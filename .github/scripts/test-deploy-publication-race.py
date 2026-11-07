#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Real git histories and the actual workflow publication block; no remote writes."""
import importlib.util
import errno
import json
import os
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('publication', SCRIPTS / 'deploy-publication-race.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)
WORKFLOW = (SCRIPTS.parent / 'workflows/auto-deploy.yml').read_text()


def cleanup_temporary_directory(temporary_directory):
    """Retry a brief ENOTEMPTY race, but fail if the fixture stays nonempty."""
    for attempt in range(5):
        try:
            temporary_directory.cleanup()
            return
        except OSError as error:
            if error.errno != errno.ENOTEMPTY or attempt == 4:
                raise
            time.sleep(0.1 * (attempt + 1))


class TemporaryDirectoryCleanupTests(unittest.TestCase):
    def test_transient_enotempty_is_retried(self):
        temporary_directory = Mock()
        temporary_directory.cleanup.side_effect = [OSError(errno.ENOTEMPTY, 'Directory not empty'), None]
        with patch('time.sleep') as sleep:
            cleanup_temporary_directory(temporary_directory)
        self.assertEqual(temporary_directory.cleanup.call_count, 2)
        sleep.assert_called_once_with(0.1)

    def test_persistent_enotempty_fails_after_bounded_retries(self):
        temporary_directory = Mock()
        temporary_directory.cleanup.side_effect = OSError(errno.ENOTEMPTY, 'Directory not empty')
        with patch('time.sleep') as sleep:
            with self.assertRaises(OSError) as error:
                cleanup_temporary_directory(temporary_directory)
        self.assertEqual(error.exception.errno, errno.ENOTEMPTY)
        self.assertEqual(temporary_directory.cleanup.call_count, 5)
        self.assertEqual(sleep.call_count, 4)
        for call, expected in zip(sleep.call_args_list, [0.1, 0.2, 0.3, 0.4], strict=True):
            self.assertAlmostEqual(call.args[0], expected)

    def test_other_cleanup_errors_propagate_without_retry(self):
        temporary_directory = Mock()
        failure = OSError(errno.EACCES, 'Permission denied')
        temporary_directory.cleanup.side_effect = failure
        with patch('time.sleep') as sleep:
            with self.assertRaises(OSError) as error:
                cleanup_temporary_directory(temporary_directory)
        self.assertIs(error.exception, failure)
        temporary_directory.cleanup.assert_called_once_with()
        sleep.assert_not_called()


class PublicationTest(unittest.TestCase):
    def setUp(self):
        # Gate runners provide a scratch index for the checkout; fixtures need their own.
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        isolated = patch.dict(os.environ, clean_env, clear=True)
        isolated.start()
        self.addCleanup(isolated.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.tmp.name)
        self.addCleanup(cleanup_temporary_directory, self.tmp)
        self.addCleanup(os.chdir, self.previous)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.com')
        self.git('config', 'user.name', 'test')
        self.git('config', 'commit.gpgsign', 'false')
        self.manifest = 'openbank-infra/gitops/components/example/service.yaml'
        self.write(self.manifest, 'image: registry.example/openbank-example:sandbox-11111111\n')
        self.write('service/version.txt', '1.0\n')
        self.base = self.commit()
        self.write(self.manifest, f'image: registry.example/openbank-example:sandbox-{self.base[:8]}\n')
        self.tip = self.commit()

    def git(self, *args):
        result = subprocess.run(['git', *args], capture_output=True, text=True, check=False)
        if result.returncode:
            raise RuntimeError(f'fixture git {args!r} failed: {result.stderr or result.stdout}')
        return result.stdout.strip()

    def write(self, path, value):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(value)

    def commit(self):
        paths = self.git('ls-files', '--others', '--modified', '--exclude-standard').splitlines()
        self.git('add', '--', *paths)
        self.git('commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD')

    def decide(self, **kwargs):
        args = {'event': 'schedule', 'reconcile': '', 'initial': 'sandbox-11111111',
                    'current': 'sandbox-' + self.base[:8], 'candidate': 'sandbox-' + self.tip[:8], 'source': self.tip}
        args.update(kwargs)
        return publication.decision(**args)

    def test_concurrent_pin_only_advance_preserved(self):
        self.assertEqual(self.decide(), 'preserve')
        self.assertEqual(self.decide(event='workflow_dispatch', reconcile='true'), 'preserve')

    def test_all_changed_inputs_publish(self):
        for path in ['service/src/main/App.kt', 'service/version.txt', 'gradle/libs.versions.toml',
                     'service/Dockerfile', '.github/workflows/auto-deploy.yml', 'unknown/input']:
            with self.subTest(path=path):
                self.write(path, 'changed\n')
                tip = self.commit()
                self.assertEqual(self.decide(candidate='sandbox-' + tip[:8], source=tip), 'publish')
                self.git('reset', '--hard', self.tip)

    def test_other_manifest_changes_publish(self):
        self.write(self.manifest, 'image: registry.example/openbank-example:sandbox-22222222\nreplicas: 2\n')
        tip = self.commit()
        self.assertEqual(self.decide(candidate='sandbox-' + tip[:8], source=tip), 'publish')

    def test_manual_and_provenance_refresh_publish(self):
        self.assertEqual(self.decide(event='workflow_dispatch'), 'publish')
        self.assertEqual(self.decide(event='push'), 'publish')
        self.assertEqual(self.decide(candidate='sandbox-' + self.tip[:8] + '-run123'), 'publish')
        self.assertEqual(self.decide(current='sandbox-' + self.base[:8] + '-run123'), 'preserve')

    def test_no_concurrent_advance_publish(self):
        self.assertEqual(self.decide(initial='sandbox-' + self.base[:8]), 'publish')

    def test_unknown_or_malformed_evidence_fails_closed(self):
        for change in [{'current': 'sandbox-pending'}, {'current': 'sandbox-ffffffff'},
                       {'candidate': 'sandbox-nope'}, {'source': 'ffffffff'}, {'source': self.base}]:
            with self.subTest(change=change), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.decide(**change)

    def rewrite_workflow(self, source, tags=None, digests=None, event='schedule', reconcile=''):
        # Execute the actual workflow's only image writer; neither shell nor Python is transcribed.
        start = WORKFLOW.index('          python3 .github/scripts/rewrite-reconcile-image-pins.py')
        end = WORKFLOW.index('          while IFS=', start)
        block = WORKFLOW[start:end]
        for name in ('deploy-publication-race.py', 'rewrite-reconcile-image-pins.py'):
            target = Path('.github/scripts') / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((SCRIPTS / name).read_bytes())
        tags = tags or {'openbank-example': 'sandbox-' + source[:8]}
        if digests is None:
            digests = {service: 'sha256:' + 'c' * 64 for service in tags}
        env = dict(os.environ, SOURCE_COMMIT=source, GITHUB_SHA='f' * 40,
                   GITHUB_EVENT_NAME=event, INPUT_RECONCILE=reconcile,
                   ECR_REGISTRY='registry.example', SELECTED_TAGS=json.dumps(tags),
                   SELECTED_DIGESTS=json.dumps(digests))
        return subprocess.run(['/bin/bash', '-c', 'set -euo pipefail\n' + block],
                              env=env, capture_output=True, text=True, check=False)

    def test_workflow_initial_manifest_evidence_missing_aborts(self):
        for origin in ('missing', 'without-image', 'ambiguous', 'malformed'):
            with self.subTest(origin=origin):
                self.git('reset', '--hard', self.tip)
                if origin == 'missing':
                    self.git('rm', self.manifest)
                    self.git('commit', '-qm', 'missing original')
                    source = self.git('rev-parse', 'HEAD')
                else:
                    contents = {
                        'without-image': 'replicas: 1\n',
                        'ambiguous': 'image: registry.example/openbank-example:sandbox-11111111\n'
                                     'image: registry.example/openbank-example:sandbox-22222222\n',
                        'malformed': 'image: registry.example/openbank-example:sandbox-11111111@sha256:nope\n',
                    }[origin]
                    self.write(self.manifest, contents)
                    source = self.commit()
                live = 'image: registry.example/openbank-example:sandbox-' + self.base[:8] + '\n'
                self.write(self.manifest, live)
                result = self.rewrite_workflow(source)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual(Path(self.manifest).read_text(), live)

    def test_actual_workflow_block_preserves_concurrent_pin(self):
        for quote in ('', '"', "'"):
            with self.subTest(quote=quote):
                self.git('reset', '--hard', self.tip)
                def reference(tag, digest, quote=quote):
                    return f'image: {quote}registry.example/openbank-example:{tag}@sha256:{digest * 64}{quote} # pin\n'
                self.write(self.manifest, reference('sandbox-11111111', 'a'))
                previous = self.commit()
                self.write(self.manifest, reference('sandbox-' + previous[:8], 'b'))
                source = self.commit()
                live = reference('sandbox-' + previous[:8] + '-run123', 'd')
                self.write(self.manifest, live)
                self.assertEqual(self.decide(initial='sandbox-11111111', current='sandbox-' + previous[:8],
                                             candidate='sandbox-' + source[:8], source=source), 'preserve')
                result = self.rewrite_workflow(source)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(Path(self.manifest).read_text(), live)
                # A substantive input change must still publish the exact candidate digest.
                self.write('service/version.txt', '2.0\n')
                changed = self.commit()
                self.write(self.manifest, reference('sandbox-' + previous[:8], 'e'))
                result = self.rewrite_workflow(changed)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('sandbox-' + changed[:8] + '@sha256:' + 'c' * 64,
                              Path(self.manifest).read_text())

    def test_actual_writer_failure_does_not_partially_publish(self):
        first = 'openbank-infra/gitops/components/first/service.yaml'
        self.write(first, 'image: registry.example/openbank-first:sandbox-11111111\n')
        source = self.commit()
        tags = {'openbank-first': 'sandbox-' + source[:8], 'openbank-example': 'sandbox-' + source[:8]}
        originals = {path: Path(path).read_text() for path in (first, self.manifest)}
        for failure in ('invalid-digest', 'missing-digest', 'unproven-source', 'malformed-current'):
            with self.subTest(failure=failure):
                for path, contents in originals.items():
                    Path(path).write_text(contents)
                digests = {service: 'sha256:' + 'c' * 64 for service in tags}
                if failure == 'invalid-digest':
                    digests['openbank-example'] = 'sha256:invalid'
                elif failure == 'missing-digest':
                    del digests['openbank-example']
                elif failure == 'unproven-source':
                    Path(self.manifest).write_text('image: registry.example/openbank-example:sandbox-ffffffff\n')
                else:
                    Path(self.manifest).write_text(originals[self.manifest] +
                        'image: registry.example/openbank-example:sandbox-11111111@sha256:invalid\n')
                before = {path: Path(path).read_bytes() for path in originals}
                result = self.rewrite_workflow(source, tags, digests)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual({path: Path(path).read_bytes() for path in originals}, before)

    def test_manual_refresh_and_same_tag_digest_refresh_still_publish(self):
        for event, reconcile, run_suffix in (('push', '', ''), ('workflow_dispatch', '', ''),
                                            ('workflow_dispatch', 'true', '-run123'), ('schedule', '', '')):
            with self.subTest(event=event, reconcile=reconcile, suffix=run_suffix):
                self.git('reset', '--hard', self.tip)
                # The same tag is an intentional digest refresh, not a concurrent tag advance.
                candidate = 'sandbox-' + self.tip[:8] + run_suffix
                self.write(self.manifest, f'image: registry.example/openbank-example:{candidate}@sha256:{"a" * 64}\n')
                result = self.rewrite_workflow(self.tip, {'openbank-example': candidate},
                                               event=event, reconcile=reconcile)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('@sha256:' + 'c' * 64, Path(self.manifest).read_text())


if __name__ == '__main__':
    unittest.main()
