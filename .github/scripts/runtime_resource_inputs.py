#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read literal external processResources inputs without evaluating Gradle.

Only tasks.named[<type>]("processResources") { ... } is recognized. Repository
copies use from(rootProject.file("literal")); a repository-root copy requires
static include patterns. Test resources and unrelated string literals are ignored.
Unsupported recognized root reads fail closed, never silently prove equivalence.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from functools import lru_cache
from pathlib import Path

from library_dependency_graph import _tokens

Token = tuple[str, str]
BUILD_FILES = {"build.gradle.kts", "build.gradle"}
REGULAR_MODES = {"100644", "100755"}
SAFE_MODULE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]*\Z")
GIT_OVERRIDES = {"GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR"}


class UnsupportedInputs(ValueError):
    pass


def _end(tokens: list[Token], start: int, opening: str, closing: str) -> int:
    depth = 0
    for index in range(start, len(tokens)):
        if tokens[index] == ("symbol", opening):
            depth += 1
        elif tokens[index] == ("symbol", closing):
            depth -= 1
            if depth == 0:
                return index
    raise UnsupportedInputs(f"unbalanced {opening}{closing} in task declaration")


def _literal_path(value: str, *, pattern: bool = False) -> str:
    if (not value or "$" in value or "\\" in value or any(ord(c) < 32 for c in value)
            or value.startswith("/") or re.match(r"^[A-Za-z]:", value)
            or ".." in value.split("/") or (not pattern and "*" in value)):
        raise UnsupportedInputs("resource input must be a static repository-relative path")
    parts = [part for part in value.split("/") if part not in {"", "."}]
    if pattern and any("**" in part and part != "**" for part in parts):
        raise UnsupportedInputs("recursive '**' must occupy a complete path segment")
    return "/".join(parts) or "."


def _includes(tokens: list[Token], start: int, finish: int, *, required: bool = True) -> set[str] | None:
    patterns: set[str] = set()
    index, depth = start, 0
    while index < finish:
        token = tokens[index]
        if token == ("symbol", "{"):
            depth += 1
        elif token == ("symbol", "}"):
            depth -= 1
        elif depth == 0 and token == ("identifier", "include"):
            if tokens[index + 1:index + 2] != [("symbol", "(")]:
                if not required:
                    return None
                raise UnsupportedInputs("root resource copy needs static include arguments")
            last = _end(tokens, index + 1, "(", ")")
            args = tokens[index + 2:last]
            if (not args or any(t[0] != "string" if i % 2 == 0 else t != ("symbol", ",")
                                for i, t in enumerate(args)) or len(args) % 2 == 0):
                if not required:
                    return None
                raise UnsupportedInputs("root resource copy needs literal include patterns")
            patterns.update(_literal_path(t[1], pattern=True) for t in args[::2])
            index = last
        index += 1
    if not patterns:
        if not required:
            return None
        raise UnsupportedInputs("repository-root resource copy requires bounded static includes")
    if "." in patterns:
        raise UnsupportedInputs("resource include must name a bounded path or pattern")
    return patterns


def _root_aliases(tokens: list[Token]) -> set[str]:
    """Reject simple root-object aliases; do not evaluate their values or paths."""
    declared: set[str] = set()
    origins: list[tuple[str, str]] = []
    for index, token in enumerate(tokens):
        if token not in {("identifier", "val"), ("identifier", "var")}:
            continue
        if index + 1 >= len(tokens) or tokens[index + 1][0] != "identifier":
            continue
        name = tokens[index + 1][1]
        declared.add(name)
        position = index + 2
        if tokens[position:position + 1] == [("symbol", ":")]:
            position += 1
            while position < len(tokens):
                kind, value = tokens[position]
                if value in {"val", "var", "fun", "tasks", "from"}:
                    break
                if kind != "identifier" and (kind, value) not in {
                    ("symbol", c) for c in (".", "<", ">", ",", "?", "[", "]")
                }:
                    break
                position += 1
        if (tokens[position:position + 1] == [("symbol", "=")]
                and position + 1 < len(tokens) and tokens[position + 1][0] == "identifier"):
            origins.append((name, tokens[position + 1][1]))
    # A declared mutable variable may be assigned its root value later.
    for index in range(len(tokens) - 2):
        if (tokens[index][0] == "identifier" and tokens[index][1] in declared
                and tokens[index + 1] == ("symbol", "=")
                and tokens[index + 2][0] == "identifier"
                and (index == 0 or tokens[index - 1] != ("symbol", "."))):
            origins.append((tokens[index][1], tokens[index + 2][1]))
    aliases = {"rootProject"}
    while True:
        expanded = aliases | {name for name, origin in origins if origin in aliases}
        if expanded == aliases:
            return aliases - {"rootProject"}
        aliases = expanded


def _resource_body(tokens: list[Token], start: int, finish: int, aliases: set[str]) -> set[str]:
    patterns: set[str] = set()
    index = start
    while index < finish:
        if tokens[index] == ("identifier", "from") and tokens[index + 1:index + 2] == [("symbol", "(")]:
            last = _end(tokens, index + 1, "(", ")")
            args = tokens[index + 2:last]
            if any(kind == "identifier" and value in aliases for kind, value in args):
                raise UnsupportedInputs("aliased root input in processResources is unsupported")
            root_reference = ("identifier", "rootProject") in args
            if root_reference:
                prefix = [("identifier", "rootProject"), ("symbol", "."),
                          ("identifier", "file"), ("symbol", "(")]
                if (len(args) != 6 or args[:4] != prefix or args[4][0] != "string"
                        or args[5] != ("symbol", ")")):
                    raise UnsupportedInputs("unsupported processResources rootProject input")
                source = _literal_path(args[4][1])
                if source == ".":
                    if tokens[last + 1:last + 2] != [("symbol", "{")]:
                        raise UnsupportedInputs("repository-root copy needs an include block")
                    body_end = _end(tokens, last + 1, "{", "}")
                    if body_end > finish:
                        raise UnsupportedInputs("resource copy escapes its task block")
                    patterns.update(_includes(tokens, last + 2, body_end))
                else:
                    includes = None
                    if tokens[last + 1:last + 2] == [("symbol", "{")]:
                        body_end = _end(tokens, last + 1, "{", "}")
                        if body_end > finish:
                            raise UnsupportedInputs("resource copy escapes its task block")
                        includes = _includes(tokens, last + 2, body_end, required=False)
                    if includes is None:
                        # No static filter: conservatively watch the full source.
                        patterns.add(source)
                    else:
                        # A source may be a directory or file. Keep directory
                        # patterns, and cover the file itself only if its basename
                        # would pass the same include filter; never guess its type.
                        patterns.update(f"{source}/{include}" for include in includes)
                        if any(path_matches(source.rsplit("/", 1)[-1], include) for include in includes):
                            patterns.add(source)
            elif len(args) == 1 and args[0][0] == "string":
                # Non-root module-local copies do not add an external input, but
                # ambiguous escape paths must not masquerade as local resources.
                _literal_path(args[0][1])
            index = last + 1
            continue
        if (tokens[index] == ("identifier", "rootProject")
                or (tokens[index][0] == "identifier" and tokens[index][1] in aliases)):
            raise UnsupportedInputs("unrecognized root-backed value in processResources")
        index += 1
    return patterns


def resource_patterns(source: str) -> set[str]:
    tokens = _tokens(source)
    if tokens is None:
        raise UnsupportedInputs("unreadable Gradle token stream")
    patterns: set[str] = set()
    aliases = _root_aliases(tokens)
    index = 0
    while index < len(tokens):
        if tokens[index:index + 3] != [("identifier", "tasks"), ("symbol", "."), ("identifier", "named")]:
            index += 1
            continue
        position = index + 3
        if tokens[position:position + 1] == [("symbol", "<")]:
            position = _end(tokens, position, "<", ">") + 1
        if tokens[position:position + 1] != [("symbol", "(")]:
            raise UnsupportedInputs("malformed tasks.named declaration")
        last = _end(tokens, position, "(", ")")
        args = tokens[position + 1:last]
        if len(args) != 1 or args[0][0] != "string":
            if tokens[last + 1:last + 2] == [("symbol", "{")]:
                unknown_end = _end(tokens, last + 1, "{", "}")
                if any(kind == "identifier" and value in aliases | {"rootProject"}
                       for kind, value in tokens[last + 2:unknown_end]):
                    raise UnsupportedInputs("dynamic task with root input cannot prove resource ownership")
            index = last + 1
            continue
        if args[0][1] != "processResources":
            index = last + 1
            continue
        if tokens[last + 1:last + 2] != [("symbol", "{")]:
            raise UnsupportedInputs("processResources requires a literal configuration block")
        body_end = _end(tokens, last + 1, "{", "}")
        patterns.update(_resource_body(tokens, last + 2, body_end, aliases))
        index = body_end + 1
    return patterns


def _ant(pattern: str, path: str, *, prefix: bool = False) -> bool:
    pats, parts = pattern.split("/"), path.split("/")

    @lru_cache(maxsize=None)
    def match(i: int, j: int) -> bool:
        if j == len(parts):
            return prefix or all(p == "**" for p in pats[i:])
        if i == len(pats):
            return False
        if pats[i] == "**":
            return match(i + 1, j) or match(i, j + 1)
        segment = re.escape(pats[i]).replace(r"\*", r"[^/]*")
        return re.fullmatch(segment, parts[j]) is not None and match(i + 1, j + 1)

    return match(0, 0)


def path_matches(path: str, pattern: str) -> bool:
    if "*" not in pattern:
        return path == pattern or path.startswith(pattern + "/")
    return _ant(pattern, path)


class SourceTree:
    def __init__(self, root: Path, ref: str | None):
        self.root, self.ref = root, ref
        self.modes: dict[str, str] = {}
        if ref is None:
            self.modules = {p.name for p in root.iterdir() if p.is_dir() and not p.is_symlink() and SAFE_MODULE.fullmatch(p.name)
                            and any((p / name).is_file() for name in BUILD_FILES)}
        else:
            self.ref = self.git("rev-parse", "--verify", "--end-of-options", ref + "^{commit}").decode().strip()
            for entry in self.git("ls-tree", "-r", "-z", self.ref).split(b"\0"):
                if entry:
                    header, name = entry.split(b"\t", 1)
                    self.modes[name.decode("utf-8")] = header.decode().split()[0]
            self.modules = {p.split("/")[0] for p in self.modes
                            if p.count("/") == 1 and SAFE_MODULE.fullmatch(p.split("/")[0]) and p.split("/")[1] in BUILD_FILES}

    def git(self, *args: str) -> bytes:
        env = {key: value for key, value in os.environ.items() if key not in GIT_OVERRIDES}
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.PIPE, env=env)

    def patterns(self, service: str) -> set[str]:
        if service not in self.modules:
            raise UnsupportedInputs(f"not a readable Gradle module: {service}")
        result: set[str] = set()
        for name in sorted(BUILD_FILES):
            path = f"{service}/{name}"
            if self.ref is None:
                source_path = self.root / path
                if not source_path.is_file():
                    continue
                if source_path.is_symlink():
                    raise UnsupportedInputs("symlinked build scripts are unsupported")
                source = source_path.read_text(encoding="utf-8")
            else:
                if path not in self.modes:
                    continue
                if self.modes[path] not in REGULAR_MODES:
                    raise UnsupportedInputs("non-regular build script is unsupported")
                source = self.git("show", f"{self.ref}:{path}").decode("utf-8")
            result.update(resource_patterns(source))
        return result

    def reject_links(self, patterns: set[str]) -> None:
        for path, mode in self.modes.items():
            if mode in {"120000", "160000"} and any(
                    path_matches(path, pattern) or _ant(pattern, path, prefix=True) for pattern in patterns):
                raise UnsupportedInputs(f"resource input traverses an unsupported link: {path}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ref")
    parser.add_argument("--service", action="append", default=[])
    parser.add_argument("--readers")
    parser.add_argument("--compare", action="store_true")
    parser.add_argument("--base")
    parser.add_argument("--head")
    args = parser.parse_args(argv)
    try:
        if args.compare:
            if not args.service or not args.base or not args.head or args.readers is not None or args.ref:
                parser.error("--compare needs --service, --base, --head only")
            before, after = SourceTree(args.root, args.base), SourceTree(args.root, args.head)
            old = {s: before.patterns(s) for s in set(args.service)}
            new = {s: after.patterns(s) for s in set(args.service)}
            union = set().union(*old.values(), *new.values())
            before.reject_links(union)
            after.reject_links(union)
            if old != new:
                print("runtime resource membership changed", file=sys.stderr)
                return 1
            changed = after.git("diff", "--name-only", "--no-renames", "--no-ext-diff", "--no-textconv", "-z", before.ref, after.ref).decode().split("\0")
            for path in changed:
                if path and any(path_matches(path, pattern) for pattern in union):
                    print(f"runtime resource input changed: {path}", file=sys.stderr)
                    return 1
            return 0
        tree = SourceTree(args.root, args.ref)
        if args.readers is not None:
            if args.service or args.base or args.head:
                parser.error("--readers cannot be combined with service/comparison flags")
            paths = [line.strip() for line in sys.stdin if line.strip()]
            for path in paths:
                _literal_path(path)
            selected = []
            for service in sorted(set(args.readers.split()) & tree.modules):
                patterns = tree.patterns(service)
                if any(path_matches(path, pattern) for pattern in patterns for path in paths):
                    selected.append(service)
            print("\n".join(selected), end="\n" if selected else "")
            return 0
        if len(args.service) != 1 or args.base or args.head:
            parser.error("query mode needs exactly one --service")
        patterns = sorted(tree.patterns(args.service[0]))
        print("\n".join(patterns), end="\n" if patterns else "")
        return 0
    except (UnsupportedInputs, OSError, UnicodeError, subprocess.CalledProcessError) as error:
        print(f"runtime-resource-inputs: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
