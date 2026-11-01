#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fixture proofs for conservative library dependency discovery."""

import tempfile
import unittest
from pathlib import Path

from library_dependency_graph import read_dependencies, reverse_dependents


class LibraryDependencyGraphTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, name, source=""):
        path = self.root / name / "build.gradle.kts"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)

    def test_umbrella_multi_hop_diamond_and_unrelated(self):
        self.write("openbank-core")
        self.write("openbank-umbrella", 'api(project(":openbank-core"))')
        self.write("openbank-left", 'implementation(project(":openbank-umbrella"))')
        self.write("openbank-right", 'runtimeOnly(project(":openbank-core"))')
        self.write("openbank-app", '''
            implementation(project(":openbank-left"))
            compileOnly(project(":openbank-right"))
        ''')
        self.write("openbank-unrelated")
        graph = read_dependencies(self.root, False)
        self.assertIsNotNone(graph)
        expected = {"openbank-core", "openbank-umbrella", "openbank-left", "openbank-right", "openbank-app"}
        self.assertEqual(expected, reverse_dependents(graph, {"openbank-core"}))
        old_direct_only = {"openbank-core"} | {name for name, deps in graph.items() if "openbank-core" in deps}
        self.assertNotEqual(expected, old_direct_only, "old direct-only algorithm must fail this regression")
        self.assertEqual({"openbank-unrelated"}, reverse_dependents(graph, {"openbank-unrelated"}))

    def test_cycles_and_multiple_seeds_terminate(self):
        self.write("openbank-a", 'api(project(":openbank-b"))')
        self.write("openbank-b", 'api(project(":openbank-a"))')
        self.write("openbank-c")
        graph = read_dependencies(self.root, False)
        self.assertEqual(set(graph), reverse_dependents(graph, {"openbank-a", "openbank-c"}))
        self.assertEqual(set(), reverse_dependents(graph, set()))

    def test_test_dependencies_only_enter_ci_graph(self):
        self.write("openbank-core")
        for configuration in ("testImplementation", "testApi", "testRuntimeOnly", "testCompileOnly", "jmh"):
            self.write("openbank-" + configuration, f'{configuration}(project(":openbank-core"))')
        runtime = read_dependencies(self.root, False)
        ci = read_dependencies(self.root, True)
        self.assertEqual({"openbank-core"}, reverse_dependents(runtime, {"openbank-core"}))
        self.assertEqual(set(ci), reverse_dependents(ci, {"openbank-core"}))

    def test_comments_whitespace_named_argument_and_quoted_urls(self):
        self.write("openbank-core")
        self.write("openbank-app", '''
            val url = "https://example.test/project(:bogus)/*not a comment*/"
            val example = "implementation(project(\\":missing\\"))"
            val raw = """quoted "project(dynamic)" text""""
            /* outer /* nested project(dynamic) */ ignored */
            // implementation(project(":missing"))
            implementation /* note */ (
                project ( path /* note */ = ":openbank-core" )
            )
        ''')
        self.assertEqual({"openbank-core"}, read_dependencies(self.root, False)["openbank-app"])

    def test_unknown_syntax_fails_closed(self):
        self.write("openbank-core")
        invalid = [
            'implementation(project(dynamicName))',
            'implementation(project(":${dynamicName}"))',
            'implementation(project(path = dynamicName))',
            'implementation(project(":openbank-core", configuration = "custom"))',
            'implementation(project(path = ":openbank-core", configuration = "custom"))',
            'implementation(projects.openbankCore)',
            'implementation(projects["openbank-core"])',
            'implementation(`project`(":openbank-core"))',
            'implementation(project<Any>(":openbank-core"))',
            'implementation(project(""":openbank-core"""))',
            'implementation(project(":openbank-missing"))',
            'testImplementation(project(":openbank-missing"))',
            'implementation(project(":openbank-core")) /* unfinished',
            'customConfiguration(project(":openbank-core"))',
            'implementation(platform(project(":openbank-core")))',
            'val dependency = project(":openbank-core")',
            'implementation(rootProject.project(":openbank-core"))',
        ]
        for source in invalid:
            with self.subTest(source=source):
                self.write("openbank-app", source)
                self.assertIsNone(read_dependencies(self.root, False))
                self.assertIsNone(read_dependencies(self.root, True))

    def test_read_only_project_lookups_are_conservative_edges(self):
        self.write("openbank-core")
        sources = [
            'project(":openbank-core").layout.buildDirectory',
            'project(":openbank-core").file("src/fixture.json")',
            'testImplementation(project(":openbank-core").layout.buildDirectory)',
        ]
        for source in sources:
            with self.subTest(source=source):
                self.write("openbank-app", source)
                for include_tests in (False, True):
                    graph = read_dependencies(self.root, include_tests)
                    self.assertEqual({"openbank-core"}, graph["openbank-app"])

    def test_cross_project_configuration_requires_full_fallback(self):
        self.write("openbank-seed")
        self.write("openbank-target")
        self.write("openbank-app", 'implementation(project(":openbank-target"))')
        sources = [
            'project(":openbank-target") { dependencies { implementation(project(":openbank-seed")) } }',
            'project(":openbank-target").dependencies.add("implementation", project(":openbank-seed"))',
            'val target = project(":openbank-target"); target.dependencies.add("implementation", project(":openbank-seed"))',
            'configure(project(":openbank-target")) { dependencies { implementation(project(":openbank-seed")) } }',
            'project(":openbank-target").extensions.configure<SomeExtension> { }',
            'project(":openbank-target").tasks.register("changeClasspath") { }',
        ]
        for source in sources:
            with self.subTest(source=source):
                self.write("openbank-configurer", source)
                for include_tests in (False, True):
                    self.assertIsNone(
                        read_dependencies(self.root, include_tests),
                        "cannot attribute target's seed dependency to the configuring module",
                    )

    def test_cross_project_collections_and_root_aliases_require_fallback(self):
        self.write("openbank-core")
        sources = [
            'rootProject.subprojects { dependencies { implementation(project(":openbank-core")) } }',
            'rootProject.allprojects { dependencies { implementation(project(":openbank-core")) } }',
            'subprojects { dependencies { implementation(project(":openbank-core")) } }',
            'allprojects { dependencies { implementation(project(":openbank-core")) } }',
            'rootProject.childProjects.values.forEach { it.dependencies.add("implementation", project(":openbank-core")) }',
            'rootProject.getSubprojects().forEach { }',
            'rootProject.getAllprojects().forEach { }',
            'rootProject.getChildProjects().values.forEach { }',
            'val other = rootProject; other.subprojects { }',
            'rootProject.apply { dependencies { implementation(project(":openbank-core")) } }',
            'getRootProject().subprojects { }',
            'parent!!.subprojects { }',
        ]
        for source in sources:
            with self.subTest(source=source):
                self.write("openbank-app", source)
                self.assertIsNone(read_dependencies(self.root, False))
                self.assertIsNone(read_dependencies(self.root, True))

    def test_read_only_root_project_inputs_remain_supported(self):
        self.write("openbank-core")
        self.write("openbank-app", '''
            inputs.file(rootProject.file("some-fixture.txt"))
            inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
            inputs.files(rootProject.fileTree(rootProject.projectDir))
            // rootProject.subprojects { ignored comment }
            val explanation = "rootProject.allprojects { ignored string }"
            implementation(project(":openbank-core"))
        ''')
        self.assertEqual({"openbank-core"}, read_dependencies(self.root, False)["openbank-app"])

    def test_root_task_aggregation_is_not_an_unknown_dependency_graph(self):
        self.write("openbank-core")
        root_script = self.root / "build.gradle.kts"
        root_script.write_text('''
            tasks.register("sbomAll") {
                dependsOn(subprojects.mapNotNull { sub ->
                    sub.tasks.findByName("cyclonedxBom")
                })
            }
        ''')
        self.assertEqual({"openbank-core": set()}, read_dependencies(self.root, False))
        root_script.write_text('''
            subprojects {
                dependencies { implementation(project(":openbank-core")) }
            }
        ''')
        self.assertIsNone(read_dependencies(self.root, False))

    def test_absent_modules_and_unknown_seeds_are_not_success(self):
        self.assertIsNone(read_dependencies(self.root, False))
        self.write("openbank-core")
        with self.assertRaises(ValueError):
            reverse_dependents(read_dependencies(self.root, False), {"openbank-missing"})
        with self.assertRaises(ValueError):
            reverse_dependents({"openbank-core": {"openbank-missing"}}, {"openbank-core"})

    def test_external_project_dependencies_fail_closed(self):
        self.write("openbank-core")
        for relative in ("build.gradle.kts", "build-logic/src/main/kotlin/example.gradle.kts"):
            with self.subTest(relative=relative):
                path = self.root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('implementation(project(":openbank-core"))')
                self.assertIsNone(read_dependencies(self.root, False))
                path.unlink()

    def test_only_exact_existing_static_analysis_tooling_is_exempt(self):
        self.write("openbank-core")
        self.write("openbank-libs-detekt-rules")
        path = self.root / "build-logic/src/main/kotlin/openbank.static-analysis.gradle.kts"
        path.parent.mkdir(parents=True)
        path.write_text('detektCli(project(":openbank-libs-detekt-rules"))')
        self.assertIsNotNone(read_dependencies(self.root, False))
        path.write_text('detektCli(project(":openbank-core"))')
        self.assertIsNone(read_dependencies(self.root, False))
        path.write_text('detektCli(project(dynamic))')
        self.assertIsNone(read_dependencies(self.root, False))

    def test_repository_graph_reaches_umbrella_consumers(self):
        root = Path(__file__).resolve().parents[2]
        graph = read_dependencies(root, False)
        self.assertIsNotNone(graph, "current repository syntax must remain supported")
        affected = reverse_dependents(graph, {"openbank-libs-domain"})
        self.assertTrue({"openbank-customer-edge", "openbank-product-catalog"} <= affected)
        self.assertNotIn("openbank-libs-benchmarks", affected)
        ci = read_dependencies(root, True)
        self.assertIsNotNone(ci)
        self.assertIn("openbank-libs-benchmarks", reverse_dependents(ci, {"openbank-libs-domain"}))


if __name__ == "__main__":
    unittest.main()
