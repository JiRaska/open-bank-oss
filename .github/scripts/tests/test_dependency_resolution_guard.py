# SPDX-License-Identifier: Apache-2.0
"""Exercise the real Gradle guard after setup-gradle enabled graph generation."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GUARD = ROOT / '.github/scripts/dependency-resolution-strict.init.gradle'


class ResolutionGuardTests(unittest.TestCase):
    def run_case(self, case):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            (fixture / 'settings.gradle').write_text("rootProject.name = 'resolution-guard-test'\n")
            build = "plugins { id 'java' }\nrepositories { maven { url = uri('repo') } }\n"
            if case == 'direct':
                build += "dependencies { implementation 'probe:missing:1.0' }\n"
            if case == 'transitive':
                package = fixture / 'repo/probe/parent/1.0'
                package.mkdir(parents=True)
                (package / 'parent-1.0.pom').write_text(
                    '<project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId>'
                    '<artifactId>parent</artifactId><version>1.0</version><dependencies>'
                    '<dependency><groupId>probe</groupId><artifactId>missing</artifactId>'
                    '<version>1.0</version></dependency></dependencies></project>')
                build += "dependencies { implementation 'probe:parent:1.0' }\n"
            (fixture / 'build.gradle').write_text(build)
            coverage = fixture / 'coverage'
            coverage.mkdir()
            env = dict(os.environ, GITHUB_DEPENDENCY_GRAPH_WORKSPACE=str(fixture),
                       DEPENDENCY_GRAPH_REPORT_DIR=str(fixture / 'reports'),
                       DEPENDENCY_GRAPH_COVERAGE_DIR=str(coverage))
            result = subprocess.run(
                [str(ROOT / 'gradlew'), '-p', str(fixture), '--init-script', str(GUARD),
                 '--no-daemon', '--no-configuration-cache', '--max-workers=1',
                 '-Dorg.gradle.jvmargs=-Xmx512m',
                 ':ForceDependencyResolutionPlugin_resolveAllDependencies'],
                env=env, capture_output=True, text=True, timeout=120, check=False)
            receipts = [json.loads(p.read_text()) for p in coverage.glob('*.json')]
            return result, receipts

    def test_valid_graph_has_resolution_receipt(self):
        result, receipts = self.run_case('valid')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(receipts), 1)
        self.assertEqual(receipts[0]['project'], ':')
        self.assertEqual(receipts[0]['sha'], os.environ['GITHUB_DEPENDENCY_GRAPH_SHA'])
        self.assertIn('runtimeClasspath', receipts[0]['configurations'])

    def test_missing_direct_or_transitive_dependency_fails_without_receipt(self):
        for case in ('direct', 'transitive'):
            with self.subTest(case=case):
                result, receipts = self.run_case(case)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Incomplete dependency graph:', result.stdout + result.stderr)
                self.assertEqual(receipts, [])


if __name__ == '__main__':
    unittest.main()
