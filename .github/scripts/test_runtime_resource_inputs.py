#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline source-closure proof for literal Gradle runtime resource copies."""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

GIT_OVERRIDES = {"GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR"}


def fixture_env():
    return {key: value for key, value in os.environ.items() if key not in GIT_OVERRIDES}


SCRIPT = Path(__file__).with_name("runtime_resource_inputs.py").resolve()
spec = importlib.util.spec_from_file_location("runtime_resource_inputs", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

AGENT = '''
// Unrelated source strings and test configuration must not become runtime inputs.
testImplementation("not/a/runtime/read")
val example = "tasks.named<Copy>(\\\"processResources\\\") { from(rootProject.file(\\\"fake\\\")) }"
tasks.named<Copy>("processTestResources") { from(rootProject.file("test-fixtures")) }
tasks.named<Copy>("processResources") {
    from(rootProject.file(".")) {
        include("openbank-*/governance.yaml")
        into("business-domains")
        rename { "a}filename" }
    }
    from(rootProject.file("openbank-libs/governance/domain-taxonomy.json")) {
        rename { "taxonomy.json" }
    }
    from(rootProject.file("docs/prompts")) { include("*.md") }
}
'''


class RuntimeResourceInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="runtime-resources-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.changed = set()
        self.git("init", "-q")
        self.git("config", "user.name", "Resource Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.fileMode", "true")
        self.write("openbank-agent-service/build.gradle.kts", AGENT)
        self.write("openbank-account-service/build.gradle.kts", "plugins {}")
        self.write("openbank-libs/build.gradle.kts", "plugins {}")
        self.write("openbank-account-service/governance.yaml", "dataDomain: core\n")
        self.write("openbank-libs/governance/domain-taxonomy.json", "{}\n")
        self.write("docs/prompts/system.md", "prompt\n")
        self.base = self.commit("baseline")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.PIPE,
                                       env=fixture_env()).decode().strip()

    def write(self, path, body):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
        self.changed.add(path)

    def commit(self, message):
        if self.changed:
            self.git("add", "--", *sorted(self.changed))
            self.changed.clear()
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def cli(self, *args, paths=""):
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), *args],
                              input=paths, text=True, capture_output=True, env=fixture_env())

    def compare(self, base=None, head="HEAD", services=("openbank-agent-service",)):
        args = ["--compare", "--base", base or self.base, "--head", head]
        for service in services:
            args.extend(["--service", service])
        return self.cli(*args)

    def test_literal_packaged_governance_taxonomy_and_prompts_only(self):
        result = self.cli("--ref", "HEAD", "--service", "openbank-agent-service")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(set(result.stdout.splitlines()), {
            "openbank-*/governance.yaml", "openbank-libs/governance/domain-taxonomy.json", "docs/prompts/*.md"})
        self.assertNotIn("test-fixtures", result.stdout)
        self.assertNotIn("not/a/runtime/read", result.stdout)

    def test_readers_are_allowlisted_real_modules_and_only_runtime_inputs_match(self):
        options = ("--ref", "HEAD", "--readers", "openbank-agent-service openbank-account-service openbank-ghost openbank-document-renderer")
        for path in ["openbank-account-service/governance.yaml", "openbank-libs/governance/domain-taxonomy.json", "docs/prompts/new.md"]:
            with self.subTest(path=path):
                result = self.cli(*options, paths=path + "\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.splitlines(), ["openbank-agent-service"])
        for path in ["openbank-account-service/src/main/kotlin/A.kt", "openbank-account-service/src/test/A.kt",
                     "openbank-account-service/other.yaml", "test-fixtures/test.yaml", "not/a/runtime/read",
                     "docs/prompts/new/sub.md", "docs/prompts/ignored.yaml"]:
            with self.subTest(path=path):
                result = self.cli(*options, paths=path + "\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "")

    def test_prompt_rename_selects_both_static_consumers_with_no_rename_paths(self):
        for service, directory in [("svc-a", "first"), ("svc-b", "second")]:
            self.write(f"{service}/build.gradle.kts", f'''
                tasks.named<Copy>("processResources") {{
                    from(rootProject.file("docs/{directory}")) {{ include("*.md") }}
                }}
            ''')
        self.write("docs/first/source.md", "unchanged copied prompt\n")
        self.write("docs/second/existing.md", "another copied prompt\n")
        before = self.commit("add two prompt consumers")
        self.git("config", "diff.renames", "true")
        self.git("mv", "docs/first/source.md", "docs/second/moved.md")
        head = self.commit("move unchanged prompt between consumers")

        default_paths = self.git("diff", "--name-only", before, head).splitlines()
        self.assertEqual(default_paths, ["docs/second/moved.md"])
        changed_paths = self.git("diff", "--name-only", "--no-renames", before, head).splitlines()
        self.assertEqual(set(changed_paths), {"docs/first/source.md", "docs/second/moved.md"})
        result = self.cli("--ref", head, "--readers", "svc-a svc-b openbank-agent-service",
                          paths="\n".join(changed_paths) + "\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(set(result.stdout.splitlines()), {"svc-a", "svc-b"})

    def test_ref_reads_committed_scripts_instead_of_worktree(self):
        self.write("openbank-agent-service/build.gradle.kts", 'tasks.named<Copy>("processResources") { from(rootProject.file(variable)) }')
        committed = self.cli("--ref", "HEAD", "--service", "openbank-agent-service")
        working = self.cli("--service", "openbank-agent-service")
        self.assertEqual(committed.returncode, 0)
        self.assertNotEqual(working.returncode, 0)
        self.assertNotEqual(self.cli("--ref", "missing-ref", "--service", "openbank-agent-service").returncode, 0)

    def test_balanced_nested_blocks_comments_and_rename(self):
        source = '''/* tasks.named("processResources") { from(rootProject.file("fake")) } */
        tasks.named("processResources") {
            from(rootProject.file(".")) { include("data/**/*.json"); rename { "}literal" } }
            from(rootProject.file("docs/prompts")) { eachFile { if (true) { println("{") } } }
        }'''
        self.assertEqual(module.resource_patterns(source), {"data/**/*.json", "docs/prompts"})
        for path in ["data/root.json", "data/nested/deep.json"]:
            self.assertTrue(module.path_matches(path, "data/**/*.json"))
        self.assertFalse(module.path_matches("data/nested/deep.json", "data/*.json"))
        self.assertFalse(module.path_matches("data/rootxjson", "data/**/*.json"))

    def test_unsupported_recognized_root_reads_fail_closed(self):
        bodies = [
            'from(rootProject.file(variable))', 'from(rootProject.file("$variable"))',
            'from(rootProject.rootDir)', 'from(rootProject.file("../private"))',
            'from(rootProject.file("/absolute"))', 'from(rootProject.file("C:/absolute"))',
            'from(rootProject.file("."))', 'from(rootProject.file(".")) { rename { "file" } }',
            'from(rootProject.file(".")) { include(variable) }',
            'from(rootProject.file(".")) { include("data/*.json", variable) }',
            'from(rootProject.file(".")) { include("../../private") }',
            'from(rootProject.file(".")) { include("data/?.json") }',
            'from(rootProject.file("docs")) { include("?.md") }',
        ]
        for body in bodies:
            with self.subTest(body=body):
                with self.assertRaises(module.UnsupportedInputs):
                    module.resource_patterns('tasks.named<Copy>("processResources") { ' + body + ' }')
        with self.assertRaises(module.UnsupportedInputs):
            module.resource_patterns('tasks.named<Copy>("processResources") { from(rootProject.file("x"))')
        self.assertEqual(module.resource_patterns('tasks.named<Copy>("processTestResources") { from(rootProject.file(variable)) }'), set())
        self.assertEqual(module.resource_patterns('tasks.named(otherTask) { println("*/*") }'), set())
        with self.assertRaises(module.UnsupportedInputs):
            module.resource_patterns('tasks.named(otherTask) { from(rootProject.file(variable)) }')

    def test_root_aliases_cannot_masquerade_as_module_local_providers(self):
        declarations = [
            'val ext = rootProject.file("shared/data.json")',
            'val ext: File = rootProject.file("shared/data.json")',
            'var ext: File; ext = rootProject.file("shared/data.json")',
            'val ext = rootProject.rootDir',
            'val base = rootProject.file("shared"); val ext = base.resolve("data.json")',
        ]
        for declaration in declarations:
            with self.subTest(declaration=declaration):
                with self.assertRaises(module.UnsupportedInputs):
                    module.resource_patterns(declaration + '; tasks.named<Copy>("processResources") { from(ext) }')
        with self.assertRaises(module.UnsupportedInputs):
            module.resource_patterns('val ext = rootProject.file("shared/data.json"); tasks.named(taskName) { from(ext) }')
        with self.assertRaises(module.UnsupportedInputs):
            module.resource_patterns('val filter = rootProject.file("shared/filter.txt").readText(); tasks.named<Copy>("processResources") { from(rootProject.file("shared/data")) { include(filter) } }')
        generated = ('val unused = rootProject.file("test-only"); '
                     'val generatedBuildInfo = layout.buildDirectory.file("generated/info.properties"); '
                     'tasks.named<Copy>("processTestResources") { from(unused) }; '
                     'tasks.named<Copy>("processResources") { from(generatedBuildInfo) }')
        self.assertEqual(module.resource_patterns(generated), set())

    def test_root_alias_compare_refuses_changed_external_bytes(self):
        self.write("openbank-agent-service/build.gradle.kts", 'val ext = rootProject.file("shared/data.json"); tasks.named<Copy>("processResources") { from(ext) }')
        self.write("shared/data.json", "before")
        before = self.commit("aliased source")
        self.write("shared/data.json", "after")
        after = self.commit("aliased resource changed")
        self.assertNotEqual(self.compare(base=before, head=after).returncode, 0)

    def test_nonroot_static_include_narrows_governance_inputs(self):
        source = 'tasks.named<Copy>("processResources") { from(rootProject.file("openbank-libs/governance")) { include("agents.yaml") } }'
        patterns = module.resource_patterns(source)
        self.assertEqual(patterns, {"openbank-libs/governance/agents.yaml"})
        self.assertTrue(any(module.path_matches("openbank-libs/governance/agents.yaml", p) for p in patterns))
        for path in ["openbank-libs/governance/rules.yaml", "openbank-libs/governance/domain-taxonomy.json"]:
            self.assertFalse(any(module.path_matches(path, p) for p in patterns))

    def test_file_source_include_covers_only_matching_basename(self):
        matching = module.resource_patterns('tasks.named<Copy>("processResources") { from(rootProject.file("shared/data.json")) { include("*.json") } }')
        self.assertEqual(matching, {"shared/data.json", "shared/data.json/*.json"})
        self.assertTrue(any(module.path_matches("shared/data.json", p) for p in matching))
        not_matching = module.resource_patterns('tasks.named<Copy>("processResources") { from(rootProject.file("shared/data.json")) { include("other.json") } }')
        self.assertEqual(not_matching, {"shared/data.json/other.json"})
        self.assertFalse(any(module.path_matches("shared/data.json", p) for p in not_matching))
        dynamic = module.resource_patterns('tasks.named<Copy>("processResources") { from(rootProject.file("shared/data.json")) { include(variable) } }')
        self.assertEqual(dynamic, {"shared/data.json"})

    def test_no_repository_code_is_executed(self):
        source = 'throw Error("must not execute")\ntasks.named<Copy>("processResources") { from(rootProject.file("docs/prompts")) }'
        self.assertEqual(module.resource_patterns(source), {"docs/prompts"})

    def test_unchanged_and_unrelated_input_are_equivalent(self):
        self.assertEqual(self.compare().returncode, 0)
        self.write("openbank-account-service/src/main/kotlin/A.kt", "body changed")
        head = self.commit("unrelated source")
        self.assertEqual(self.compare(head=head).returncode, 0)

    def test_changed_added_and_removed_inputs_are_not_equivalent(self):
        self.write("openbank-account-service/governance.yaml", "dataDomain: pensions\n")
        head = self.commit("ownership")
        self.assertNotEqual(self.compare(head=head).returncode, 0)
        self.write("openbank-new-service/governance.yaml", "dataDomain: wealth\n")
        self.assertNotEqual(self.compare(base=head, head=self.commit("added input")).returncode, 0)
        before_delete = self.git("rev-parse", "HEAD")
        (self.root / "docs/prompts/system.md").unlink()
        self.changed.add("docs/prompts/system.md")
        self.assertNotEqual(self.compare(base=before_delete, head=self.commit("removed input")).returncode, 0)

    def test_mode_only_change_is_not_equivalent(self):
        (self.root / "docs/prompts/system.md").chmod(0o755)
        self.changed.add("docs/prompts/system.md")
        head = self.commit("mode only")
        self.assertNotEqual(self.compare(head=head).returncode, 0)

    def test_changed_resource_membership_is_not_equivalent(self):
        self.write("openbank-agent-service/build.gradle.kts", AGENT.replace('"docs/prompts"', '"docs/new-prompts"'))
        self.assertNotEqual(self.compare(head=self.commit("membership changed")).returncode, 0)
        self.write("openbank-agent-service/build.gradle.kts", "plugins {}")
        head = self.commit("removed declaration")
        self.assertNotEqual(self.compare(head=head).returncode, 0)
        self.assertNotEqual(self.compare(base=head, head=self.base).returncode, 0)

    def test_service_and_dependency_inputs_are_both_compared(self):
        self.write("openbank-libs/build.gradle.kts", 'tasks.named<Copy>("processResources") { from(rootProject.file("docs/library-input")) }')
        self.write("docs/library-input/schema.json", "old")
        before = self.commit("dependency runtime input")
        self.write("docs/library-input/schema.json", "new")
        head = self.commit("dependency resource changed")
        self.assertEqual(self.compare(base=before, head=head).returncode, 0)
        self.assertNotEqual(self.compare(base=before, head=head, services=("openbank-agent-service", "openbank-libs")).returncode, 0)

    def test_symlink_and_gitlink_inputs_cannot_prove_equivalence(self):
        link = self.root / "docs/prompts/link.md"
        link.symlink_to("outside-target")
        self.changed.add("docs/prompts/link.md")
        head = self.commit("symlink input")
        self.assertNotEqual(self.compare(base=head, head=head).returncode, 0)
        link.unlink()
        self.git("rm", "--cached", "docs/prompts/link.md")
        self.git("update-index", "--add", "--cacheinfo", "160000", self.base, "docs/prompts/submodule.md")
        linked = self.commit("gitlink input")
        self.assertNotEqual(self.compare(base=linked, head=linked).returncode, 0)

    def test_resource_path_below_symlink_ancestor_is_not_equivalent(self):
        self.write("openbank-agent-service/build.gradle.kts", 'tasks.named<Copy>("processResources") { from(rootProject.file("docs/link/child.json")) }')
        (self.root / "docs/link").symlink_to("external-directory", target_is_directory=True)
        self.changed.add("docs/link")
        head = self.commit("linked ancestor")
        self.assertNotEqual(self.compare(base=head, head=head).returncode, 0)

    def test_non_openbank_module_names_are_supported(self):
        self.write("svc-a/build.gradle.kts", 'tasks.named<Copy>("processResources") { from(rootProject.file("docs/prompts")) }')
        head = self.commit("fixture module")
        result = self.cli("--ref", head, "--service", "svc-a")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "docs/prompts")
        self.assertEqual(self.compare(base=head, head=head, services=("svc-a",)).returncode, 0)

    def test_private_ci_index_and_git_directory_overrides_cannot_escape_fixture_root(self):
        foreign = self.root / "foreign"
        foreign.mkdir()
        def foreign_git(*args):
            return subprocess.check_output(["git", "-C", str(foreign), *args], stderr=subprocess.PIPE, env=fixture_env())
        foreign_git("init", "-q")
        (foreign / "marker.txt").write_text("external index must remain untouched")
        foreign_git("add", "--", "marker.txt")
        index = foreign / ".git/index"
        before = index.read_bytes()
        hostile = {
            "GIT_INDEX_FILE": str(index), "GIT_DIR": str(foreign / ".git"),
            "GIT_WORK_TREE": str(foreign), "GIT_COMMON_DIR": str(foreign / ".git"),
            "GIT_OBJECT_DIRECTORY": str(foreign / ".git/objects"),
            "GIT_ALTERNATE_OBJECT_DIRECTORIES": str(foreign / ".git/objects"),
        }
        with patch.dict(os.environ, hostile):
            self.write("local-marker.txt", "staged only in the fixture")
            self.commit("hostile CI env")
            result = self.cli("--ref", "HEAD", "--service", "openbank-agent-service")
            self.assertEqual(result.returncode, 0, result.stderr)
            # Bypass the test wrapper's sanitizer to exercise SourceTree.git itself.
            direct = subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root),
                                     "--ref", "HEAD", "--service", "openbank-agent-service"],
                                    text=True, capture_output=True, env=os.environ.copy())
            self.assertEqual(direct.returncode, 0, direct.stderr)
            self.assertEqual(direct.stdout, result.stdout)
        self.assertEqual(index.read_bytes(), before)
        self.assertIn("local-marker.txt", self.git("show", "--format=", "--name-only", "HEAD"))

    def test_real_runtime_inputs_and_consumer_builds_have_ordered_auto_deploy_triggers(self):
        root = SCRIPT.parents[2]
        snapshot = module.SourceTree(root, "HEAD")
        working = module.SourceTree(root, None)
        workflow = yaml.load((root / ".github/workflows/auto-deploy.yml").read_text(), Loader=yaml.BaseLoader)
        push_patterns = workflow["on"]["push"]["paths"]
        self.assertIsInstance(push_patterns, list)
        self.assertTrue(push_patterns)
        for pattern in push_patterns:
            self.assertIsInstance(pattern, str)
            self.assertNotRegex(pattern, r"[?+\[\]\\]")

        def triggered(path, rules=push_patterns):
            included = False
            for rule in rules:
                negative = rule.startswith("!")
                pattern = rule[1:] if negative else rule
                matched = module.path_matches(path, pattern) if "*" in pattern else path == pattern
                if matched:
                    included = not negative
            return included

        ordered = ["**", "!docs/**", "docs/prompts/*.md"]
        self.assertTrue(triggered("docs/prompts/system.md", ordered))
        self.assertFalse(triggered("docs/other.md", ordered))
        self.assertFalse(triggered("docs/prompts/nested/system.md", ordered))
        self.assertFalse(triggered("docs/prompts/system.md", ["docs"]))
        covered_inputs = set()
        consumers = set()
        for service in sorted(snapshot.modules & working.modules):
            patterns = working.patterns(service)
            if not patterns:
                continue
            consumers.add(service)
            snapshot.reject_links(patterns)
            for path, mode in snapshot.modes.items():
                if any(module.path_matches(path, pattern) for pattern in patterns):
                    self.assertIn(mode, module.REGULAR_MODES, path)
                    self.assertTrue(triggered(path), f"{service}: runtime input is absent from auto-deploy push.paths: {path}")
                    covered_inputs.add(path)
            for name in sorted(module.BUILD_FILES):
                path = f"{service}/{name}"
                if path in snapshot.modes:
                    self.assertTrue(triggered(path), f"runtime consumer build declaration is not triggered: {path}")
        self.assertTrue(consumers, "real source corpus unexpectedly contains no resource consumers")
        self.assertTrue(covered_inputs, "real source corpus unexpectedly contains no tracked runtime inputs")

    def test_compare_unreadable_and_missing_module_fails_closed(self):
        self.assertNotEqual(self.compare(base="missing-ref").returncode, 0)
        self.assertNotEqual(self.compare(services=("nonexistent-service",)).returncode, 0)


if __name__ == "__main__":
    unittest.main()
