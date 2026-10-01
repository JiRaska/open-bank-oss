#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A service that asks for JSON console logging must carry the extension that provides it.

WHY THIS EXISTS
---------------
`quarkus.log.console.json` is read by exactly one thing: the `quarkus-logging-json` extension.
Without that extension on the application's classpath the key is still accepted — no warning,
no boot failure — and the console silently keeps the TEXT pattern formatter
(`quarkus.log.console.format`). The configuration then describes structured logging that the
process does not do, and every reader of the config (a reviewer, a dashboard author, the log
pipeline's parser) is reasoning about output that does not exist. The fleet ran that way: the
shared library config and ~30 services declared `json: true`, and no module depended on the
extension.

A config key that does nothing cannot be noticed from the config, so this gate checks the
pairing instead:

    a Quarkus application module REQUESTS structured console logging
        (its own resources, or the resources of a project it depends on, set
         `quarkus.log.console.json` true or configure any `quarkus.log.console.json.*` key)
    ==>
    that module DECLARES `io.quarkus:quarkus-logging-json`
        (in its own build file, through a version-catalog alias, or through a convention
         plugin under build-logic/ that it applies)

WHAT IT DOES NOT COVER
----------------------
It reads build files, not a resolved classpath: a dependency that is declared and then excluded
by a resolution rule would read as present. `StructuredLogEncodingIT` in
openbank-ledger-service is the by-effect half — it boots the application and asserts the
console handler's formatter really is the JSON one.

Usage:  check-json-logging-extension.py [--root DIR] [--enforce] [--self-test]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

EXTENSION = "quarkus-logging-json"
JSON_KEY = "quarkus.log.console.json"
SWITCH_SUFFIXES = ("", ".enabled", ".enable")
RESOURCE_FILES = (
    "src/main/resources/application.yaml",
    "src/main/resources/application.yml",
    "src/main/resources/application.properties",
    "src/main/resources/META-INF/microprofile-config.properties",
)
QUARKUS_PLUGIN = re.compile(r'id\("io\.quarkus"\)|alias\(libs\.plugins\.quarkus\)')
PLUGIN_ID = re.compile(r'id\("([A-Za-z0-9_.\-]+)"\)')
PROJECT_DEP = re.compile(r'project\("(:[A-Za-z0-9_.\-]+)"\)')
CATALOG_ALIAS = re.compile(r'^\s*([A-Za-z0-9_\-]+)\s*=\s*\{[^}]*module\s*=\s*"io\.quarkus:' + EXTENSION + '"', re.M)


def strip_comments(text: str) -> str:
    """Drop `//` line comments and `/* */` blocks, so prose naming the artifact is not a declaration."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"(^|\s)//.*$", "", line) for line in text.splitlines())


def flatten(node: object, prefix: str = "") -> dict[str, object]:
    out: dict[str, object] = {}
    if isinstance(node, dict):
        for key, value in node.items():
            out.update(flatten(value, f"{prefix}.{key}" if prefix else str(key)))
    else:
        out[prefix] = node
    return out


def config_keys(path: pathlib.Path) -> list[tuple[str, object]]:
    """`(key, value)` pairs of one config file, with any `%profile.` prefix removed.

    A list, not a dict: once the profile prefix is gone `quarkus.log.console.json` and
    `%dev.quarkus.log.console.json` are the same key, and a dict would keep only the last —
    which is how a service with `json: true` and a `%dev` `json: false` read as not asking.
    """
    if path.suffix in (".yaml", ".yml"):
        pairs = list(flatten(gatelib.load_yaml(path) or {}).items())
    else:
        pairs = []
        for line in gatelib.read_text(path).splitlines():
            line = line.strip()
            if line and not line.startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                pairs.append((key.strip(), value.strip()))
    return [(re.sub(r'^"?%[^.]+"?\.', "", key), value) for key, value in pairs]


def requests_json(module: pathlib.Path) -> str | None:
    """The `file: key` that asks this module for JSON console logging, or None."""
    for rel in RESOURCE_FILES:
        path = module / rel
        if not path.is_file():
            continue
        for key, value in config_keys(path):
            if not key.startswith(JSON_KEY):
                continue
            suffix = key[len(JSON_KEY):]
            if suffix in SWITCH_SUFFIXES:
                if str(value).strip().lower() == "true":
                    return f"{rel}: {key}"
            elif suffix.startswith("."):
                return f"{rel}: {key}"
    return None


def catalog_accessors(root: pathlib.Path) -> list[str]:
    accessors = []
    for toml in sorted(root.glob("*/gradle/libs.versions.toml")) + sorted(root.glob("gradle/libs.versions.toml")):
        for alias in CATALOG_ALIAS.findall(gatelib.read_text(toml)):
            accessors.append("libs." + re.sub(r"[-_]", ".", alias))
    return accessors


def declares_extension(build_text: str, root: pathlib.Path, accessors: list[str]) -> bool:
    code = strip_comments(build_text)
    if EXTENSION in code or any(accessor in code for accessor in accessors):
        return True
    for plugin in PLUGIN_ID.findall(code):
        convention = root / "build-logic/src/main/kotlin" / f"{plugin}.gradle.kts"
        if convention.is_file() and EXTENSION in strip_comments(gatelib.read_text(convention)):
            return True
    return False


def analyse(root: pathlib.Path) -> tuple[list[str], int]:
    """(findings, number of Quarkus application modules examined)."""
    builds = {p.parent.name: gatelib.read_text(p) for p in sorted(root.glob("*/build.gradle.kts"))}
    accessors = catalog_accessors(root)
    requested = {name: requests_json(root / name) for name in builds}

    def inherited(name: str, seen: set[str]) -> str | None:
        if name in seen or name not in builds:
            return None
        seen.add(name)
        if requested[name]:
            return f"{name}/{requested[name]}"
        for dep in PROJECT_DEP.findall(strip_comments(builds[name])):
            via = inherited(dep.lstrip(":"), seen)
            if via:
                return via
        return None

    findings: list[str] = []
    applications = 0
    for name, text in builds.items():
        code = strip_comments(text)
        convention_is_quarkus = any(
            (root / "build-logic/src/main/kotlin" / f"{plugin}.gradle.kts").is_file()
            and QUARKUS_PLUGIN.search(strip_comments(gatelib.read_text(root / "build-logic/src/main/kotlin" / f"{plugin}.gradle.kts")))
            for plugin in PLUGIN_ID.findall(code)
        )
        if not (QUARKUS_PLUGIN.search(code) or convention_is_quarkus):
            continue
        applications += 1
        source = inherited(name, set())
        if source and not declares_extension(text, root, accessors):
            findings.append(
                f"::error file={name}/build.gradle.kts::{name} asks for JSON console logging "
                f"({source}) but does not declare io.quarkus:{EXTENSION}. Without the extension "
                "the key is inert and the console keeps the text pattern formatter. Apply the "
                "openbank.quarkus-service convention plugin or declare the extension directly."
            )
    return findings, applications


def write(root: pathlib.Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def self_test() -> int:
    service = 'plugins {\n    id("openbank.quarkus-service")\n}\ndependencies {\n    implementation(project(":openbank-libs-runtime"))\n}\n'
    convention_with = 'plugins {\n    id("io.quarkus")\n}\ndependencies {\n    "implementation"("io.quarkus:quarkus-logging-json")\n}\n'
    convention_without = 'plugins {\n    id("io.quarkus")\n}\n// io.quarkus:quarkus-logging-json is mentioned only in this comment\n'
    lib_config = "quarkus.log.console.json=true\n"
    failures: list[str] = []

    def case(label: str, files: dict[str, str], expect_flagged: list[str], expect_apps: int) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for rel, text in files.items():
                write(root, rel, text)
            findings, apps = analyse(root)
            flagged = sorted(re.search(r"file=([^/]+)/", f).group(1) for f in findings)
            if flagged != sorted(expect_flagged) or apps != expect_apps:
                failures.append(f"{label}: flagged={flagged} apps={apps}, expected {sorted(expect_flagged)} / {expect_apps}")

    lib = {
        "openbank-libs-runtime/build.gradle.kts": "plugins {\n    `java-library`\n}\n",
        "openbank-libs-runtime/src/main/resources/META-INF/microprofile-config.properties": lib_config,
    }
    # The defect itself, both ways a service can ask: inherited from the shared library, and own YAML.
    case("inherited request, extension absent", {
        **lib,
        "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts": convention_without,
        "openbank-a-service/build.gradle.kts": service,
    }, ["openbank-a-service"], 1)
    case("own yaml request under a profile, extension absent", {
        "openbank-b-service/build.gradle.kts": 'plugins {\n    id("io.quarkus")\n}\n',
        "openbank-b-service/src/main/resources/application.yaml": '"%prod":\n  quarkus:\n    log:\n      console:\n        json: true\n',
    }, ["openbank-b-service"], 1)
    case("json true with a dev-profile json false is still a request", {
        "openbank-e-service/build.gradle.kts": 'plugins {\n    id("io.quarkus")\n}\n',
        "openbank-e-service/src/main/resources/application.yaml":
            'quarkus:\n  log:\n    console:\n      json: true\n"%dev":\n  quarkus:\n    log:\n      console:\n        json: false\n',
    }, ["openbank-e-service"], 1)
    case("sub-key configured, extension absent", {
        "openbank-c-service/build.gradle.kts": 'plugins {\n    id("io.quarkus")\n}\n',
        "openbank-c-service/src/main/resources/application.properties": "quarkus.log.console.json.pretty-print=false\n",
    }, ["openbank-c-service"], 1)
    # The three accepted declarations.
    case("extension through the convention plugin", {
        **lib,
        "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts": convention_with,
        "openbank-a-service/build.gradle.kts": service,
    }, [], 1)
    case("extension declared directly", {
        **lib,
        "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts": convention_without,
        "openbank-a-service/build.gradle.kts": service + 'dependencies {\n    implementation("io.quarkus:quarkus-logging-json")\n}\n',
    }, [], 1)
    case("extension through a catalog alias", {
        **lib,
        "openbank-libs/gradle/libs.versions.toml": '[libraries]\nquarkus-logging-json = { module = "io.quarkus:quarkus-logging-json" }\n',
        "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts": convention_without,
        "openbank-a-service/build.gradle.kts": service + "dependencies {\n    implementation(libs.quarkus.logging.json)\n}\n",
    }, [], 1)
    # Not a finding: JSON explicitly off, and a library (not an application) that asks for it.
    case("json false is not a request; a library is not a subject", {
        **lib,
        "openbank-d-service/build.gradle.kts": 'plugins {\n    id("io.quarkus")\n}\n',
        "openbank-d-service/src/main/resources/application.yaml": "quarkus:\n  log:\n    console:\n      json: false\n",
    }, [], 1)
    # A commented-out declaration is not a declaration.
    case("declaration only in a comment", {
        **lib,
        "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts": convention_without,
        "openbank-a-service/build.gradle.kts": service + '// implementation("io.quarkus:quarkus-logging-json")\n',
    }, ["openbank-a-service"], 1)

    if failures:
        for failure in failures:
            print(f"self-test FAIL: {failure}")
        return 1
    print("self-test OK: 9 cases — four absent-extension shapes flagged, three declarations accepted, "
          "json=false and a comment-only declaration handled")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, applications = analyse(pathlib.Path(args.root))
    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    print(f"[json-logging-extension] {applications} Quarkus application modules examined, "
          f"{len(findings)} asking for JSON console logging without the extension")
    gatelib.subjects(applications, "Quarkus application modules examined")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
