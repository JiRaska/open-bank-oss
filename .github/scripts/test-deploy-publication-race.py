#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Real git histories and the actual workflow publication block; no remote writes."""
import importlib.util
import errno
import json
import os
import shutil
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


def _seed_git(root, *args):
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    result = subprocess.run(['git', *args], cwd=root, env=env,
                            capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError(f'fixture git {args!r} failed: {result.stderr or result.stdout}')
    return result.stdout.strip()


def _seed_commit(root):
    paths = _seed_git(root, 'ls-files', '--others', '--modified', '--exclude-standard').splitlines()
    _seed_git(root, 'add', '--', *paths)
    _seed_git(root, 'commit', '-qm', 'fixture')
    return _seed_git(root, 'rev-parse', 'HEAD')


def _new_seed(test_class, *init_args):
    temporary = tempfile.TemporaryDirectory()
    # unittest runs this even if setUpClass fails before any test starts.
    test_class.addClassCleanup(cleanup_temporary_directory, temporary)
    root = Path(temporary.name)
    _seed_git(root, 'init', *init_args)
    _seed_git(root, 'config', 'user.email', 'test@example.com')
    _seed_git(root, 'config', 'user.name', 'test')
    _seed_git(root, 'config', 'commit.gpgsign', 'false')
    return root


def _copy_seed(source, destination):
    # Copy every file independently: no hardlinks, alternates or mutable shared Git state.
    shutil.copytree(source, destination)


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


class PushPreservesNewerPinTest(unittest.TestCase):
    """A push-event deploy never moves a pin BACKWARD: if main already carries a descendant of this
    run's source for a service, that pin is preserved (union of newer pins, the no-downgrade CI/CD invariant (#14702)).

    Measured on #14579: a deploy PR for an older source rewrote 22 newer pins. These cases were
    written before the rule and FAIL on the old `decision()`, which publishes every push."""

    @classmethod
    def setUpClass(cls):
        cls._seed_path = _new_seed(cls, '-q', '-b', 'main')
        root = cls._seed_path
        (root / 'f.txt').write_text('1\n'); c1 = _seed_commit(root)
        (root / 'f.txt').write_text('2\n'); c2 = _seed_commit(root)
        (root / 'f.txt').write_text('3\n'); c3 = _seed_commit(root)
        _seed_git(root, 'checkout', '-q', '-b', 'side', c1)
        (root / 'g.txt').write_text('s\n'); side = _seed_commit(root)
        _seed_git(root, 'checkout', '-q', 'main')
        cls._seed_values = (c1, c2, c3, side)

    def setUp(self):
        # Gate runners provide a scratch index; every copied fixture needs its own.
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        isolated = patch.dict(os.environ, clean_env, clear=True)
        isolated.start()
        self.addCleanup(isolated.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        self.addCleanup(cleanup_temporary_directory, self.tmp)
        self.addCleanup(os.chdir, self.previous)
        repository = Path(self.tmp.name) / 'repo'
        _copy_seed(type(self)._seed_path, repository)
        os.chdir(repository)
        self.c1, self.c2, self.c3, self.side = type(self)._seed_values

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

    def decide(self, current, source, initial=None):
        tag = lambda sha: 'sandbox-' + sha[:8]
        return publication.decision('push', '', tag(initial or self.c1), tag(current), tag(source), source)

    def test_push_preserves_a_pin_main_already_moved_past_this_source(self):
        # Built c2; main already pins c3 (a descendant): rewriting would roll the service back.
        self.assertEqual('preserve', self.decide(current=self.c3, source=self.c2))

    def test_push_publishes_when_this_source_is_newer_than_main_pin(self):
        self.assertEqual('publish', self.decide(current=self.c2, source=self.c3))

    def test_push_publishes_when_main_pin_is_unchanged_since_source(self):
        self.assertEqual('publish', self.decide(current=self.c1, source=self.c2, initial=self.c1))

    def test_push_publishes_a_same_source_refresh(self):
        self.assertEqual('publish', self.decide(current=self.c2, source=self.c2))

    def test_push_fails_closed_on_a_diverged_main_pin(self):
        with self.assertRaises(ValueError):
            self.decide(current=self.side, source=self.c2)

    def test_push_fails_closed_on_an_unresolvable_main_pin(self):
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            publication.decision('push', '', 'sandbox-' + self.c1[:8], 'sandbox-deadbeef', 'sandbox-' + self.c2[:8], self.c2)


class PublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._seed_path = _new_seed(cls, '-q')
        root = cls._seed_path
        manifest = 'openbank-infra/gitops/components/example/service.yaml'
        path = root / manifest
        path.parent.mkdir(parents=True)
        path.write_text('image: registry.example/openbank-example:sandbox-11111111\n')
        (root / 'service').mkdir()
        (root / 'service/version.txt').write_text('1.0\n')
        base = _seed_commit(root)
        path.write_text(f'image: registry.example/openbank-example:sandbox-{base[:8]}\n')
        tip = _seed_commit(root)
        cls._seed_values = (manifest, base, tip)

    def setUp(self):
        # Gate runners provide a scratch index; every copied fixture needs its own.
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        isolated = patch.dict(os.environ, clean_env, clear=True)
        isolated.start()
        self.addCleanup(isolated.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        self.addCleanup(cleanup_temporary_directory, self.tmp)
        self.addCleanup(os.chdir, self.previous)
        repository = Path(self.tmp.name) / 'repo'
        _copy_seed(type(self)._seed_path, repository)
        os.chdir(repository)
        self.manifest, self.base, self.tip = type(self)._seed_values

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


class FixtureSeedTests(unittest.TestCase):
    def test_copies_do_not_share_objects_refs_index_or_worktree(self):
        seed = _new_seed(type(self), '-q', '-b', 'main')
        (seed / 'f').write_text('base\n')
        base = _seed_commit(seed)
        blob = _seed_git(seed, 'rev-parse', 'HEAD:f')
        object_path = Path('.git/objects') / blob[:2] / blob[2:]
        original_object = (seed / object_path).read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            first, peer = Path(directory) / 'first', Path(directory) / 'peer'
            for destination in (first, peer):
                _copy_seed(seed, destination)

            def state(root):
                return ((root / '.git/index').read_bytes(), (root / 'f').read_bytes(),
                        _seed_git(root, 'for-each-ref', '--format=%(refname) %(objectname)'))

            before_seed, before_peer = state(seed), state(peer)
            (first / 'f').write_text('first only\n')
            changed = _seed_commit(first)
            _seed_git(first, 'reset', '--hard', base)
            new_object = Path('.git/objects') / changed[:2] / changed[2:]
            self.assertFalse((seed / new_object).exists())
            self.assertFalse((peer / new_object).exists())
            # This catches copy_function=os.link, including links to existing objects.
            (first / object_path).chmod(0o600)
            (first / object_path).write_bytes(b'corrupt only the disposable copy')
            self.assertEqual((seed / object_path).read_bytes(), original_object)
            self.assertEqual((peer / object_path).read_bytes(), original_object)
            self.assertEqual(state(seed), before_seed)
            self.assertEqual(state(peer), before_peer)
            for root in (seed, first, peer):
                self.assertFalse((root / '.git/objects/info/alternates').exists())

    def test_seed_copy_and_chdir_failures_restore_owned_resources(self):
        previous = Path.cwd()
        real_chdir = os.chdir
        for mode in ('seed', 'copy', 'chdir'):
            with self.subTest(mode=mode):
                paths, ran = [], []

                class BrokenPublication(PublicationTest):
                    def test_body(self):
                        ran.append(True)

                def fail_seed(root, *args):
                    paths.append(Path(root))
                    raise OSError('injected seed initialization failure')

                def fail_copy(source, destination):
                    paths.extend((Path(source), Path(destination).parent))
                    Path(destination).mkdir()
                    (Path(destination) / 'partial').write_text('partial copy')
                    raise OSError('injected partial copy failure')

                def fail_chdir(path):
                    if Path(path) != previous:
                        paths.extend((BrokenPublication._seed_path, Path(path).parent))
                        raise OSError('injected chdir failure')
                    return real_chdir(path)

                replacements = ({'_seed_git': fail_seed} if mode == 'seed' else
                                {'_copy_seed': fail_copy} if mode == 'copy' else {})
                with patch.dict(globals(), replacements), \
                     patch.object(os, 'chdir', side_effect=fail_chdir if mode == 'chdir' else real_chdir):
                    result = unittest.TestResult()
                    unittest.TestSuite([BrokenPublication('test_body')]).run(result)
                self.assertEqual(len(result.errors), 1)
                self.assertFalse(ran)
                self.assertEqual(Path.cwd(), previous)
                self.assertTrue(paths)
                self.assertTrue(all(not path.exists() for path in paths))


if __name__ == '__main__':
    unittest.main()
