#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Regression proof for concurrent shallow fetches; no credentials or remote network."""
import os
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

import merge_train_carrier as carrier

SHA = 'a' * 40
LOCK_ERROR = "fatal: Unable to create '/checkout/.git/shallow.lock': File exists."


class FetchDeadlineTest(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.git = mock.patch.object(carrier, '_git', side_effect=lambda *args, **kw:
                                     subprocess.CompletedProcess(args, 0, 'true' if args[0] == 'rev-parse' and args[1] == '--is-shallow-repository' else SHA, ''))
        self.git.start()
        self.chain = mock.patch.object(carrier, '_chain_complete', return_value=False).start()
        mock.patch.object(carrier.time, 'monotonic', side_effect=lambda: self.now).start()
        mock.patch.object(carrier.time, 'sleep', side_effect=self.sleep).start()
        self.addCleanup(mock.patch.stopall)

    def sleep(self, seconds):
        self.now += seconds

    def test_lock_then_success_uses_same_sha_and_remaining_deadline(self):
        calls = []
        def fetch(args, **kw):
            calls.append((args, kw['timeout']))
            self.now += 0.05
            return subprocess.CompletedProcess(args, int(len(calls) == 1), '', LOCK_ERROR)
        carrier.deepen_for_chain('base', timeout=0.5, runner=fetch)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][0], ['git', 'fetch', '-q', '--no-tags', '--deepen=128', 'origin', SHA])
        self.assertEqual(calls[0][0], calls[1][0])
        self.assertLess(calls[1][1], calls[0][1])
        self.assertLessEqual(self.now, 100.5)

    def test_persistent_lock_exhausts_original_deadline(self):
        calls = []
        def fetch(args, **kw):
            calls.append(kw['timeout'])
            return subprocess.CompletedProcess(args, 128, '', LOCK_ERROR)
        with self.assertRaises(carrier.CarrierUnavailable):
            carrier.deepen_for_chain('base', timeout=0.25, runner=fetch)
        self.assertGreater(len(calls), 1)
        self.assertLessEqual(self.now, 100.25)
        self.assertTrue(all(0 < t <= 0.25 for t in calls))

    def test_other_git_lock_is_not_retried(self):
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 128, '', "fatal: Unable to create '.git/index.lock': File exists."))
        with self.assertRaises(carrier.CarrierUnavailable):
            carrier.deepen_for_chain('base', runner=runner)
        runner.assert_called_once()

    def test_other_fetch_failure_is_not_retried(self):
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 128, '', 'fatal: authentication failed'))
        with self.assertRaises(carrier.CarrierUnavailable):
            carrier.deepen_for_chain('base', runner=runner)
        runner.assert_called_once()

    def test_timeout_is_not_retried(self):
        runner = mock.Mock(side_effect=subprocess.TimeoutExpired('git', 0.25))
        with self.assertRaises(carrier.CarrierUnavailable):
            carrier.deepen_for_chain('base', timeout=0.25, runner=runner)
        runner.assert_called_once()

    def test_expired_deadline_never_fetches(self):
        runner = mock.Mock()
        with self.assertRaises(carrier.CarrierUnavailable):
            carrier.deepen_for_chain('base', timeout=0, runner=runner)
        runner.assert_not_called()

    def test_another_fetch_completed_chain_before_retry(self):
        self.chain.side_effect = [False, True]
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 128, '', LOCK_ERROR))
        carrier.deepen_for_chain('base', runner=runner)
        runner.assert_called_once()

    def test_already_complete_never_fetches(self):
        self.chain.return_value = True
        runner = mock.Mock()
        carrier.deepen_for_chain('base', runner=runner)
        runner.assert_not_called()


class RealShallowLockTest(unittest.TestCase):
    def test_real_git_lock_contention_recovers_without_removing_writer_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            origin, clone = root / 'origin', root / 'clone'
            # run-gates gives each gate a private index for the caller checkout, not this fixture.
            env = {k: v for k, v in os.environ.items() if k not in {
                'GIT_INDEX_FILE', 'GIT_DIR', 'GIT_WORK_TREE', 'GIT_COMMON_DIR',
                'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES',
            }}
            def git(*args, cwd=origin):
                return subprocess.run(['git', *args], cwd=cwd, text=True, capture_output=True, check=True, env=env).stdout.strip()
            origin.mkdir()
            git('init', '-q')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-q', '--allow-empty', '-m', 'base')
            base = git('rev-parse', 'HEAD')
            for n in (1, 2):
                git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-q', '--allow-empty', '-m', f'merge-train: member #{n}\n\nPR-Number: #{n}\nPR-Head: {SHA}')
            head = git('rev-parse', 'HEAD')
            git('clone', '-q', '--depth=1', origin.as_uri(), str(clone), cwd=root)
            # Keep base reachable but the carrier's first parent shallow, as in CI.
            git('fetch', '-q', '--depth=1', 'origin', base, cwd=clone)
            lock = clone / '.git' / 'shallow.lock'
            ready, contended = threading.Event(), threading.Event()
            writer_errors = []
            def writer():
                try:
                    lock.write_text('owned by another fetch\n')
                    ready.set()
                    if not contended.wait(5):
                        raise AssertionError('fetch never reached actual Git lock contention')
                    if lock.read_text() != 'owned by another fetch\n':
                        raise AssertionError('resolver modified the writer lock')
                    lock.unlink()
                except BaseException as error:
                    writer_errors.append(error)
            thread = threading.Thread(target=writer)
            thread.start()
            self.assertTrue(ready.wait(5))
            attempts = []
            def fetch(args, **kw):
                result = subprocess.run(args, env=env, **kw)
                attempts.append(result)
                if 'shallow.lock' in result.stderr and 'File exists' in result.stderr:
                    contended.set()
                return result
            try:
                with mock.patch.dict(os.environ, env, clear=True):
                    carrier.deepen_for_chain(base, head, str(clone), timeout=3, runner=fetch)
            finally:
                thread.join(6)
            self.assertFalse(thread.is_alive())
            self.assertEqual(writer_errors, [])
            self.assertTrue(contended.is_set())
            self.assertGreaterEqual(len(attempts), 2)
            with mock.patch.dict(os.environ, env, clear=True):
                self.assertEqual([n for n, _ in carrier.carrier_members(base, head, str(clone))], [1, 2])
            self.assertFalse(lock.exists())


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromModule(__import__(__name__))
    print(f'SUBJECTS={suite.countTestCases()}', flush=True)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
