#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Conservative project-dependency discovery for this repository's Gradle dialect.

This is deliberately not a Kotlin evaluator. Nonliteral/unsupported project
arguments return None; callers must fall back to their full buildable set.
Only direct known dependency configurations and read-only file/layout lookups
are supported. Unknown wrappers and cross-project configuration fail closed.
A successful graph includes modules with no dependencies.
"""

from __future__ import annotations

import re
from pathlib import Path


RUNTIME_CONFIGURATIONS = {"implementation", "api", "runtimeOnly", "compileOnly"}
TEST_CONFIGURATIONS = {"test" + name[0].upper() + name[1:] for name in RUNTIME_CONFIGURATIONS} | {"jmh"}
MODULE = re.compile(r":(openbank-[A-Za-z0-9._-]+)\Z")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
CROSS_PROJECT_SCOPES = {
    "subprojects", "allprojects", "childProjects",
    "getSubprojects", "getAllprojects", "getChildProjects", "getRootProject",
}
ROOT_VALUE_ACCESSORS = {"file", "fileTree", "layout", "projectDir", "name"}


def _tokens(source: str) -> list[tuple[str, str]] | None:
    """Skip comments, preserving strings (including URLs) as opaque tokens."""
    result: list[tuple[str, str]] = []
    pos = 0
    while pos < len(source):
        if source[pos].isspace():
            pos += 1
        elif source.startswith("//", pos):
            end = source.find("\n", pos + 2)
            pos = len(source) if end == -1 else end
        elif source.startswith("/*", pos):
            depth = 1
            pos += 2
            while pos < len(source) and depth:
                if source.startswith("/*", pos):
                    depth += 1
                    pos += 2
                elif source.startswith("*/", pos):
                    depth -= 1
                    pos += 2
                else:
                    pos += 1
            if depth:
                return None
        elif source[pos] in "\"'":
            delimiter = '"""' if source.startswith('"""', pos) else source[pos]
            start = pos + len(delimiter)
            pos = start
            while pos < len(source) and not source.startswith(delimiter, pos):
                if delimiter != '"""' and source[pos] == "\\":
                    pos += 2
                else:
                    pos += 1
            if pos >= len(source):
                return None
            if delimiter == '"""':
                # Kotlin permits a raw string to end with literal quotes: the
                # last three quotes in a closing run are the delimiter.
                while pos + 3 < len(source) and source[pos + 3] == '"':
                    pos += 1
            value = source[start:pos]
            # Kotlin expressions in interpolated strings are outside this grammar.
            if "${" in value and re.search(r"\bproject\s*\(", value):
                return None
            result.append(("string" if delimiter == '"' else "opaque", value))
            pos += len(delimiter)
        else:
            match = IDENTIFIER.match(source, pos)
            if match:
                result.append(("identifier", match.group()))
                pos = match.end()
            else:
                result.append(("symbol", source[pos]))
                pos += 1
    return result


def _declarations(source: str, external_script: bool = False) -> list[tuple[str, str]] | None:
    tokens = _tokens(source)
    if tokens is None:
        return None
    # The root SBOM aggregator enumerates subprojects' tasks without declaring
    # project dependencies. An external script with no project reference cannot
    # add a literal graph edge. Any such reference restores the strict scope
    # check, and external declarations are separately rejected below.
    has_project_reference = any(
        kind == "identifier" and value in {"project", "projects"}
        for kind, value in tokens
    )
    result: list[tuple[str, str]] = []
    for index, token in enumerate(tokens):
        # These scopes change the receiver of nested dependency declarations.
        # Assigning their edges to the file-owning module would under-build.
        if (
            token[0] == "identifier"
            and token[1] in CROSS_PROJECT_SCOPES
            and (not external_script or has_project_reference)
        ):
            return None
        if token == ("identifier", "rootProject"):
            member = tokens[index + 1:index + 3]
            if (
                len(member) != 2
                or member[0] != ("symbol", ".")
                or member[1][0] != "identifier"
                or member[1][1] not in ROOT_VALUE_ACCESSORS
            ):
                return None
        # Type-safe Gradle project accessors require a different graph model.
        if token == ("identifier", "projects") and tokens[index + 1:index + 2] in (
            [("symbol", ".")], [("symbol", "[")],
        ):
            return None
        if token != ("identifier", "project"):
            continue
        if tokens[index + 1:index + 2] in ([("symbol", "`")], [("symbol", "<")]):
            return None
        if tokens[index + 1:index + 2] != [("symbol", "(")]:
            continue
        # A Project object can configure ANOTHER module's dependencies. Never
        # infer an owning-module edge from an alias or a configuration lambda.
        # Only known dependency wrappers and read-only file/layout access are
        # safe without interpreting Kotlin.
        configuration = None
        argument = index + 2
        if tokens[argument:argument + 2] == [("identifier", "path"), ("symbol", "=")]:
            argument += 2
        if argument >= len(tokens) or tokens[argument][0] != "string":
            return None
        match = MODULE.fullmatch(tokens[argument][1])
        if not match or tokens[argument + 1:argument + 2] != [("symbol", ")")]:
            return None
        suffix = tokens[argument + 2:argument + 4]
        if suffix in (
            [("symbol", "."), ("identifier", "file")],
            [("symbol", "."), ("identifier", "layout")],
        ):
            configuration = "read-only"
        if (
            index >= 2
            and tokens[index - 1] == ("symbol", "(")
            and tokens[index - 2][0] == "identifier"
            and tokens[argument + 2:argument + 3] == [("symbol", ")")]
            and (index < 3 or tokens[index - 3] != ("symbol", "."))
        ):
            configuration = tokens[index - 2][1]
            if configuration not in RUNTIME_CONFIGURATIONS | TEST_CONFIGURATIONS | {"detektCli"}:
                return None
        if configuration is None:
            return None
        result.append((configuration, match.group(1)))
    return result


def read_dependencies(root: Path, include_tests: bool) -> dict[str, set[str]] | None:
    """Read literal module edges; None means unknown, never an empty success.

    Project dependencies introduced by root/convention scripts cannot be assigned
    to consumers here and therefore fail closed. The one exact existing detektCli
    tooling dependency is excluded: it affects static analysis, not service/test
    classpaths. Changes to build-logic remain global inputs in the callers.
    """
    scripts = sorted(root.glob("openbank-*/build.gradle.kts"))
    if not scripts:
        return None
    graph: dict[str, set[str]] = {script.parent.name: set() for script in scripts}
    try:
        for script in scripts:
            declarations = _declarations(script.read_text())
            if declarations is None:
                return None
            for configuration, dependency in declarations:
                if dependency not in graph or configuration == "detektCli":
                    return None
                if include_tests or configuration not in TEST_CONFIGURATIONS:
                    graph[script.parent.name].add(dependency)

        outside = [root / "build.gradle.kts", *sorted(root.glob("build-logic/**/*.gradle.kts"))]
        for script in outside:
            if not script.is_file():
                continue
            declarations = _declarations(script.read_text(), external_script=True)
            if declarations is None:
                return None
            for declaration in declarations:
                if (
                    script.relative_to(root).as_posix()
                    != "build-logic/src/main/kotlin/openbank.static-analysis.gradle.kts"
                    or declaration != ("detektCli", "openbank-libs-detekt-rules")
                    or declaration[1] not in graph
                ):
                    return None
    except (OSError, UnicodeError):
        return None
    return graph


def reverse_dependents(graph: dict[str, set[str]], seeds: set[str]) -> set[str]:
    """Include seeds and all transitive consumers; reject unknown graph nodes."""
    if not seeds <= graph.keys() or any(not dependencies <= graph.keys() for dependencies in graph.values()):
        raise ValueError("dependency graph or seeds reference an absent module")
    reverse: dict[str, set[str]] = {name: set() for name in graph}
    for consumer, dependencies in graph.items():
        for dependency in dependencies:
            reverse[dependency].add(consumer)
    reached = set(seeds)
    pending = list(seeds)
    while pending:
        for consumer in reverse[pending.pop()] - reached:
            reached.add(consumer)
            pending.append(consumer)
    return reached
