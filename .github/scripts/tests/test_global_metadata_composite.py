# SPDX-License-Identifier: Apache-2.0
"""Exercise the experimental collector composite boundary with real offline Gradle."""
import errno, importlib.util, json, os, subprocess, tempfile, time, unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('fixture_helpers', ROOT / '.github/scripts/tests/test_dependency_resolution_guard.py')
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)

class CompositeCoverageTest(unittest.TestCase):

    def test_composite_boundary(self):
        distribution = HELPERS.installed_distribution(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
        self.assertIsNotNone(distribution, 'Provision the pinned Gradle distribution before running this test')
        home_holder = tempfile.TemporaryDirectory()
        try:
            home = home_holder.name
            gradle_home = Path(home)
            (gradle_home / 'wrapper').mkdir()
            (gradle_home / 'wrapper/dists').symlink_to(distribution, target_is_directory=True)
            for build, child, success in [('build-logic', False, True), ('other-plugins', False, False), ('build-logic', True, False)]:
                with self.subTest(build=build, child=child), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    (root / 'openbank-fixture').mkdir()
                    included = root / build
                    included.mkdir()
                    (root / 'settings.gradle').write_text("rootProject.name='openbank'\ninclude ':openbank-fixture'\nincludeBuild('" + build + "')\n")
                    (root / 'build.gradle').touch()
                    (included / 'settings.gradle').write_text("rootProject.name='plugin'\n" + ("include ':child'\n" if child else ''))
                    java = "plugins { id 'java-library' }\ngroup='g'; version='1'\n"
                    (included / 'build.gradle').write_text(java)
                    if child:
                        (included / 'child').mkdir()
                        (included / 'child/build.gradle').write_text(java)
                    coordinate = 'child' if child else 'plugin'
                    (root / 'openbank-fixture/build.gradle').write_text("buildscript { dependencies { classpath 'g:" + coordinate + ":1' } }\nconfigurations { classpath { canBeResolved=true; canBeConsumed=false } }\n")
                    receipts = root / 'receipts'
                    env = dict(os.environ, GRADLE_USER_HOME=str(gradle_home), OB_METADATA_PROJECTS=':openbank-fixture', OB_METADATA_MODEL_DIR=str(receipts))
                    result = subprocess.run([str(ROOT / 'gradlew'), '-p', str(root), '-I', str(ROOT / '.github/scripts/global-metadata-project-model.init.gradle'), 'exportMetadataScopeModel', '--offline', '--dependency-verification', 'strict', '--no-daemon', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx1g', '--console=plain'], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
                    if success:
                        self.assertEqual(result.returncode, 0, result.stdout)
                        model = json.loads((receipts / 'openbank-fixture.json').read_text())
                        self.assertIn('buildscript:classpath', model['receipts'])
                    else:
                        self.assertNotEqual(result.returncode, 0, result.stdout)
                        self.assertIn('Opaque composite dependency', result.stdout)
                        self.assertFalse((receipts / 'openbank-fixture.json').exists())
        finally:
            for attempt in range(5):
                try:
                    home_holder.cleanup()
                    break
                except OSError as error:
                    if error.errno != errno.ENOTEMPTY or attempt == 4:
                        raise
                    time.sleep(0.1 * (attempt + 1))
if __name__ == '__main__':
    unittest.main()
