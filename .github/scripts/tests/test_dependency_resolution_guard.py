# SPDX-License-Identifier: Apache-2.0
"""Exercise the real Gradle guard, including on cold governance CI runners."""
import errno
import json
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[3]
GUARD = ROOT / '.github/scripts/dependency-resolution-strict.init.gradle'


WRAPPER_PROPERTIES = ROOT / 'gradle/wrapper/gradle-wrapper.properties'


def pinned_archive(properties=WRAPPER_PROPERTIES):
    url = next(line.split('=', 1)[1].strip() for line in properties.read_text().splitlines()
               if line.startswith('distributionUrl='))
    return url.rsplit('/', 1)[1]


def installed_distribution(gradle_user_home, properties=WRAPPER_PROPERTIES):
    """Return the wrapper/dists entry for the pinned distribution, or None if not installed.

    The wrapper marks a finished unpack with `<zip>.ok` next to the unpacked directory; a dir
    without it is a half-download the wrapper would redo over the network.
    """
    archive = pinned_archive(properties)
    dists = Path(gradle_user_home) / 'wrapper/dists'
    if any((dists / archive.removesuffix('.zip')).glob(f'*/{archive}.ok')):
        return dists
    return None


class InstalledDistributionTests(unittest.TestCase):
    # Must-fail half of the lookup: without it, a lookup that always answered "found" would let
    # the fixture builds silently fall back to a network download again.
    def test_absent_or_unfinished_distribution_is_not_found(self):
        with tempfile.TemporaryDirectory() as home:
            self.assertIsNone(installed_distribution(home))
            archive = pinned_archive()
            unpacked = Path(home) / 'wrapper/dists' / archive.removesuffix('.zip') / 'hash'
            unpacked.mkdir(parents=True)
            self.assertIsNone(installed_distribution(home))
            (unpacked / f'{archive}.ok').touch()
            self.assertEqual(installed_distribution(home), Path(home) / 'wrapper/dists')


class BuildscriptFreeMarkerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # setup-gradle installs graph init scripts in the runner's Gradle home.
        # Fixture builds must exercise only our guard, so they get a temp Gradle home that
        # reuses the already installed distribution. Missing distribution is a FAILURE, not a
        # skip and not a download: services.gradle.org answering 504 once failed an unrelated
        # PR here. CI provisions it before the tests (ci.yml gates shard, dependency-submission).
        distributions = installed_distribution(
            os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
        if distributions is None:
            raise RuntimeError(
                f'Gradle distribution pinned in {WRAPPER_PROPERTIES.relative_to(ROOT)} is not '
                'installed under $GRADLE_USER_HOME/wrapper/dists; run ./gradlew --version once '
                '(CI: .github/scripts/provision-gradle-distribution.sh) before these tests.')
        cls._home = tempfile.TemporaryDirectory()
        cls.gradle_home = Path(cls._home.name)
        (cls.gradle_home / 'wrapper').mkdir()
        (cls.gradle_home / 'wrapper/dists').symlink_to(distributions, target_is_directory=True)

    @classmethod
    def tearDownClass(cls):
        # Gradle's single-use daemon can finish a late write after the wrapper exits.
        # Retry only that directory-removal race; all other cleanup errors still fail.
        for attempt in range(5):
            try:
                cls._home.cleanup()
                return
            except OSError as error:
                if error.errno != errno.ENOTEMPTY or attempt == 4:
                    raise
                time.sleep(0.1 * (attempt + 1))

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
            env = dict(os.environ, GRADLE_USER_HOME=str(self.gradle_home))
            # A daemon can keep writing into the temporary Gradle home after this
            # process exits, racing tearDownClass cleanup.
            command = [str(ROOT / 'gradlew'), '-p', str(fixture), '--init-script', str(GUARD),
                       '--no-daemon', '--offline', task, *(['--dry-run'] if resolver else [])]
            for attempt in range(3):
                result = subprocess.run(command, env=env, capture_output=True, text=True,
                                        timeout=120, check=False)
                output = result.stdout + result.stderr
                # --offline affects Gradle dependency resolution, not a cold wrapper's
                # distribution download. The first call in a fresh hosted runner may fail
                # with a connection reset; retry only that transport failure. A failed guard
                # assertion or build still returns immediately and fails this required gate.
                wrapper_download_failed = (result.returncode != 0
                                           and 'Fetching distribution.' in output
                                           and 'Downloading https://services.gradle.org/distributions/' in output
                                           and 'Exception in thread "main"' in output)
                if not wrapper_download_failed or attempt == 2:
                    return result

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


class GradleHomeCleanupTests(unittest.TestCase):
    def test_late_daemon_write_is_retried(self):
        home = mock.Mock()
        home.cleanup.side_effect = [OSError(errno.ENOTEMPTY, 'Directory not empty'), None]
        with mock.patch.object(BuildscriptFreeMarkerTests, '_home', home, create=True), mock.patch('time.sleep'):
            BuildscriptFreeMarkerTests.tearDownClass()
        self.assertEqual(home.cleanup.call_count, 2)

    def test_unrelated_cleanup_error_still_fails(self):
        home = mock.Mock()
        home.cleanup.side_effect = OSError(errno.EACCES, 'Permission denied')
        with mock.patch.object(BuildscriptFreeMarkerTests, '_home', home, create=True), mock.patch('time.sleep'):
            with self.assertRaises(OSError) as error:
                BuildscriptFreeMarkerTests.tearDownClass()
        self.assertEqual(error.exception.errno, errno.EACCES)
        home.cleanup.assert_called_once()

    def test_persistent_late_writes_still_fail(self):
        home = mock.Mock()
        home.cleanup.side_effect = OSError(errno.ENOTEMPTY, 'Directory not empty')
        with mock.patch.object(BuildscriptFreeMarkerTests, '_home', home, create=True), mock.patch('time.sleep'):
            with self.assertRaises(OSError) as error:
                BuildscriptFreeMarkerTests.tearDownClass()
        self.assertEqual(error.exception.errno, errno.ENOTEMPTY)
        self.assertEqual(home.cleanup.call_count, 5)


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
