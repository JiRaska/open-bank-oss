#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every Quarkus test in the repo binds ephemeral ports, never a fixed one.

THE DEFECT THIS EXISTS FOR
--------------------------
`@QuarkusTest` binds `quarkus.http.test-port` (default 8081), `quarkus.http.test-ssl-port`
(8444) and, when the management interface is on, `quarkus.management.test-port` (9001). Those
defaults are per MACHINE, not per build: two Gradle runs on one host -- parallel agent sessions,
a second worktree, two jobs on a shared runner -- race for the same socket and the loser dies
with `QuarkusBindException`. Measured 2026-09-30: 65 of 70 Quarkus service modules ran on the
fixed default, and the failure recurred all day. It is worse than a red build: a service that
cannot boot reports its @QuarkusTest classes as SKIPPED, which reads as a pass.

THE CONTROL
-----------
`build-logic/.../openbank.quarkus-service.gradle.kts` sets all three keys to `0` as system
properties on every Test task. A system property outranks application.yaml (ordinal 400 vs
250), so a module's own config cannot undo it; Quarkus writes the bound port back into the same
keys, so everything that reads them sees the real value. Two libs modules run @QuarkusTest
without applying that plugin and set the same three properties themselves.

WHAT THIS ENFORCES
------------------
1. The convention plugin sets all three keys to "0" inside `tasks.withType<Test>()`.
2. Every module with a Quarkus test (`@QuarkusTest`, `@QuarkusIntegrationTest`,
   `@QuarkusMainTest`) either applies `openbank.quarkus-service` or sets all three keys to "0"
   in its own build.gradle.kts. A new module that forgets is a finding, not a silent regression.
3. No test source pins a non-zero port through a source that OUTRANKS the system property: a
   `QuarkusTestProfile.getConfigOverrides()` or `QuarkusTestResourceLifecycleManager.start()`
   map entry, or `System.setProperty`. (application.yaml and test resources cannot outrank it,
   but a non-zero value there is still reported, because it documents a port nothing uses.)
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

KEYS = ("quarkus.http.test-port", "quarkus.http.test-ssl-port", "quarkus.management.test-port")
PLUGIN_ID = "openbank.quarkus-service"
PLUGIN_PATH = "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts"
QUARKUS_TEST = re.compile(r"@(QuarkusTest|QuarkusIntegrationTest|QuarkusMainTest)\b")
SYSPROP = re.compile(r'systemProperty\(\s*"(quarkus\.[a-z.-]+)"\s*,\s*"([^"]*)"\s*\)')
# A config-key literal followed by a value literal: `"k" to "v"`, `put("k", "v")`, `["k"] = "v"`,
# `System.setProperty("k", "v")`.
KEY_ALT = "|".join(re.escape(k) for k in KEYS)
CODE_PIN = re.compile(r'"(' + KEY_ALT + r')"\s*(?:\)\s*\]?\s*=|,|to|\]\s*=)\s*"(\d+)"')
YAML_PIN = re.compile(r"^\s*(test-port|test-ssl-port)\s*:\s*[\"']?(\d+)[\"']?\s*(?:#.*)?$")
PROPS_PIN = re.compile(r"^\s*(?:%[a-z-]+\.)?(" + KEY_ALT + r")\s*=\s*(\d+)\s*$")


def tracked_files(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True)
    return out.stdout.splitlines()


def sets_all_keys_to_zero(gradle_text: str) -> list[str]:
    """Keys NOT set to "0" in this build script (empty = compliant)."""
    found = {k: v for k, v in SYSPROP.findall(gradle_text)}
    return [k for k in KEYS if found.get(k) != "0"]


def check(files: dict[str, str]) -> tuple[list[str], int]:
    """files: repo-relative path -> content. Returns (findings, subjects)."""
    findings: list[str] = []

    plugin = files.get(PLUGIN_PATH)
    if plugin is None:
        findings.append(f"{PLUGIN_PATH}: convention plugin not found")
    else:
        block = plugin.split("tasks.withType<Test>().configureEach", 1)
        missing = sets_all_keys_to_zero(block[1] if len(block) == 2 else "")
        if len(block) != 2:
            findings.append(f"{PLUGIN_PATH}: no tasks.withType<Test>().configureEach block")
        for k in missing:
            findings.append(f'{PLUGIN_PATH}: withType<Test> must set systemProperty("{k}", "0")')

    modules: set[str] = set()
    for path, text in files.items():
        parts = path.split("/")
        if len(parts) > 3 and parts[1] == "src" and parts[2] != "main" and path.endswith(".kt"):
            if QUARKUS_TEST.search(text):
                modules.add(parts[0])
    for module in sorted(modules):
        gradle = files.get(f"{module}/build.gradle.kts")
        if gradle is None:
            findings.append(f"{module}: has Quarkus tests but no build.gradle.kts")
            continue
        if PLUGIN_ID in gradle:
            continue
        for k in sets_all_keys_to_zero(gradle):
            findings.append(
                f'{module}/build.gradle.kts: runs Quarkus tests without {PLUGIN_ID}, so it must set '
                f'systemProperty("{k}", "0") on its Test task itself',
            )

    for path, text in files.items():
        in_test_code = "/src/" in path and "/src/main/" not in path and path.endswith(".kt")
        if in_test_code:
            for key, port in CODE_PIN.findall(text):
                if port != "0":
                    findings.append(f"{path}: pins {key}={port}, which outranks the ephemeral default")
        elif path.endswith((".yaml", ".yml")) and "/src/" in path and "/resources/" in path:
            for line in text.splitlines():
                m = YAML_PIN.match(line)
                if m and m.group(2) != "0":
                    findings.append(f"{path}: fixed {m.group(1)}: {m.group(2)} (use 0)")
        elif path.endswith(".properties") and "/src/" in path and "/resources/" in path:
            for line in text.splitlines():
                m = PROPS_PIN.match(line)
                if m and m.group(2) != "0":
                    findings.append(f"{path}: fixed {m.group(1)}={m.group(2)} (use 0)")

    return findings, len(modules)


def load(root: Path) -> dict[str, str]:
    wanted = (".kt", ".kts", ".yaml", ".yml", ".properties")
    files: dict[str, str] = {}
    for rel in tracked_files(root):
        if rel.endswith(wanted):
            p = root / rel
            if p.is_file():
                files[rel] = p.read_text(encoding="utf-8", errors="replace")
    return files


GOOD_PLUGIN = (
    'tasks.withType<Test>().configureEach {\n'
    '    systemProperty("quarkus.http.test-port", "0")\n'
    '    systemProperty("quarkus.http.test-ssl-port", "0")\n'
    '    systemProperty("quarkus.management.test-port", "0")\n'
    '}\n'
)


def self_test() -> int:
    base = {
        PLUGIN_PATH: GOOD_PLUGIN,
        "svc-a/build.gradle.kts": 'plugins { id("openbank.quarkus-service") }',
        "svc-a/src/test/kotlin/ATest.kt": "@QuarkusTest\nclass ATest",
        "lib-b/build.gradle.kts": "tasks.test {\n" + GOOD_PLUGIN.split("{\n", 1)[1],
        "lib-b/src/test/kotlin/BTest.kt": "@QuarkusTest\nclass BTest",
        "svc-a/src/main/resources/application.yaml": "quarkus:\n  http:\n    test-port: 0\n",
        "svc-a/src/test/kotlin/Profile.kt": 'mapOf("quarkus.http.test-port" to "0")',
    }
    cases = {
        "compliant control": ({}, 0),
        "plugin drops the management key": (
            {PLUGIN_PATH: GOOD_PLUGIN.replace('"quarkus.management.test-port", "0"', '"x", "0"')},
            1,
        ),
        "plugin pins a fixed port": ({PLUGIN_PATH: GOOD_PLUGIN.replace('test-port", "0"', 'test-port", "8081"', 1)}, 1),
        "new module without the plugin or the properties": (
            {"svc-c/build.gradle.kts": "plugins { kotlin(\"jvm\") }", "svc-c/src/test/kotlin/C.kt": "@QuarkusTest\nclass C"},
            3,
        ),
        "test profile override pins 8081": ({"svc-a/src/test/kotlin/Profile.kt": 'mapOf("quarkus.http.test-port" to "8081")'}, 1),
        "System.setProperty pins the management port": (
            {"svc-a/src/test/kotlin/Res.kt": 'System.setProperty("quarkus.management.test-port", "9001")'},
            1,
        ),
        "application.yaml pins a fixed test port": (
            {"svc-a/src/main/resources/application.yaml": "quarkus:\n  http:\n    test-port: 8081\n"},
            1,
        ),
        "test resources properties pin a fixed port": (
            {"svc-a/src/test/resources/application.properties": "quarkus.http.test-port=8081\n"},
            1,
        ),
    }
    failed = 0
    for name, (patch, expected) in cases.items():
        findings, subjects = check({**base, **patch})
        ok = len(findings) == expected and subjects >= 2
        failed += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: {len(findings)} finding(s), expected {expected}")
        if not ok:
            for f in findings:
                print(f"       {f}")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, subjects = check(load(Path(args.root)))
    print(f"SUBJECTS={subjects}  # modules with Quarkus tests")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"FAIL: {len(findings)} fixed Quarkus test port(s) or unprotected module(s)")
        return 1
    print("OK: every module with Quarkus tests binds ephemeral HTTP, HTTPS and management test ports")
    return 0


if __name__ == "__main__":
    sys.exit(main())
