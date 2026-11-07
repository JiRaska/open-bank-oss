#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the agent-merge recorder without credentials or a reachable backend."""
import http.server
import ast
import contextlib
import io
import importlib.util
import json
import os
import re
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


class ReadOnlyTest(unittest.TestCase):
    """Real HTTP reads, with independent tripwires against creating a missing row."""

    def observe(self, flags, capability=None, row_status=200, bad_row=False,
                bad_json=False, actor='JiRaska', merged=True, proof_error=False,
                policy=False, env_overrides=None):
        calls = []
        canonical = pull(number=7, branch='agent/read-proof', merged=merged, state='closed',
                         head={'ref': 'agent/read-proof', 'sha': 'a' * 40},
                         base={'ref': 'main', 'repo': {'full_name': 'JiRaska/open-bank-oss'}})
        proof = {'mergedByType': 'GITHUB_APP', 'mergedById': 12, 'headSha': 'a' * 40,
                 'mergeCommitSha': 'b' * 40,
                 'policyAuthorization': {'kind': 'POLICY_PR_AUTO_MERGE', 'scope': 'PR',
                                         'approvedBy': 'JiRaska', 'approvedById': 1,
                                         'policyCommitSha': 'c' * 40, 'policyBlobSha': 'd' * 40}}
        row = {'agentId': 'external-coding-agent', 'prNumber': 7,
               'prUrl': 'https://github.com/JiRaska/open-bank-oss/pull/7',
               'headBranch': 'agent/read-proof', 'mergedBy': 'JiRaska',
               'mergedByType': 'HUMAN', 'mergedAt': canonical['merged_at'],
               'title': 'unlogged-row-marker'}
        if policy:
            canonical['merged_by'] = {'login': recorder.BOT, 'type': 'Bot'}
            row.update(proof, mergedBy=recorder.BOT)
        if bad_row:
            row['mergedBy'] = 'different-executor'
        capability = capability if capability is not None else {
            'evidenceSchemaVersion': 2, 'policyPrAuthorization': True}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(('GET', self.path, self.headers.get('Authorization')))
                is_capability = self.path.endswith('/capabilities')
                self.send_response(200 if is_capability else row_status)
                self.end_headers()
                value = capability if is_capability else row
                malformed = bad_json is True or (bad_json == 'row' and not is_capability)
                self.wfile.write(b'not-json' if malformed else json.dumps(value).encode())

            def do_POST(self):
                calls.append(('POST', self.path, None))
                self.send_response(500)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        github = mock.Mock()
        github.get.side_effect = lambda path: ({
            'full_name': 'JiRaska/open-bank-oss',
            'owner': {'login': 'JiRaska', 'type': 'User', 'id': 1}}
            if path == '/repos/JiRaska/open-bank-oss' else canonical)
        env = {'GITHUB_REPOSITORY': 'JiRaska/open-bank-oss', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '1',
               'GITHUB_ACTOR': actor, 'GITHUB_TRIGGERING_ACTOR': 'JiRaska', 'GITHUB_ACTOR_ID': '1',
               'AGENT_SERVICE_URL': f'http://127.0.0.1:{server.server_port}',
               'AGENT_MERGE_RECORDER_TOKEN': 'unlogged-token-marker'}
        env.update(env_overrides or {})
        out, err = io.StringIO(), io.StringIO()
        try:
            with mock.patch.dict(os.environ, env, clear=True), \
                 mock.patch.object(recorder, 'GitHub', return_value=github), \
                 mock.patch.object(recorder, 'load_agent_accounts', return_value=AGENT_ACCOUNTS), \
                 mock.patch.object(recorder, 'load_agent_ids', return_value=AGENT_IDS), \
                 mock.patch.object(recorder, 'app_authorization', return_value=proof,
                                   side_effect=ValueError('unlogged-proof-marker') if proof_error else None), \
                 mock.patch.object(recorder, 'post_record', side_effect=AssertionError('write attempted')) as post, \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                result = recorder.main(flags)
                post.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertFalse(any(method != 'GET' for method, _, _ in calls), calls)
        for marker in ('unlogged-token-marker', 'unlogged-row-marker', 'unlogged-proof-marker',
                       env['AGENT_SERVICE_URL']):
            self.assertNotIn(marker, out.getvalue() + err.getvalue())
        return result, calls, github, out.getvalue() + err.getvalue()

    def test_capabilities_only_gets_strict_runtime_flags(self):
        result, calls, github, output = self.observe(['--read-capabilities'])
        self.assertEqual(result, 0)
        self.assertEqual([path for _, path, _ in calls], ['/api/v1/agent-merge-records/capabilities'])
        self.assertEqual(calls[0][2], 'Bearer unlogged-token-marker')
        github.get.assert_called_once_with('/repos/JiRaska/open-bank-oss')
        self.assertEqual(output, 'record-agent-merge: read-only verification passed\n')

    def test_capability_values_fail_closed_without_writes(self):
        invalid = [[], {}, {'evidenceSchemaVersion': True, 'policyPrAuthorization': True},
                   {'evidenceSchemaVersion': 2.0, 'policyPrAuthorization': True},
                   {'evidenceSchemaVersion': '2', 'policyPrAuthorization': True},
                   {'evidenceSchemaVersion': 1, 'policyPrAuthorization': True},
                   {'evidenceSchemaVersion': 2},
                   {'evidenceSchemaVersion': 2, 'policyPrAuthorization': False},
                   {'evidenceSchemaVersion': 2, 'policyPrAuthorization': 1}]
        for value in invalid:
            with self.subTest(capability=value):
                result, calls, _, _ = self.observe(['--read-capabilities'], capability=value)
                self.assertEqual(result, 1)
                self.assertEqual(len(calls), 1)

    def test_existing_row_is_freshly_proved_and_independently_read(self):
        result, calls, github, _ = self.observe(['--pr', '7', '--read-existing'])
        self.assertEqual(result, 0)
        self.assertIn(mock.call('/repos/JiRaska/open-bank-oss/pulls/7'), github.get.call_args_list)
        self.assertEqual([path for _, path, _ in calls], [
            '/api/v1/agent-merge-records/capabilities', '/api/v1/agent-merge-records/7'])

    def test_policy_row_uses_existing_evidence_comparator(self):
        result, _, _, _ = self.observe(['--pr', '7', '--read-existing'], policy=True)
        self.assertEqual(result, 0)
        result, _, _, _ = self.observe(['--pr', '7', '--read-existing'], policy=True, bad_row=True)
        self.assertEqual(result, 1)

    def test_missing_malformed_or_mismatched_row_never_repairs_it(self):
        for options in ({'row_status': 404}, {'bad_row': True}, {'bad_json': True}, {'bad_json': 'row'}):
            with self.subTest(options=options):
                result, _, _, output = self.observe(['--pr', '7', '--read-existing'], **options)
                self.assertEqual(result, 1)
                self.assertEqual(output, 'record-agent-merge: read-only verification failed\n')

    def test_owner_guard_or_canonical_proof_failure_precedes_backend_read(self):
        for options in ({'actor': 'not-owner'}, {'merged': False},
                        {'policy': True, 'proof_error': True},
                        {'env_overrides': {'GITHUB_EVENT_NAME': 'pull_request'}},
                        {'env_overrides': {'GITHUB_REF': 'refs/heads/topic'}},
                        {'env_overrides': {'GITHUB_RUN_ATTEMPT': '2'}},
                        {'env_overrides': {'GITHUB_TRIGGERING_ACTOR': 'not-owner'}},
                        {'env_overrides': {'GITHUB_ACTOR_ID': '2'}}):
            with self.subTest(options=options):
                result, calls, _, _ = self.observe(['--pr', '7', '--read-existing'], **options)
                self.assertEqual(result, 1)
                self.assertEqual(calls, [])

    def test_capabilities_owner_guard_and_row_capability_guard_are_not_bypassed(self):
        result, calls, _, _ = self.observe(['--read-capabilities'], actor='not-owner')
        self.assertEqual((result, calls), (1, []))
        result, calls, _, _ = self.observe(['--pr', '7', '--read-existing'],
                                         capability={'evidenceSchemaVersion': 2,
                                                     'policyPrAuthorization': False})
        self.assertEqual(result, 1)
        self.assertEqual([path for _, path, _ in calls], ['/api/v1/agent-merge-records/capabilities'])

    def test_invalid_read_arguments_cannot_fall_through_to_record(self):
        for flags in (['--read-existing'], ['--read-existing', '--pr', '7', '--verify-record'],
                      ['--read-capabilities', '--pr', '7'],
                      ['--read-capabilities', '--event', 'unread-event.json']):
            with self.subTest(flags=flags):
                result, calls, _, _ = self.observe(flags)
                self.assertEqual(result, 1)
                self.assertEqual(calls, [])

    def test_read_and_dry_run_operations_are_mutually_exclusive(self):
        with mock.patch.object(recorder, 'post_record') as post, \
             contextlib.redirect_stderr(io.StringIO()):
            for flags in (['--read-capabilities', '--dry-run'],
                          ['--read-existing', '--read-capabilities']):
                with self.subTest(flags=flags), self.assertRaises(SystemExit) as error:
                    recorder.main(flags)
                self.assertEqual(error.exception.code, 2)
            post.assert_not_called()


class WorkflowWiringTest(unittest.TestCase):
    def test_workflow_is_thin_and_pins_actions_by_sha(self):
        workflow = yaml.safe_load(
            (Path(__file__).parents[1] / 'workflows/record-agent-merge.yml').read_text())
        self.assertEqual(workflow['permissions'], {
            'contents': 'read', 'pull-requests': 'read', 'actions': 'read', 'statuses': 'read'})
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
        record_step = next(step for step in record['steps'] if '--verify-record' in step.get('run', ''))
        self.assertIn('record-agent-merge.py --verify-record --token-file', record_step['run'])
        self.assertIn('--pr "$pr" --verify-record', record_step['run'])
        self.assertEqual(checkout['with']['ref'], 'main')
        self.assertFalse(checkout['with']['persist-credentials'])
        self.assertIn("github.actor == github.repository_owner", record['if'])
        self.assertIn("github.triggering_actor == github.repository_owner", record['if'])
        self.assertIn("github.run_attempt == 1", record['if'])
        self.assertIn("github.ref == 'refs/heads/main'", record['if'])
        self.assertIn('prs', events['workflow_dispatch']['inputs'])
        self.assertEqual(events['workflow_dispatch']['inputs']['operation']['default'], 'record')
        self.assertEqual(events['workflow_dispatch']['inputs']['operation']['options'],
                         ['record', 'capabilities', 'verify-existing'])
        cleanup = record['steps'][-1]
        self.assertEqual(cleanup['if'], 'always()')
        self.assertIn('rm -f -- "$TOKEN_FILE"', cleanup['run'])
        env = workflow['env']
        self.assertIn('vars.AGENT_SERVICE_URL', env['AGENT_SERVICE_URL'])
        self.assertIn('vars.AGENT_MERGE_RECORDER_ISSUER', env['AGENT_MERGE_RECORDER_ISSUER'])
        for key in ('AGENT_MERGE_RECORDER_CLIENT_ID', 'AGENT_MERGE_RECORDER_CLIENT_SECRET'):
            self.assertIn('secrets.' + key, record['env'][key])
            self.assertNotIn(key, env)
            for name, job in workflow['jobs'].items():
                if name not in ('record', 'observe'):
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
        failure_script = raise_issue['steps'][0]['run']
        self.assertIn('gh issue create', failure_script)
        self.assertEqual(raise_issue['env']['GH_REPO'], '${{ github.repository }}')
        self.assertIn('gh issue comment', failure_script)
        self.assertNotIn('close-issue', workflow['jobs'])
        self.assertNotIn('gh issue close', yaml.safe_dump(workflow['jobs']))
        self.assertIn('backfilled and its merge record verified', failure_script)
        self.assertEqual(raise_issue['if'], "${{ failure() && (inputs.operation || 'record') == 'record' }}")

    def test_actual_workflow_shell_routes_reads_without_write_commands(self):
        workflow = yaml.safe_load((Path(__file__).parents[1] / 'workflows/record-agent-merge.yml').read_text())
        worker = yaml.safe_load((Path(__file__).parents[1] / 'workflows/_read-agent-merge.yml').read_text())
        record_script = next(step['run'] for step in workflow['jobs']['record']['steps']
                             if '--verify-record' in step.get('run', ''))
        read_script = next(step['run'] for step in worker['jobs']['observe']['steps']
                           if '--read-capabilities' in step.get('run', ''))
        with tempfile.TemporaryDirectory() as folder:
            stub = Path(folder) / 'python3'
            stub.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\nexit "${TEST_EXIT:-0}"\n')
            stub.chmod(0o755)
            for operation, prs, expected in [('capabilities', '', '--read-capabilities'),
                                              ('verify-existing', '7,8', '--read-existing'),
                                              ('record', '7', '--verify-record')]:
                for exit_code in (0, 1):
                    with self.subTest(operation=operation, exit_code=exit_code):
                        calls = Path(folder) / 'calls'
                        calls.write_text('')
                        env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                                   GITHUB_EVENT_NAME='workflow_dispatch', OPERATION=operation,
                                   BACKFILL_PRS=prs, READ_PRS=prs, TOKEN_FILE='test-header', CALLS=str(calls),
                                   TEST_EXIT=str(exit_code))
                        script = record_script if operation == 'record' else read_script
                        result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True)
                        self.assertEqual(result.returncode, exit_code, result.stderr)
                        commands = calls.read_text().splitlines()
                        self.assertTrue(commands)
                        self.assertTrue(all(expected in command for command in commands))
                        if operation != 'record':
                            self.assertNotIn('--verify-record', calls.read_text())
                        self.assertEqual(len(commands), 2 if operation == 'verify-existing' and not exit_code else 1)


class NativeReadRoutingTest(unittest.TestCase):
    def setUp(self):
        folder = Path(__file__).parents[1] / 'workflows'
        self.caller = yaml.safe_load((folder / 'record-agent-merge.yml').read_text())
        self.worker = yaml.safe_load((folder / '_read-agent-merge.yml').read_text())

    @staticmethod
    def allowed(expression, context):
        # Evaluate only this guard's literals/boolean comparisons, not arbitrary Python.
        text = expression.removeprefix('${{').removesuffix('}}').strip()
        text = re.sub(r'\b(?:github|inputs)\.[A-Za-z_][A-Za-z_.]*',
                      lambda match: repr(context.get(match.group(), '')), text)
        text = text.replace('&&', ' and ').replace('||', ' or ')
        text = re.sub(r'\btrue\b', 'True', text)
        text = re.sub(r'\bfalse\b', 'False', text)
        text = ' '.join(text.split())
        tree = ast.parse(text, mode='eval')
        permitted = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare,
                     ast.Eq, ast.NotEq, ast.Constant)
        if any(not isinstance(node, permitted) for node in ast.walk(tree)):
            raise AssertionError('unexpected guard syntax')
        return bool(eval(compile(tree, '<workflow guard>', 'eval'), {'__builtins__': {}}, {}))

    def context(self, **overrides):
        context = {'github.repository': 'JiRaska/open-bank-oss',
                   'github.repository_owner': 'JiRaska', 'github.actor': 'JiRaska',
                   'github.triggering_actor': 'JiRaska', 'github.run_attempt': 1,
                   'github.event_name': 'workflow_dispatch', 'github.ref': 'refs/heads/main',
                   'github.workflow_ref': 'JiRaska/open-bank-oss/.github/workflows/record-agent-merge.yml@refs/heads/main',
                   'inputs.operation': 'capabilities', 'github.event.pull_request.merged': False}
        context.update(overrides)
        return context

    def test_native_secrets_are_the_only_worker_transport_channel(self):
        call = self.caller['jobs']['observe']
        self.assertEqual(call['uses'], './.github/workflows/_read-agent-merge.yml')
        self.assertNotIn('runs-on', call)
        self.assertNotIn('steps', call)
        self.assertEqual(set(call['with']), {'operation', 'prs'})
        self.assertEqual(call['secrets'], {
            'agent_service_url': '${{ vars.AGENT_SERVICE_URL }}',
            'recorder_issuer': '${{ vars.AGENT_MERGE_RECORDER_ISSUER }}',
            'recorder_client_id': '${{ secrets.AGENT_MERGE_RECORDER_CLIENT_ID }}',
            'recorder_client_secret': '${{ secrets.AGENT_MERGE_RECORDER_CLIENT_SECRET }}'})
        events = self.worker.get('on', self.worker.get(True))
        self.assertEqual(set(events), {'workflow_call'})
        self.assertEqual(set(events['workflow_call']['secrets']), set(call['secrets']))
        self.assertTrue(all(value['required'] for value in events['workflow_call']['secrets'].values()))
        self.assertEqual(self.worker['permissions'], self.caller['permissions'])
        self.assertNotIn('concurrency', self.worker)  # cannot cancel its caller's run group
        self.assertEqual(set(self.worker['jobs']), {'observe'})
        job = self.worker['jobs']['observe']
        for name, secret in [('AGENT_SERVICE_URL', 'agent_service_url'),
                             ('AGENT_MERGE_RECORDER_ISSUER', 'recorder_issuer'),
                             ('AGENT_MERGE_RECORDER_CLIENT_ID', 'recorder_client_id'),
                             ('AGENT_MERGE_RECORDER_CLIENT_SECRET', 'recorder_client_secret')]:
            self.assertEqual(job['env'][name], '${{ secrets.' + secret + ' }}')
        checkout = job['steps'][0]
        self.assertEqual(checkout['with']['repository'], 'JiRaska/open-bank-oss')
        self.assertEqual(checkout['with']['ref'], 'main')
        self.assertFalse(checkout['with']['persist-credentials'])
        self.assertRegex(checkout['uses'], r'^actions/checkout@[0-9a-f]{40}$')
        scripts = '\n'.join(step.get('run', '') for step in job['steps'])
        self.assertNotIn('--verify-record', scripts)
        self.assertNotIn('gh issue', scripts)
        self.assertIn('grant_type=client_credentials', scripts)  # expected OAuth POST only
        self.assertEqual(job['steps'][-1]['if'], 'always()')

    def test_actual_invocation_guards_reject_foreign_callers_and_write_fallthrough(self):
        guard = self.worker['jobs']['observe']['if']
        self.assertTrue(self.allowed(guard, self.context()))
        invalid = {'github.repository': 'other/open-bank-oss',
                   'github.workflow_ref': 'JiRaska/open-bank-oss/.github/workflows/other.yml@refs/heads/main',
                   'github.event_name': 'pull_request_target', 'github.ref': 'refs/heads/topic',
                   'github.actor': 'other', 'github.triggering_actor': 'other', 'github.run_attempt': 2}
        for name, value in invalid.items():
            with self.subTest(context=name):
                self.assertFalse(self.allowed(guard, self.context(**{name: value})))
        for operation in ['capabilities', 'verify-existing']:
            context = self.context(**{'inputs.operation': operation})
            self.assertTrue(self.allowed(self.caller['jobs']['observe']['if'], context))
            self.assertFalse(self.allowed(self.caller['jobs']['record']['if'], context))
            self.assertFalse(self.allowed(self.caller['jobs']['raise-issue']['if'].replace('failure()', 'true'), context))
        context = self.context(**{'inputs.operation': 'record'})
        self.assertTrue(self.allowed(self.caller['jobs']['record']['if'], context))
        self.assertFalse(self.allowed(self.caller['jobs']['observe']['if'], context))

    def test_worker_clears_runner_proposal_override(self):
        inherited = {'AGENT_MERGE_PROPOSAL_ID': '11111111-1111-4111-8111-111111111111'}
        worker_env = self.worker['jobs']['observe']['env']
        if 'AGENT_MERGE_PROPOSAL_ID' in worker_env:
            inherited['AGENT_MERGE_PROPOSAL_ID'] = worker_env['AGENT_MERGE_PROPOSAL_ID']
        with mock.patch.dict(os.environ, inherited):
            self.assertEqual(recorder.extract_proposal_id({
                'body': 'Proposal-Id: 22222222-2222-4222-8222-222222222222'}),
                '22222222-2222-4222-8222-222222222222')
        self.assertEqual(worker_env.get('AGENT_MERGE_PROPOSAL_ID'), '')

    def test_worker_request_validation_fails_before_auth_for_non_read_inputs(self):
        step = self.worker['jobs']['observe']['steps'][1]
        self.assertEqual(step['name'], 'Validate bounded read request')
        for operation, prs, valid in [('capabilities', '', True), ('verify-existing', '7,8', True),
                                      ('record', '7', False), ('capabilities', '7', False),
                                      ('verify-existing', '', False), ('verify-existing', '7,0', False),
                                      ('verify-existing', ','.join(['7'] * 101), False)]:
            with self.subTest(operation=operation, prs=prs):
                env = dict(os.environ, OPERATION=operation, READ_PRS=prs, AGENT_SERVICE_URL='test-url',
                           AGENT_MERGE_RECORDER_ISSUER='test-issuer', AGENT_MERGE_RECORDER_CLIENT_ID='test-client',
                           AGENT_MERGE_RECORDER_CLIENT_SECRET='test-secret')
                result = subprocess.run(['bash', '-c', step['run']], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, valid, result.stderr)
                self.assertEqual(result.stdout, '')



if __name__ == '__main__':
    # SUBJECTS= for run-gates.py min_subjects floor: report the ACTUAL loaded test
    # count so deleting cases drops the floor, not just the coverage.
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules['__main__'])
    print(f'SUBJECTS={suite.countTestCases()}')
    unittest.main()
