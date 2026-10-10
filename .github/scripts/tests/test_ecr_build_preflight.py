# SPDX-License-Identifier: Apache-2.0
"""Run actual workflow shell with fake registry/Docker commands; no cloud access."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[3]
STEPS = yaml.safe_load((ROOT / '.github/workflows/auto-deploy.yml').read_text())['jobs']['build-push']['steps']

class RegistryPreflightTest(unittest.TestCase):
    def test_registry_partition_and_failure_propagation(self):
        for services, blocked in [(['openbank-ready', 'openbank-blocked'], ['openbank-blocked']),
                                  (['openbank-blocked'], ['openbank-blocked']),
                                  (['openbank-ready', 'openbank-unobservable'], []), ([], [])]:
            with self.subTest(services=services), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                scripts = root / '.github/scripts'
                scripts.mkdir(parents=True)
                helper = scripts / 'ensure-ecr-repository.sh'
                helper.write_text('case "$1" in openbank-blocked) exit 1;; *) exit 0;; esac\n')
                native = scripts / 'verify-image-native-libs.py'
                native.write_text('raise SystemExit(0)\n')
                (root / '.github/workflows').mkdir()
                (root / '.github/workflows/Dockerfile.deploy').write_text('FROM scratch\n')
                binary = root / 'bin'
                binary.mkdir()
                docker = binary / 'docker'
                docker.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$DOCKER_CALLS"\nexit 0\n')
                docker.chmod(0o755)
                output = root / 'outputs'
                env = dict(os.environ, GITHUB_WORKSPACE=str(root), GITHUB_OUTPUT=str(output),
                           SERVICES_JSON=json.dumps(services), RUNNER_TEMP=str(root),
                           SELF_BUILT_SERVICES='', ECR_REGISTRY='registry.example.invalid',
                           PATH=str(binary)+os.pathsep+os.environ['PATH'], DOCKER_CALLS=str(root/'docker-calls'))
                registry = next(step['run'] for step in STEPS if step.get('id') == 'registry')
                result = subprocess.run(['bash', '-c', registry], cwd=root, env=env,
                                        text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)
                values = dict(line.split('=', 1) for line in output.read_text().splitlines())
                ready = [s for s in services if s not in blocked]
                self.assertEqual(json.loads(values['services']), ready)
                self.assertEqual(json.loads(values['failed']), blocked)
                for service in ready:
                    jar = root / service / 'build/quarkus-app/quarkus-run.jar'
                    jar.parent.mkdir(parents=True)
                    jar.touch()
                    (root / service / 'Dockerfile').write_text('EXPOSE 8080\n')
                push = next(step['run'] for step in STEPS if step.get('id') == 'push')
                push = push.replace('${{ steps.registry.outputs.services }}', values['services'])
                push = push.replace('${{ steps.registry.outputs.failed }}', values['failed'])
                push = push.replace('${{ steps.build.outputs.tag }}', 'sandbox-test')
                output.write_text('')
                result = subprocess.run(['bash', '-c', push], cwd=root, env=env,
                                        text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
                values = dict(line.split('=', 1) for line in output.read_text().splitlines())
                self.assertEqual(json.loads(values['pushed']), ready)
                self.assertEqual(json.loads(values['failed']), blocked)
                calls = (root / 'docker-calls').read_text()
                for service in blocked:
                    self.assertNotIn(service, calls)
                build = next(step['run'] for step in STEPS if step.get('id') == 'build')
                self.assertIn("SERVICES='${{ steps.registry.outputs.services }}'", build)

if __name__ == '__main__':
    unittest.main()
