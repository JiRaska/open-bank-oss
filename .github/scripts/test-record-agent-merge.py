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


def pull(number=12747, branch='agent/adr-0031-phase3-gated-pr-path', author='JiRaska',
         merged_by='JiRaska', **extra):
    item = {
        'number': number,
        'html_url': f'https://github.com/JiRaska/open-bank-oss/pull/{number}',
        'title': 'feat(docs): ADR-0031 phase 3 evidence',
        'head': {'ref': branch},
        'user': {'login': author},
        'merged_by': {'login': merged_by},
        'merged_at': '2026-10-14T12:00:00Z',
        'body': '',
    }
    item.update(extra)
    return item


class DecideAgentIdTest(unittest.TestCase):
    def test_agent_prefix_maps_first_dash_segment(self):
        self.assertEqual(recorder.decide_agent_id('agent/adr-0031-phase3-gated-pr-path',
                                                  'JiRaska', AGENT_ACCOUNTS), 'adr')
        self.assertEqual(recorder.decide_agent_id('agent/docs-fleet-ci', 'JiRaska',
                                                  AGENT_ACCOUNTS), 'docs')

    def test_agent_prefix_without_dash_uses_whole_segment(self):
        self.assertEqual(recorder.decide_agent_id('agent/adr', 'JiRaska', AGENT_ACCOUNTS), 'adr')

    def test_codex_prefix_maps_to_constant(self):
        self.assertEqual(recorder.decide_agent_id('codex/docs-fleet-ci', 'JiRaska',
                                                  AGENT_ACCOUNTS), 'external-coding-agent')
        self.assertEqual(recorder.decide_agent_id('codex/anything-at-all', 'someone',
                                                  AGENT_ACCOUNTS), 'external-coding-agent')

    def test_declared_agent_author_on_prefixless_branch_maps_to_login(self):
        # The login is recorded verbatim, whatever spelling GitHub supplied — the
        # declaration list is normalised, the recorded identity is not.
        self.assertEqual(recorder.decide_agent_id('fix/human', 'openbank-agent-bot[bot]',
                                                  AGENT_ACCOUNTS), 'openbank-agent-bot[bot]')
        # The `app/...` spelling of the same declared account must also match (#6403 lesson).
        self.assertEqual(recorder.decide_agent_id('chore/x', 'app/openbank-agent-bot',
                                                  AGENT_ACCOUNTS), 'app/openbank-agent-bot')

    def test_non_agent_pr_is_noop(self):
        for branch, author in [('fix/human', 'JiRaska'), ('release-please--branches--main',
                               'release-please[bot]'), ('agent/', 'JiRaska')]:
            with self.subTest(branch=branch, author=author):
                self.assertIsNone(recorder.decide_agent_id(branch, author, AGENT_ACCOUNTS))


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

    def run_main(self, event, env=None, rules=None):
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        event_path = Path(tmpdir.name) / 'event.json'
        event_path.write_text(json.dumps(event))
        rules_path = Path(tmpdir.name) / 'rules.yaml'
        rules_path.write_text(rules if rules is not None else
                              'autonomous_agent_prs:\n  agent_accounts:\n'
                              '    - openbank-agent-bot[bot]\n')
        argv = [sys.executable, str(Path(__file__).with_name('record-agent-merge.py')),
                '--event', str(event_path), '--rules', str(rules_path)]
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

    def test_unreachable_endpoint_warns_and_exits_zero(self):
        # A port that is guaranteed closed: bind, learn the port, release it.
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        result = self.run_main(
            self.closed_event(),
            env={'AGENT_SERVICE_URL': f'http://127.0.0.1:{port}',
                 'AGENT_MERGE_RECORDER_TOKEN': 'token'})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('::warning::record-agent-merge', result.stdout)
        self.assertIn('NOT recorded', result.stdout)

    def test_missing_config_warns_and_exits_zero(self):
        for env in ({}, {'AGENT_SERVICE_URL': 'http://agent-service.platform.svc:8109'},
                    {'AGENT_MERGE_RECORDER_TOKEN': 'token'}):
            with self.subTest(env=env):
                result = self.run_main(self.closed_event(), env=env)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('::warning::record-agent-merge', result.stdout)

    def test_merged_by_absent_warns_and_exits_zero(self):
        result = self.run_main(self.closed_event(merged_by=None))
        # pull() always sets a dict; simulate the absent-identity shape directly.
        event = self.closed_event()
        event['pull_request']['merged_by'] = None
        result = self.run_main(event)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('::warning::', result.stdout)

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
            result = self.run_main(
                self.closed_event(),
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
        self.assertEqual(received['body']['agentId'], 'adr')
        self.assertEqual(received['body']['mergedBy'], 'JiRaska')
        self.assertEqual(received['body']['prNumber'], 12747)

    def test_non_2xx_response_warns_and_exits_zero(self):
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
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('::warning::record-agent-merge', result.stdout)
        self.assertIn('HTTP 401', result.stdout)


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
        self.assertIn('merged == true', workflow['jobs']['record']['if'])
        step = workflow['jobs']['record']['steps'][1]
        self.assertIn('record-agent-merge.py', step['run'])
        checkout = workflow['jobs']['record']['steps'][0]
        self.assertRegex(checkout['uses'], r'^actions/checkout@[0-9a-f]{40}$')
        env = workflow['jobs']['record']['env']
        self.assertIn('vars.AGENT_SERVICE_URL', env['AGENT_SERVICE_URL'])
        self.assertIn('secrets.AGENT_MERGE_RECORDER_TOKEN', env['AGENT_MERGE_RECORDER_TOKEN'])


if __name__ == '__main__':
    unittest.main()
