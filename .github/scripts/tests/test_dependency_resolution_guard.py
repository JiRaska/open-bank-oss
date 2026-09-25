# SPDX-License-Identifier: Apache-2.0
"""Exercise the real Gradle guard after setup-gradle enabled graph generation."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[3]
GUARD = ROOT / '.github/scripts/dependency-resolution-strict.init.gradle'


class BuildscriptFreeMarkerTests(unittest.TestCase):
    def run_case(self, version, resolver=False):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            (fixture / 'settings.gradle').write_text("rootProject.name = 'buildscript-floor-test'\n")
            build = (
                "buildscript { repositories { maven { url = uri('repo') } }; "
                f"dependencies {{ classpath 'org.freemarker:freemarker:{version}' }} }}\n"
            )
            if resolver:
                build += "tasks.register('ForceDependencyResolutionPlugin_resolveProjectDependencies')\n"
            (fixture / 'build.gradle').write_text(build)
            artifact = fixture / 'repo/org/freemarker/freemarker' / version
            artifact.mkdir(parents=True)
            (artifact / f'freemarker-{version}.pom').write_text(
                '<project><modelVersion>4.0.0</modelVersion><groupId>org.freemarker</groupId>'
                f'<artifactId>freemarker</artifactId><version>{version}</version></project>'
            )
            with ZipFile(artifact / f'freemarker-{version}.jar', 'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
            task = ('ForceDependencyResolutionPlugin_resolveProjectDependencies'
                    if resolver else 'VerifyBuildscriptFreeMarker')
            return subprocess.run(
                [str(ROOT / 'gradlew'), '-p', str(fixture), '--init-script', str(GUARD),
                 '--offline', task, *(['--dry-run'] if resolver else [])],
                capture_output=True, text=True, timeout=120, check=False,
            )

    def test_vulnerable_plugin_classpath_fails(self):
        result = self.run_case('2.3.32')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Vulnerable buildscript FreeMarker: 2.3.32', result.stdout + result.stderr)

    def test_last_vulnerable_version_fails(self):
        result = self.run_case('2.3.34')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Vulnerable buildscript FreeMarker: 2.3.34', result.stdout + result.stderr)

    def test_patched_plugin_classpath_passes(self):
        result = self.run_case('2.3.35')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_dependency_resolver_runs_the_buildscript_check_first(self):
        result = self.run_case('2.3.35', resolver=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertLess(result.stdout.index(':VerifyBuildscriptFreeMarker SKIPPED'),
                        result.stdout.index(':ForceDependencyResolutionPlugin_resolveProjectDependencies SKIPPED'))


# Needs the graph plugin setup-gradle injects; dependency-submission.yml runs it and fails
# its step when that environment is absent, so this skip cannot hide a CI run.
@unittest.skipUnless(os.environ.get('GITHUB_DEPENDENCY_GRAPH_SHA'), 'needs setup-gradle dependency-graph environment')
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
