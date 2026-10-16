"""Hermetic real-shell pool controls; fake cosign never contacts a registry."""
import collections
import json
import os
import pathlib
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = pathlib.Path(__file__).with_name('check-fleet-attestations.sh')
FAKE = '''#!/usr/bin/env python3
import fcntl,json,os,sys,time
if sys.argv[1]=='version':print('GitVersion: v2.4.3');sys.exit(0)
p=os.environ['FAKE_STATE'];image=sys.argv[-1];kind=sys.argv[1]
predicate=sys.argv[sys.argv.index('--type')+1] if '--type' in sys.argv else 'signature'
def update(delta):
 with open(p,'r+') as f:
  fcntl.flock(f,fcntl.LOCK_EX);d=json.load(f);d['active']+=delta
  d['peak']=max(d['peak'],d['active'])
  if delta==1:d['calls'].append([image,predicate])
  f.seek(0);json.dump(d,f);f.truncate()
update(1)
try:
 if os.environ.get('FAKE_FIRST_WAVE_BARRIER')=='4' and predicate=='signature':
  # Only this healthy fixture rendezvous: a single shared deadline releases the
  # first wave even for serial/cap3 negative controls, never one timeout per image.
  while True:
   with open(p,'r+') as f:
    fcntl.flock(f,fcntl.LOCK_EX);d=json.load(f)
    d.setdefault('barrier_deadline',time.monotonic()+8)
    seen=d.setdefault('barrier_seen',[])
    if image not in seen:seen.append(image)
    if not d.get('barrier_released') and (len(seen)>=4 or time.monotonic()>=d['barrier_deadline']):
     d['barrier_released']=True;d['barrier_release_count']=len(seen)
    released=d.get('barrier_released',False)
    f.seek(0);json.dump(d,f);f.truncate()
   if released:break
   time.sleep(.01)
 time.sleep(.025)
finally:update(-1)
if 'unknown' in image:print('Error: connection reset by peer',file=sys.stderr);sys.exit(1)
if 'absent' in image:print('Error: MANIFEST_UNKNOWN',file=sys.stderr);sys.exit(1)
if 'gap' in image and predicate.endswith('two'):print('Error: no matching attestations:',file=sys.stderr);sys.exit(1)
print('verified')
'''

class WorkersTest(unittest.TestCase):
    def run_gate(self, names, workers=4, mutate=None, threshold=5, attempts=2, script=None, interrupt=False, barrier=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            gitops = root/'gitops'; gitops.mkdir()
            registry = 'fixture.invalid'
            (gitops/'images.yaml').write_text(''.join(f'image: {registry}/openbank-{n}:t\n' for n in names))
            policies = root/'policies'; policies.mkdir()
            (policies/'verify-fixture.yaml').write_text('predicateType: https://fixture.invalid/one\npredicateType: https://fixture.invalid/two\n')
            arc = root/'arc.tf'
            arc.write_text('runner_image = "'+registry+'/openbank-ci-runner@sha256:'+'1'*64+'"\n')
            fake=root/'cosign'; fake.write_text(FAKE); fake.chmod(0o700)
            state=root/'state.json'; state.write_text(json.dumps({'active':0,'peak':0,'calls':[]}))
            report=root/'report.json'
            target = script or SCRIPT
            if mutate:
                target=root/'mutant.sh'; target.write_text(mutate(SCRIPT.read_text()))
            env=dict(os.environ, GITOPS_DIR=str(gitops), KYVERNO_DIR=str(policies),
                     ARC_RUNNERS_TF=str(arc), ECR_REGISTRY=registry, COSIGN_KEY='fixture-key',
                     COSIGN_BIN=str(fake), PLACEHOLDER_FILE=str(root/'none'), FAKE_STATE=str(state),
                     FLEET_ATTEST_JSON=str(report), VERIFY_ATTEMPTS=str(attempts), VERIFY_RETRY_SLEEP='0',
                     SYSTEMIC_UNKNOWN_THRESHOLD=str(threshold), FLEET_ATTEST_WORKERS=str(workers), FAKE_FIRST_WAVE_BARRIER='4' if barrier else '')
            if interrupt:
                process=subprocess.Popen(['bash',str(target)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                for _ in range(500):
                    try:
                        if json.loads(state.read_text())['active']:
                            break
                    except json.JSONDecodeError:
                        pass
                    time.sleep(.01)
                else:
                    process.kill(); process.communicate()
                    self.fail('no verification worker started')
                process.send_signal(signal.SIGINT)
                stdout, stderr=process.communicate(timeout=20)
                result=subprocess.CompletedProcess(process.args,process.returncode,stdout,stderr)
                self.assertEqual(json.loads(state.read_text())['active'],0)
            else:
                result=subprocess.run(['bash',str(target)],env=env,capture_output=True,text=True,timeout=60,check=False)
            return result, json.loads(report.read_text()) if report.exists() else None, json.loads(state.read_text())

    def test_healthy_eighty_complete_and_actual_four_way_concurrency(self):
        result, report, state=self.run_gate([f'healthy-{i:03}' for i in range(79)],barrier=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertEqual((report['total'],report['attested'],report['unknown']),(80,80,0))
        self.assertEqual(state['peak'],4)
        self.assertEqual(state['barrier_release_count'],4)
        calls=collections.defaultdict(list)
        for image, predicate in state['calls']:calls[image].append(predicate)
        self.assertEqual(len(calls),80)
        self.assertTrue(all(p==['signature','https://fixture.invalid/one','https://fixture.invalid/two'] for p in calls.values()))

    def test_serial_and_parallel_full_verdict_parity(self):
        names=['healthy','late-gap','absent','unknown']
        serial, a, _=self.run_gate(names,1,threshold=99)
        parallel, b, state=self.run_gate(names,4,threshold=99)
        self.assertEqual((serial.returncode,parallel.returncode),(1,1))
        for key in ['total','attested','unattested','absent','unknown','gateResult','unattestedImages','absentImages','unknownImages']:
            self.assertEqual(a[key],b[key],key)
        self.assertLessEqual(state['peak'],4)

    def test_unknown_only_never_success(self):
        result, report, _=self.run_gate(['unknown'])
        self.assertEqual(result.returncode,2)
        self.assertEqual(report['gateResult'],'INCOMPLETE')

    def test_worker_crash_missing_and_extra_result_fail_closed(self):
        needle='  printf \'%s\\n\' "$image" > "$result/image"'
        self.assertIn(needle,SCRIPT.read_text())
        for replacement in ['  return 9', needle+'\n  : > "$result/extra"', needle+'\n  rm "$result/image"']:
            with self.subTest(replacement=replacement):
                result, report, _=self.run_gate(['healthy'],mutate=lambda s,replacement=replacement:s.replace(needle,replacement))
                self.assertEqual(result.returncode,2,result.stdout)
                self.assertEqual(report['unknown'],2)
                self.assertEqual(report['attested'],0)

    def test_corrupt_attempt_range_is_unknown(self):
        needle='  printf \'%s\\n\' "$attempt" > "$result/attempt"'
        self.assertIn(needle,SCRIPT.read_text())
        for attempt in [0,999,'9'*100,'junk1']:
            with self.subTest(attempt=attempt):
                result, report, _=self.run_gate(['healthy'],mutate=lambda s,attempt=attempt:s.replace(needle,f'  printf "%s\\n" "{attempt}" > "$result/attempt"'))
                self.assertEqual(result.returncode,2)
                self.assertEqual(report['attested'],0)
                self.assertEqual(report['unknown'],2)

    def test_systemic_unknown_disables_new_work_retries_bounded_inflight(self):
        result, report, state=self.run_gate([f'unknown-{i:02}' for i in range(12)],threshold=3)
        self.assertEqual(result.returncode,2)
        self.assertEqual(report['unknown'],12)
        counts=collections.Counter(image for image,_ in state['calls'] if 'unknown' in image)
        # Once three ordered UNKNOWN verdicts are folded, only the other three
        # already-admitted image groups can have started with retries enabled.
        # This fixture uses2attempts; the default3 allows at most9 extra attempts
        # across3groups, not3individual network probes.
        self.assertTrue(all(counts['fixture.invalid/openbank-unknown-'+f'{i:02}'+':t']==1 for i in range(6,12)))
        self.assertLessEqual(sum(counts.values()),18)
        self.assertIn('Retries OFF',result.stdout)

    def test_parent_interrupt_drains_owned_workers(self):
        result, _, state=self.run_gate([f'healthy-{i}' for i in range(8)],interrupt=True)
        self.assertEqual(result.returncode,130)
        self.assertEqual(state['active'],0)

    def test_invalid_width_fails_without_cosign_calls(self):
        result, _, state=self.run_gate(['healthy'],workers=5)
        self.assertEqual(result.returncode,2)
        self.assertEqual(state['calls'],[])

if __name__=='__main__':unittest.main()
