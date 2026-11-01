"""Real run() integration with synthetic REST payloads; no network or real PUT."""
import copy
import importlib.util
import os
import pathlib
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('u', pathlib.Path(os.environ.get('SLOT_SOURCE', pathlib.Path(__file__).with_name('auto-update-armed-prs.py'))))
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)
CONTEXTS = ['all-green', 'Validate manifests', 'Gitleaks', 'issue-hygiene', 'OPA policy gate',
            'gates (gitops-api)', 'gates (lint-supplychain-security)', 'gates (registry-kotlin-data)']
OLD = '2026-09-01T00:00:00Z'


class Rest:
    def __init__(self, count=2):
        self.main = 'mainA'
        self.prs = {i: {'number': i, 'state': 'open', 'draft': False, 'labels': [],
                           'auto_merge': {'enabled_at': f'2026-09-{i:02}T00:00:00Z'},
                           'created_at': OLD, 'mergeable_state': 'behind',
                           'user': {'login': 'JiRaska'},
                           'head': {'sha': f'h{i}', 'ref': f'codex/test{i}', 'repo': {'full_name': 'a/b'}}}
                    for i in range(1, count+1)}
        self.behind = {f'h{i}': 1 for i in self.prs}
        self.checks = {}
        self.runs = {}
        self.puts = []
        self.pull_reads = {}
        self.drift = None
        self.fail_read = False
        self.job = {'id': 100, 'run_id': 10, 'run_attempt': 1, 'head_sha': 'h1',
                        'check_run_url': u.API+'/repos/a/b/check-runs/1'}
        self.old_run = self.workflow('h1', 'completed', ident=10)
        self.old_run['conclusion'] = 'failure'

    def workflow(self, sha, status='queued', ident=10, path='.github/workflows/ci.yml', workflow_id=70):
        return {'id': ident, 'run_attempt': 2, 'workflow_id': workflow_id, 'path': path, 'head_sha': sha,
                    'event': 'pull_request', 'status': status, 'conclusion': None,
                    'repository': {'full_name': 'a/b'}, 'pull_requests': [{'number': 1}]}

    def get(self, path):
        if self.fail_read:
            raise SystemExit('REST unavailable')
        if '/rules/branches/' in path:
            return [{'type': 'required_status_checks', 'parameters': {
                'strict_required_status_checks_policy': True,
                'required_status_checks': [{'context': c} for c in CONTEXTS]}}]
        if '/git/ref/' in path:
            return {'object': {'sha': self.main}}
        if '/pulls?' in path:
            return list(copy.deepcopy(self.prs).values())
        if '/pulls/' in path:
            n = int(path.rsplit('/', 1)[1]); self.pull_reads[n] = self.pull_reads.get(n, 0)+1
            if self.drift and self.pull_reads[n] >= 5:
                self.drift(self, n)
            return copy.deepcopy(self.prs[n])
        if '/compare/' in path:
            return {'behind_by': self.behind[path.rsplit('...', 1)[1]]}
        if '/check-runs?' in path:
            sha = path.split('/commits/')[1].split('/')[0]
            default = [{'id': i+1, 'name': c, 'status': 'completed', 'conclusion': 'success', 'completed_at': OLD}
                       for i, c in enumerate(CONTEXTS)]
            return {'check_runs': self.checks.get(sha, default)}
        if path.endswith('/status'):
            return {'statuses': []}
        if '/actions/runs?' in path:
            sha = path.split('head_sha=')[1].split('&')[0]
            return {'workflow_runs': self.runs.get(sha, [])}
        if '/actions/jobs/' in path:
            return self.job
        if path.endswith('/actions/runs/10'):
            return self.old_run
        if '/commits/' in path:
            return {'commit': {'message': 'fix: actual source',
                               'committer': {'name': 'Jiri Raska', 'date': OLD}},
                    'parents': [{'sha': 'parent'}]}
        raise AssertionError(path)

    def api(self, method, path, body=None):
        if method == 'GET':
            return 200, self.get(path)
        assert method == 'PUT' and path.endswith('/update-branch')
        n = int(path.split('/pulls/')[1].split('/')[0])
        assert body == {'expected_head_sha': self.prs[n]['head']['sha']}
        self.puts.append((n, body))
        sha = self.prs[n]['head']['sha']+'-new'
        self.prs[n]['head']['sha'] = sha
        self.prs[n]['mergeable_state'] = 'unstable'
        self.behind[sha] = 0
        self.checks[sha] = []
        self.runs[sha] = [dict(self.workflow(sha), pull_requests=[{'number': n}])]
        return 202, {'message': 'Updating pull request branch.'}

    def execute(self):
        with patch.object(u, 'get', self.get), patch.object(u, 'api', self.api):
            return u.run('a/b', 'main', 5, 20, False, 20, 4)


class RunTest(unittest.TestCase):
    def test_five_eligible_actual_run_puts_only_one(self):
        rest = Rest(5)
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(len(rest.puts), 1)
        self.assertEqual(rest.puts[0][0], 1)

    def test_successive_invocations_do_not_fan_out_same_main(self):
        rest = Rest()
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(len(rest.puts), 1)

    def retry(self, independent=False):
        rest = Rest()
        rest.behind['h1'] = 0
        rest.prs[1]['mergeable_state'] = 'unstable'
        rest.checks['h1'] = [{'id': 1, 'name': CONTEXTS[0], 'status': 'completed', 'conclusion': 'failure',
                                  'completed_at': OLD,
                                  'details_url': 'https://github.com/a/b/actions/runs/10/job/100'}]
        rest.job['run_attempt'] = 1
        rest.old_run['run_attempt'] = 1
        run = rest.workflow('h1')
        if independent:
            run.update(id=20, workflow_id=80, path='.github/workflows/services-ci.yml')
        rest.runs['h1'] = [rest.old_run, run]
        return rest

    def test_old_failure_exact_active_retry_blocks_actual_put(self):
        rest = self.retry()
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(rest.puts, [])

    def test_independent_failure_frees_slot_despite_other_workflow(self):
        rest = self.retry(independent=True)
        self.assertEqual(rest.execute(), 0)
        self.assertEqual([n for n, _ in rest.puts], [2])

    def test_blocked_draft_autooff_never_updated(self):
        for change in [{'labels': [{'name': 'blocked'}]}, {'draft': True}, {'auto_merge': None}]:
            rest = Rest(1); rest.prs[1].update(change)
            self.assertEqual(rest.execute(), 0)
            self.assertEqual(rest.puts, [])

    def test_preput_drift_head_main_hold_arm_and_open_all_zero_put(self):
        def head(r,n): r.prs[n]['head']['sha']='changed'; r.behind['changed']=1
        def main(r,n): r.main='newMain'
        def held(r,n): r.prs[n]['labels']=[{'name':'blocked'}]
        def draft(r,n): r.prs[n]['draft']=True
        def unarm(r,n): r.prs[n]['auto_merge']=None
        def closed(r,n): r.prs[n]['state']='closed'
        for drift in [head, main, held, draft, unarm, closed]:
            rest=Rest(1); rest.drift=drift
            self.assertEqual(rest.execute(),0)
            self.assertEqual(rest.puts,[])

    def test_bot_pending_reserves_but_ready_unarmed_has_no_lease(self):
        rest = Rest()
        rest.prs[1].update(auto_merge=None, user={'login': 'app/openbank-gitops-bot'})
        rest.prs[1]['head']['ref'] = 'chore/gitops-auto-deploy-test'
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(len(rest.puts), 1)
        sha = rest.prs[1]['head']['sha']
        rest.prs[1]['mergeable_state'] = 'clean'
        rest.checks.pop(sha)
        rest.runs[sha] = []
        self.assertEqual(rest.execute(), 0)
        self.assertEqual([n for n, _ in rest.puts], [1, 2])

    def test_manual_draft_activity_is_outside_updater_admission(self):
        rest = Rest()
        rest.prs[1].update(draft=True, auto_merge=None)
        rest.behind['h1'] = 0
        rest.checks['h1'] = [{'id': 1, 'name': 'build (openbank-a)', 'status': 'in_progress', 'conclusion': None}]
        self.assertEqual(rest.execute(), 0)
        self.assertEqual([n for n, _ in rest.puts], [2])

    def test_current_required_pending_or_failure_before_put_prevents_update(self):
        for status, conclusion in [('in_progress', None), ('completed', 'failure')]:
            rest = Rest(1)
            def drift(r, n, status=status, conclusion=conclusion):
                r.checks['h1'] = [{'id': 90, 'name': CONTEXTS[0], 'status': status,
                                      'conclusion': conclusion, 'completed_at': OLD}]
            rest.drift = drift
            self.assertEqual(rest.execute(), 0)
            self.assertEqual(rest.puts, [])

    def test_preput_budget_rechecked_without_reset(self):
        rest = Rest(1)
        original = rest.api
        budget_reads = []
        def api(method, path, body=None):
            if method == 'GET' and '/commits/' in path:
                budget_reads.append(path)
                if len(budget_reads) >= 3:
                    return 200, {'commit': {'message': "Merge branch 'main' into codex/test1",
                                            'committer': {'name': 'GitHub', 'date': OLD}},
                                 'parents': [{'sha': 'parent'}, {'sha': 'main'}]}
            return original(method, path, body)
        with patch.object(u, 'get', rest.get), patch.object(u, 'api', api):
            self.assertEqual(u.run('a/b', 'main', 5, 20, False, 20, 4), 0)
        self.assertEqual(rest.puts, [])
        self.assertGreaterEqual(len(budget_reads), 6)

    def test_oldest_exhausted_or_unreadable_does_not_starve_younger(self):
        for unreadable in [False, True]:
            rest = Rest()
            original = rest.api
            def api(method, path, body=None, unreadable=unreadable, original=original):
                if method == 'GET' and (path.endswith('/commits/h1') or '/commits/spent-' in path):
                    if unreadable:
                        return 404, {'message': 'not found'}
                    index = int(path.rsplit('-', 1)[1]) if '/commits/spent-' in path else 0
                    return 200, {'commit': {'message': "Merge branch 'main' into codex/test1",
                                            'committer': {'name': 'GitHub', 'date': OLD}},
                                 'parents': [{'sha': f'spent-{index+1}'}, {'sha': 'main'}]}
                return original(method, path, body)
            with patch.object(u, 'get', rest.get), patch.object(u, 'api', api):
                self.assertEqual(u.run('a/b', 'main', 5, 20, False, 20, 4), 0)
            self.assertEqual([n for n, _ in rest.puts], [2])

    def secret_retry(self, verdict='failure', status='queued'):
        rest = self.retry()
        rest.checks['h1'][0].update(name='Gitleaks', conclusion=verdict, completed_at=OLD)
        rest.checks['h1'].extend({'id': i+10, 'name': c, 'status': 'completed', 'conclusion': 'success',
                                      'completed_at': OLD} for i,c in enumerate(CONTEXTS) if c!='Gitleaks')
        rest.old_run.update(path='.github/workflows/secret-scan.yml', workflow_id=90)
        rest.runs['h1'] = [rest.old_run, dict(rest.old_run, run_attempt=2,
                                             status=status, conclusion=None)]
        rest.prs[1]['mergeable_state'] = 'clean'
        return rest

    def test_required_secret_scan_retry_reserves_before_registration(self):
        for verdict in ['failure', 'success']:
            for status in ['queued', 'pending', 'requested', 'waiting', 'in_progress']:
                rest = self.secret_retry(verdict, status)
                self.assertEqual(rest.execute(), 0)
                self.assertEqual(rest.puts, [], (verdict, status))

    def test_advisory_run_does_not_reserve_through_required_retry_mapping(self):
        rest = self.secret_retry('success')
        rest.runs['h1'] = [rest.old_run, dict(rest.old_run, id=20, workflow_id=91,
                            path='.github/workflows/advisory.yml', status='queued', conclusion=None)]
        self.assertEqual(rest.execute(), 0)
        self.assertEqual([n for n, _ in rest.puts], [2])

    def test_required_retry_missing_run_status_is_unknown_not_free(self):
        rest = self.secret_retry()
        del rest.runs['h1'][1]['status']
        self.assertEqual(rest.execute(), 0)
        self.assertEqual(rest.puts, [])

    def test_rest_failure_no_put(self):
        rest=Rest(); rest.fail_read=True
        self.assertEqual(rest.execute(),1)
        self.assertEqual(rest.puts,[])


if __name__ == '__main__':
    unittest.main()
