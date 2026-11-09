#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise download recovery with a real archive and checksum, without network or a scanner."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install-trivy-release-fallback.sh').resolve()


class TrivyFallbackTest(unittest.TestCase):
    def run_install(self, arch='aarch64', mode='ok', failures=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binaries = root / 'bin'
            binaries.mkdir()
            (root / 'home').mkdir()
            marker = root / 'executed'
            payload = f'#!/bin/sh\ntouch "{marker}"\necho "Version: 0.72.0"\n'.encode()
            for suffix in ['ARM64', '64bit']:
                archive = root / f'trivy_0.72.0_Linux-{suffix}.tar.gz'
                with tarfile.open(archive, 'w:gz') as tar:
                    entry = tarfile.TarInfo('trivy')
                    entry.mode = 0o755
                    entry.size = len(payload)
                    tar.addfile(entry, io.BytesIO(payload))
                digest = hashlib.sha256(archive.read_bytes()).hexdigest()
                if mode == 'corrupt':
                    digest = '0' * 64
                with (root / 'trivy_0.72.0_checksums.txt').open('a') as manifest:
                    if mode != 'missing':
                        manifest.write(f'{digest}  {archive.name}\n')
                    if mode == 'duplicate':
                        manifest.write(f'{digest}  {archive.name}\n')
            (binaries / 'uname').write_text(f'#!/bin/sh\nif [ "$1" = -s ]; then echo Linux; else echo {arch}; fi\n')
            # Only the transport is stubbed: archive extraction, sha256sum and installation are real.
            (binaries / 'gh').write_text('''#!/usr/bin/env python3
import os, pathlib, shutil, sys
root = pathlib.Path(os.environ['FIXTURE'])
count = root / 'attempts'
n = int(count.read_text()) + 1 if count.exists() else 1
count.write_text(str(n))
(root / 'arguments').write_text(' '.join(sys.argv[1:]))
if n <= int(os.environ['FAILURES']): sys.exit(1)
args = sys.argv[1:]
destination = pathlib.Path(args[args.index('--dir') + 1])
for i, arg in enumerate(args):
    if arg == '--pattern': shutil.copy(root / args[i + 1], destination / args[i + 1])
''')
            (binaries / 'sleep').write_text('#!/bin/sh\nexit 0\n')
            for executable in binaries.iterdir():
                executable.chmod(0o755)
            env = dict(os.environ, PATH=f'{binaries}:{os.environ["PATH"]}', HOME=str(root / 'home'),
                       RUNNER_TEMP=str(root), GITHUB_PATH=str(root / 'path'), FIXTURE=str(root), FAILURES=str(failures))
            result = subprocess.run(['bash', str(SCRIPT)], env=env, text=True, capture_output=True, check=False)
            return result, marker.exists(), (root / 'arguments').read_text() if (root / 'arguments').exists() else '', int((root / 'attempts').read_text()) if (root / 'attempts').exists() else 0

    def test_verified_arm64_release_is_installed(self):
        result, executed, args, attempts = self.run_install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(executed)
        self.assertIn('trivy_0.72.0_Linux-ARM64.tar.gz', args)
        self.assertIn('--repo github.com/aquasecurity/trivy', args)
        self.assertEqual(attempts, 1)

    def test_verified_amd64_release_is_installed(self):
        result, executed, args, _ = self.run_install(arch='x86_64')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(executed)
        self.assertIn('trivy_0.72.0_Linux-64bit.tar.gz', args)

    def test_corrupt_missing_and_duplicate_checksums_never_execute(self):
        for mode in ['corrupt', 'missing', 'duplicate']:
            with self.subTest(mode=mode):
                result, executed, _, _ = self.run_install(mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(executed)

    def test_transient_download_recovers(self):
        result, executed, _, attempts = self.run_install(failures=1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(executed)
        self.assertEqual(attempts, 2)

    def test_exhausted_download_fails_closed(self):
        result, executed, _, attempts = self.run_install(failures=3)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(executed)
        self.assertEqual(attempts, 3)
        self.assertIn('scans have NOT run', result.stderr)

    def test_unsupported_architecture_never_downloads(self):
        result, executed, _, attempts = self.run_install(arch='sparc')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(executed)
        self.assertEqual(attempts, 0)


if __name__ == '__main__':
    unittest.main()
