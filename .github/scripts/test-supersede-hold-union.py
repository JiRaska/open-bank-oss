"""Mock all GitHub calls; exercise actual inherit_hold writer path offline."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location('merge', HERE / 'merge-deploy-holds.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
SERVICE = 'openbank-sanctions-service'
A = {'issue': '#11', 'reason': 'existing owner', 'release_condition': 'existing owner approves'}
B = {'issue': '#22', 'reason': 'donor owner\nraw', 'release_condition': 'donor owner approves'}
C = {'issue': '#33', 'reason': 'other donor condition', 'release_condition': 'other review'}


def doc(item):
    return yaml.safe_dump({'deploy_holds': {'services': {SERVICE: item}}})


FAKE = '''#!/usr/bin/env python3
import base64,json,os,pathlib,sys
a=sys.argv[1:]; d=pathlib.Path(os.environ['FIXTURE']); mode=os.environ['MODE']
with (d/'events').open('a') as f:f.write(json.dumps(a)+'\\n')
if a[:2]==['pr','view']:
 n=a[2]
 if 'headRefName' in a: print('test-survivor');sys.exit(0)
 counter=d/('head-'+n); count=int(counter.read_text()) if counter.exists() else 0;counter.write_text(str(count+1))
 print(('c' if (mode=='headchange' and n=='1' or mode=='keepheadchange' and n=='2') and count else ('a' if n=='1' else 'b'))*40);sys.exit(0)
if a[:2] in (['pr','ready'],['pr','edit']):
 if (mode=='readyfail' and a[1]=='ready') or (mode=='editfail' and a[1]=='edit'):sys.exit(1)
 sys.exit(0)
if a and a[0]=='api':
 if '-X' in a:
  if mode=='putfail':sys.exit(1)
  (d/'put.json').write_text(pathlib.Path(a[a.index('--input')+1]).read_text());sys.exit(0)
 donor='ref='+('a'*40) in a[1]
 if mode=='readfail' and donor:sys.exit(1)
 text=(d/('donor' if donor else 'target')).read_text()
 print(json.dumps({'sha':'rules-blob','content':base64.b64encode(text.encode()).decode()}));sys.exit(0)
raise SystemExit('unexpected mocked command '+str(a))
'''


class Integration(unittest.TestCase):
    def exercise(self, donor, mode='ok'):
        with tempfile.TemporaryDirectory() as temp:
            d = Path(temp)
            (d/'gh').write_text(FAKE); (d/'gh').chmod(0o755)
            prefix = "# untouched prefix\nsource_marker: 'quote stays'\n"
            suffix = '\n# untouched suffix\nunrelated: {hex: 0x10}\n\n\n'
            (d/'target').write_text(prefix + doc(A) + suffix); (d/'donor').write_text(donor)
            env = dict(os.environ, PATH=str(d)+os.pathsep+os.environ['PATH'], FIXTURE=temp,
                       MODE=mode, REPO='fixture/repo', DRY_RUN='false')
            # The same-directory library copy preserves production BASH_SOURCE helper lookup.
            text = (HERE/'supersede-deploy-prs.sh').read_text().split('if [ "${1:-}" = "--self-test" ]; then')[0]
            with tempfile.NamedTemporaryFile(mode='w', suffix='.sh', dir=HERE, delete=False) as script:
                script.write(text+'\ninherit_hold 1 2 '+SERVICE+'\n'); name=script.name
            try:
                result=subprocess.run(['bash',name],env=env,capture_output=True,text=True)
            finally:
                Path(name).unlink()
            events=[json.loads(x) for x in (d/'events').read_text().splitlines()]
            self.assertNotEqual(result.returncode,0, result.stdout+result.stderr)
            self.assertEqual(events[0][:2], ['pr','ready'])
            self.assertEqual(events[1][:2], ['pr','edit'])
            self.assertFalse(any(e[:2]==['pr','close'] for e in events))
            body=json.loads((d/'put.json').read_text()) if (d/'put.json').exists() else None
            if mode in ('readyfail', 'editfail'):
                self.assertEqual(len(events), 2)
            if body:
                raw = base64.b64decode(body['content']).decode()
                self.assertTrue(raw.startswith(prefix))
                self.assertTrue(raw.endswith(suffix), repr(raw[-100:]))
                return m.gate.holds(raw)
            return None

    def test_all_workflow_callers_use_hash_pinned_parser_runtime(self):
        for name in ('auto-deploy.yml', 'admin-ui-deploy.yml', 'deploy-window-flush.yml'):
            workflow = yaml.load((HERE.parent / 'workflows' / name).read_text(), Loader=m.gate.UniqueLoader)
            callers = [(job, index, step) for job in workflow['jobs'].values()
                       for index, step in enumerate(job.get('steps', []))
                       if 'bash .github/scripts/supersede-deploy-prs.sh' in step.get('run', '')]
            self.assertEqual(len(callers), 1, name)
            job, index, step = callers[0]
            self.assertEqual(step['env']['DEPLOY_HOLD_PYTHON'],
                             '${{ runner.temp }}/deploy-hold-venv/bin/python', name)
            self.assertTrue(any('--require-hashes' in setup.get('run', '')
                                and 'pyyaml-requirements.txt' in setup.get('run', '')
                                for setup in job['steps'][:index]), name)

    def test_existing_conflicting_legacy_conditions(self):
        result=self.exercise(doc(B))
        values=m.gate.constraints(result[SERVICE])
        self.assertEqual([{f:v[f] for f in m.gate.FIELDS} for v in values],[A,B])
        self.assertEqual(values[1]['origins'],[{'pr':1,'head':'a'*40}])

    def test_protection_api_failures_prevent_reads_writes_and_closure(self):
        for mode in ('readyfail', 'editfail'):
            with self.subTest(mode=mode):
                self.assertIsNone(self.exercise(doc(B), mode))

    def test_large_payload_remains_complete(self):
        large = {**B, 'reason': 'raw owner condition ' + 'x' * 200000}
        result = self.exercise(doc(large))
        self.assertEqual(m.gate.constraints(result[SERVICE])[1]['reason'], large['reason'])

    def test_new_format_keeps_all_conditions(self):
        result=self.exercise(doc({'all_of':[B,C]}))
        self.assertEqual(len(m.gate.constraints(result[SERVICE])),3)

    def test_refuses_missing_malformed_acquisition_head_change(self):
        for donor,mode in [('deploy_holds: {services: {}}','ok'),('deploy_holds: [','ok'),
                           (doc(B),'readfail'),(doc(B),'headchange'),(doc(B),'keepheadchange'),(doc(B),'putfail')]:
            with self.subTest(mode=mode,donor=donor):
                self.assertIsNone(self.exercise(donor,mode))


if __name__=='__main__':unittest.main()
