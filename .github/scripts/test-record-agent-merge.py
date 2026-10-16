#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the agent-merge recorder without credentials or a reachable backend."""
import http.server
import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

import yaml

spec = importlib.util.spec_from_file_location('recorder', Path(__file__).with_name('record-agent-merge.py'))
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)

AGENT_ACCOUNTS = {'openbank-agent-bot'}
# Mirrors load_agent_ids output ordering (longest-first); the loader sorts, unit tests
# pass the sorted list directly.
AGENT_IDS = sorted({'flaky-test-hunter', 'finops-agent', 'ui-assistant'}, key=len, reverse=True)


def pull(number=12747, branch='agent/adr-0031-phase3-gated-pr-path', author='JiRaska',
         merged_by='JiRaska', merged_by_type='User', **extra):
    item = {
        'number': number,
        'html_url': f'https://github.com/JiRaska/open-bank-oss/pull/{number}',
        'title': 'feat(docs): ADR-0031 phase 3 evidence',
        'head': {'ref': branch},
        'user': {'login': author},
        'merged_by': {'login': merged_by, 'type': merged_by_type},
        'merged_at': '2026-10-14T12:00:00Z',
        'body': '',
    }
    item.update(extra)
    return item


class DecideAgentIdTest(unittest.TestCase):
    def test_registry_prefix_maps_hunter_branch(self):
        # THE #12822 finding: flaky-test-hunter opens PRs from
        # `flaky-test-hunter/unit-return-<finding>` (PR_BRANCH_PREFIX in its Kotlin source).
        # The old first-dash-segment rule matched no prefix here, so a hunter merge would
        # have passed green UNRECORDED.
        self.assertEqual(recorder.decide_agent_id('flaky-test-hunter/unit-return-42',
                                                  'JiRaska', AGENT_ACCOUNTS, AGENT_IDS),
                         'flaky-test-hunter')

    def test_agent_prefixed_registry_id_maps_to_registry_id(self):
        self.assertEqual(recorder.decide_agent_id('agent/flaky-test-hunter-quarantine-1',
                                                  'JiRaska', AGENT_ACCOUNTS, AGENT_IDS),
                         'flaky-test-hunter')

    def test_bare_registry_id_prefix_maps_to_registry_id(self):
        self.assertEqual(recorder.decide_agent_id('finops-agent/cost-report-7',
                                                  'JiRaska', AGENT_ACCOUNTS, AGENT_IDS),
                         'finops-agent')

    def test_longest_registry_match_wins(self):
        ids = sorted({'short', 'short-longer'}, key=len, reverse=True)
        self.assertEqual(recorder.decide_agent_id('short-longer/x', 'JiRaska',
                                                  AGENT_ACCOUNTS, ids), 'short-longer')
        self.assertEqual(recorder.decide_agent_id('short/x', 'JiRaska',
                                                  AGENT_ACCOUNTS, ids), 'short')

    def test_unchartered_agent_prefix_records_external_not_made_up_segment(self):
        # THE other half of the #12822 finding: an ADR number is not an agent. An
        # unchartered agent/ session records as the external-coding-agent constant —
        # never as a fabricated "adr"/"docs" segment id (false provenance in an audit store).
        self.assertEqual(recorder.decide_agent_id('agent/adr-0031-phase3-gated-pr-path',
                                                  'JiRaska', AGENT_ACCOUNTS, AGENT_IDS),
                         'external-coding-agent')
        self.assertEqual(recorder.decide_agent_id('agent/docs-fleet-ci', 'JiRaska',
                                                  AGENT_ACCOUNTS, AGENT_IDS),
                         'external-coding-agent')

    def test_codex_prefix_maps_to_constant(self):
        self.assertEqual(recorder.decide_agent_id('codex/docs-fleet-ci', 'JiRaska',
                                                  AGENT_ACCOUNTS, AGENT_IDS),
                         'external-coding-agent')
        self.assertEqual(recorder.decide_agent_id('codex/anything-at-all', 'someone',
                                                  AGENT_ACCOUNTS, AGENT_IDS),
                         'external-coding-agent')

    def test_declared_agent_author_on_prefixless_branch_maps_to_login(self):
        # The login is recorded verbatim, whatever spelling GitHub supplied — the
        # declaration list is normalised, the recorded identity is not.
        self.assertEqual(recorder.decide_agent_id('fix/human', 'openbank-agent-bot[bot]',
                                                  AGENT_ACCOUNTS, AGENT_IDS),
                         'openbank-agent-bot[bot]')
        # The `app/...` spelling of the same declared account must also match (#6403 lesson).
        self.assertEqual(recorder.decide_agent_id('chore/x', 'app/openbank-agent-bot',
                                                  AGENT_ACCOUNTS, AGENT_IDS),
                         'app/openbank-agent-bot')

    def test_non_agent_pr_is_noop(self):
        for branch, author in [('fix/human', 'JiRaska'), ('release-please--branches--main',
                               'release-please[bot]'), ('agent/', 'JiRaska')]:
            with self.subTest(branch=branch, author=author):
                self.assertIsNone(recorder.decide_agent_id(branch, author, AGENT_ACCOUNTS,
                                                           AGENT_IDS))


class PayloadTest(unittest.TestCase):
    def test_payload_shape(self):
        payload = recorder.build_payload(pull(), 'adr')
        self.assertEqual(payload, {
            'agentId': 'adr',
            'prNumber': 12747,
            'prUrl': 'https://github.com/JiRaska/open-bank-oss/pull/12747',
            'headBranch': 'agent/adr-0031-phase3-gated-pr-path',
            'mergedBy': 'JiRaska',
            'mergedAt': '2026-10-14T12:00:00Z',
            'title': 'feat(docs): ADR-0031 phase 3 evidence',
            'source': 'github-actions.record-agent-merge',
        })

    def test_proposal_id_is_optional(self):
        self.assertNotIn('proposalId', recorder.build_payload(pull(), 'adr'))
        self.assertEqual(recorder.build_payload(pull(), 'adr', '42')['proposalId'], '42')

    def test_proposal_id_from_body_line_and_env_override(self):
        body = 'Some prose.\nProposal-Id: 99\nmore prose'
        self.assertEqual(recorder.extract_proposal_id(pull(body=body)), '99')
        with mock.patch.dict(os.environ, {'AGENT_MERGE_PROPOSAL_ID': '7'}):
            # Env override wins over the body line.
            self.assertEqual(recorder.extract_proposal_id(pull(body=body)), '7')
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(recorder.extract_proposal_id(pull()))


class MainTest(unittest.TestCase):
    """End-to-end over main(): event file on disk, env injected, real exit codes."""

    def run_main(self, event, env=None, rules=None, agents=None, token_file=None):
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        event_path = Path(tmpdir.name) / 'event.json'
        event_path.write_text(json.dumps(event))
        rules_path = Path(tmpdir.name) / 'rules.yaml'
        rules_path.write_text(rules if rules is not None else
                              'autonomous_agent_prs:\n  agent_accounts:\n'
                              '    - openbank-agent-bot[bot]\n')
        # Explicit registry fixture (instead of the repo default) keeps the suite
        # independent of the checkout's cwd.
        agents_path = Path(tmpdir.name) / 'agents.yaml'
        agents_path.write_text(agents if agents is not None else
                               'agents:\n  - id: flaky-test-hunter\n')
        argv = [sys.executable, str(Path(__file__).with_name('record-agent-merge.py')),
                '--event', str(event_path), '--rules', str(rules_path),
                '--agents', str(agents_path)]
        if token_file:
            argv += ['--token-file', str(token_file)]
        clean_env = {k: v for k, v in os.environ.items()
                     if k not in ('AGENT_SERVICE_URL', 'AGENT_MERGE_RECORDER_TOKEN',
                                  'AGENT_MERGE_PROPOSAL_ID')}
        clean_env.update(env or {})
        return subprocess.run(argv, capture_output=True, text=True, check=False, env=clean_env)

    def closed_event(self, **pull_kwargs):
        return {'action': 'closed', 'pull_request': pull(**pull_kwargs)}

    def test_non_agent_pr_exits_zero_with_nothing_to_record(self):
        result = self.run_main(self.closed_event(branch='fix/human', author='JiRaska'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nothing to record', result.stdout)

    def test_malformed_event_fails_loud(self):
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        event_path = Path(tmpdir.name) / 'event.json'
        event_path.write_text('{}')
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name('record-agent-merge.py')),
             '--event', str(event_path), '--dry-run'],
            capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn('CI wiring bug', result.stderr)

    def test_unreachable_endpoint_fails_closed(self):
        # A port that is guaranteed closed: bind, learn the port, release it.
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        result = self.run_main(
            self.closed_event(),
            env={'AGENT_SERVICE_URL': f'http://127.0.0.1:{port}',
                 'AGENT_MERGE_RECORDER_TOKEN': 'token'})
        self.assertEqual(result.returncode, 1)
        self.assertIn('::error::record-agent-merge', result.stderr)
        self.assertIn('NOT recorded', result.stderr)

    def test_missing_config_fails_closed(self):
        for env in ({}, {'AGENT_SERVICE_URL': 'http://agent-service.platform.svc:8109'},
                    {'AGENT_MERGE_RECORDER_TOKEN': 'token'}):
            with self.subTest(env=env):
                result = self.run_main(self.closed_event(), env=env)
                self.assertEqual(result.returncode, 1)
                self.assertIn('::error::record-agent-merge', result.stderr)

    def test_rules_unreadable_fails_closed(self):
        result = self.run_main(self.closed_event(),
                               env={'AGENT_SERVICE_URL': 'http://127.0.0.1:9',
                                    'AGENT_MERGE_RECORDER_TOKEN': 'token'},
                               rules='not: [valid')
        self.assertEqual(result.returncode, 1)
        self.assertIn('undecidable scope', result.stderr)

    def test_agents_registry_unreadable_fails_closed(self):
        result = self.run_main(self.closed_event(),
                               env={'AGENT_SERVICE_URL': 'http://127.0.0.1:9',
                                    'AGENT_MERGE_RECORDER_TOKEN': 'token'},
                               agents='agents: nope')
        self.assertEqual(result.returncode, 1)
        self.assertIn('undecidable scope', result.stderr)

    def test_merged_by_absent_fails_closed(self):
        # pull() always sets a dict; simulate the absent-identity shape directly.
        event = self.closed_event()
        event['pull_request']['merged_by'] = None
        result = self.run_main(event)
        self.assertEqual(result.returncode, 1)
        self.assertIn('::error::', result.stderr)

    def test_bot_merger_is_not_recorded_as_human(self):
        for login, actor_type in (('dependabot[bot]', 'Bot'), ('openbank-agent-bot[bot]', 'User'),
                                  ('app/openbank-agent-bot', 'User'),
                                  ('openbank-agent-bot', 'User'),
                                  ('OPENBANK-AGENT-BOT', 'User')):
            with self.subTest(login=login, actor_type=actor_type):
                result = self.run_main(self.closed_event(merged_by=login,
                                                        merged_by_type=actor_type))
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('merged_by', result.stderr)

    def test_missing_or_non_user_merger_type_fails_closed(self):
        for actor_type in (None, 'Bot', 'Organization'):
            with self.subTest(actor_type=actor_type):
                event = self.closed_event(merged_by_type=actor_type)
                if actor_type is None:
                    del event['pull_request']['merged_by']['type']
                result = self.run_main(event)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('merged_by', result.stderr)

    def test_malformed_merger_object_fails_closed(self):
        for merger in ('JiRaska', ['JiRaska'], 7):
            with self.subTest(merger=merger):
                event = self.closed_event()
                event['pull_request']['merged_by'] = merger
                result = self.run_main(event)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('merged_by', result.stderr)

    def test_happy_path_posts_expected_record(self):
        received = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                received['path'] = self.path
                received['auth'] = self.headers.get('Authorization')
                received['content_type'] = self.headers.get('Content-Type')
                length = int(self.headers.get('Content-Length') or 0)
                received['body'] = json.loads(self.rfile.read(length))
                self.send_response(201)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            # The chartered-agent shape that matters: hunter opens PRs from
            # flaky-test-hunter/unit-return-<finding>; the record must carry the
            # declared registry id, not a made-up segment or a silent skip.
            result = self.run_main(
                self.closed_event(branch='flaky-test-hunter/unit-return-9'),
                env={'AGENT_SERVICE_URL': f'http://127.0.0.1:{server.server_port}',
                     'AGENT_MERGE_RECORDER_TOKEN': 'secret-token'})
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('recorded', result.stdout)
        self.assertEqual(received['path'], '/api/v1/agent-merge-records')
        self.assertEqual(received['auth'], 'Bearer secret-token')
        self.assertEqual(received['content_type'], 'application/json')
        self.assertEqual(received['body']['agentId'], 'flaky-test-hunter')
        self.assertEqual(received['body']['mergedBy'], 'JiRaska')
        self.assertEqual(received['body']['prNumber'], 12747)

    def test_happy_path_records_unchartered_session_as_external(self):
        received = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get('Content-Length') or 0)
                received['body'] = json.loads(self.rfile.read(length))
                self.send_response(201)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            # An unchartered agent/ session (no registry id) still records — as the
            # external-coding-agent constant, never as a fabricated segment id.
            result = self.run_main(
                self.closed_event(branch='agent/adr-0031-phase3-gated-pr-path'),
                env={'AGENT_SERVICE_URL': f'http://127.0.0.1:{server.server_port}',
                     'AGENT_MERGE_RECORDER_TOKEN': 'secret-token'})
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(received['body']['agentId'], 'external-coding-agent')
        self.assertEqual(received['body']['headBranch'], 'agent/adr-0031-phase3-gated-pr-path')

    def test_non_2xx_response_fails_closed(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.send_response(401)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = self.run_main(
                self.closed_event(),
                env={'AGENT_SERVICE_URL': f'http://127.0.0.1:{server.server_port}',
                     'AGENT_MERGE_RECORDER_TOKEN': 'token'})
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(result.returncode, 1)
        self.assertIn('::error::record-agent-merge', result.stderr)
        self.assertIn('HTTP 401', result.stderr)

    def test_token_file_posts_with_minted_header(self):
        # The workflow mints a client_credentials token into a 0600 header file and
        # passes --token-file; the script must prefer it over the env fallback.
        received = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                received['auth'] = self.headers.get('Authorization')
                length = int(self.headers.get('Content-Length') or 0)
                self.rfile.read(length)
                self.send_response(201)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        token_file = Path(tmpdir.name) / 'token'
        token_file.write_text('Authorization: Bearer minted-fresh-token')
        try:
            result = self.run_main(
                self.closed_event(),
                env={'AGENT_SERVICE_URL': f'http://127.0.0.1:{server.server_port}',
                     # Deliberately ALSO set: --token-file must win over the env fallback.
                     'AGENT_MERGE_RECORDER_TOKEN': 'stale-static-token'},
                token_file=token_file)
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(received['auth'], 'Bearer minted-fresh-token')

    def test_bare_token_file_accepted(self):
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        token_file = Path(tmpdir.name) / 'token'
        token_file.write_text('bare-token-no-header-prefix')
        result = self.run_main(
            self.closed_event(),
            env={'AGENT_SERVICE_URL': 'http://127.0.0.1:9'},
            token_file=token_file)
        # The token parsed; the POST itself fails closed against the closed port — the
        # assertion here is that the failure is the POST, not the token file.
        self.assertEqual(result.returncode, 1)
        self.assertIn('could not reach', result.stderr)

    def test_missing_token_file_fails_closed(self):
        result = self.run_main(
            self.closed_event(),
            env={'AGENT_SERVICE_URL': 'http://127.0.0.1:9'},
            token_file='/nonexistent/token-file')
        self.assertEqual(result.returncode, 1)
        self.assertIn('cannot read token file', result.stderr)

    def test_empty_token_file_fails_closed(self):
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        token_file = Path(tmpdir.name) / 'token'
        token_file.write_text('')
        result = self.run_main(
            self.closed_event(),
            env={'AGENT_SERVICE_URL': 'http://127.0.0.1:9'},
            token_file=token_file)
        self.assertEqual(result.returncode, 1)
        self.assertIn('is empty', result.stderr)


class WorkflowWiringTest(unittest.TestCase):
    def test_workflow_is_thin_and_pins_actions_by_sha(self):
        workflow = yaml.safe_load(
            (Path(__file__).parents[1] / 'workflows/record-agent-merge.yml').read_text())
        self.assertEqual(workflow['permissions'], {'contents': 'read'})
        # PyYAML 1.1 parses the bare key `on:` as boolean True.
        events = workflow.get('on', workflow.get(True))
        self.assertEqual(events['pull_request']['types'], ['closed'])
        self.assertEqual(events['pull_request']['branches'], ['main'])
        self.assertIn('concurrency', workflow)
        record = workflow['jobs']['record']
        self.assertIn('merged == true', record['if'])
        # In-cluster ARC runner — cluster-DNS service URLs do not resolve on
        # GitHub-hosted runners; that was the live defect this wiring exists to prevent.
        self.assertEqual(record['runs-on'], 'openbank-build')
        checkout = record['steps'][0]
        self.assertRegex(checkout['uses'], r'^actions/checkout@[0-9a-f]{40}$')
        runs = [step.get('run', '') for step in record['steps']]
        self.assertTrue(any('Fail closed on missing configuration' in step.get('name', '')
                            for step in record['steps']))
        mint_index = next(i for i, step in enumerate(record['steps'])
                          if 'Mint OIDC token' in step.get('name', ''))
        self.assertIn('grant_type=client_credentials', runs[mint_index])
        self.assertIn('protocol/openid-connect/token', runs[mint_index])
        # The minted token reaches the script via --token-file, never via env/argv.
        record_step = record['steps'][-1]
        self.assertIn('record-agent-merge.py --token-file', record_step['run'])
        env = workflow['env']
        self.assertIn('vars.AGENT_SERVICE_URL', env['AGENT_SERVICE_URL'])
        self.assertIn('vars.AGENT_MERGE_RECORDER_ISSUER', env['AGENT_MERGE_RECORDER_ISSUER'])
        for key in ('AGENT_MERGE_RECORDER_CLIENT_ID', 'AGENT_MERGE_RECORDER_CLIENT_SECRET'):
            self.assertIn('secrets.' + key, record['env'][key])
            self.assertNotIn(key, env)
            for name, job in workflow['jobs'].items():
                if name != 'record':
                    self.assertNotIn('secrets.' + key, yaml.safe_dump(job))
        # No static bearer JWT anywhere in the workflow: the realm's 300-second
        # accessTokenLifespan would kill it minutes after provisioning.
        self.assertNotIn('AGENT_MERGE_RECORDER_TOKEN', json.dumps(workflow))

    def test_failure_tracking_requires_verified_backfill_before_closure(self):
        workflow = yaml.safe_load(
            (Path(__file__).parents[1] / 'workflows/record-agent-merge.yml').read_text())
        raise_issue = workflow['jobs']['raise-issue']
        self.assertEqual(raise_issue['permissions']['issues'], 'write')
        self.assertIn('failure()', raise_issue['if'])
        self.assertIn('gh issue create', yaml.safe_dump(raise_issue))
        self.assertEqual(raise_issue['env']['GH_REPO'], '${{ github.repository }}')
        self.assertIn('gh issue comment', yaml.safe_dump(raise_issue))
        self.assertNotIn('close-issue', workflow['jobs'])
        self.assertNotIn('gh issue close', yaml.safe_dump(workflow['jobs']))
        self.assertIn('backfilled and its merge record verified', yaml.safe_dump(raise_issue))



if __name__ == '__main__':
    # SUBJECTS= for run-gates.py min_subjects floor: report the ACTUAL loaded test
    # count so deleting cases drops the floor, not just the coverage.
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules['__main__'])
    print(f'SUBJECTS={suite.countTestCases()}')
    unittest.main()
