#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Fail closed unless two complete GitHub trees agree on every recorded build input.

The caller is intentionally shallow. This helper uses bounded Git Trees/Blobs API reads,
not the capped compare endpoint and not the checkout's local history.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
REV_RE = re.compile(r"^[0-9a-f]{7,40}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SVC_RE = re.compile(r"^openbank-[A-Za-z0-9_-]+$")
PROJECT_RE = re.compile(r'project\("(:[A-Za-z0-9._-]+)"\)')
MAX_TREE_BYTES = 12 * 1024 * 1024
MAX_TREE_ENTRIES = 50_000
MAX_BLOB_BYTES = 256 * 1024
MAX_BUILD_FILES_PER_SIDE = 12
MAX_API_CALLS = 30  # commit resolution, complete trees, bounded build-file closure reads
MAX_TOTAL_SECONDS = 28
API_TIMEOUT_SECONDS = 6

ROOT_IGNORED_RE = re.compile(
    r"^([^/]+\.md|LICENSE|NOTICE|REUSE\.toml|CITATION\.cff|CODEOWNERS|codecov\.yml|"
    r"\.gitignore|\.gitattributes|\.editorconfig|\.gitleaks\.toml|\.trivyignore|"
    r"\.pre-commit-config\.yaml|release-please-config\.json|\.release-please-manifest\.json)$"
)
IGNORED_TOPS = {
    "docs", "pacts", "perf", "fuzz", "scripts", "LICENSES", "openbank-contracts",
    "openbank-infra", "openbank-admin-ui", "openbank-api-gateway",
    "openbank-developer-portal", "openbank-document-renderer", ".security",
    ".devcontainer", ".clusterfuzzlite",
}
IGNORED_ROOT_FILES = {
    "LICENSE", "NOTICE", "REUSE.toml", "CITATION.cff", "CODEOWNERS", "codecov.yml",
    ".gitignore", ".gitattributes", ".editorconfig", ".gitleaks.toml", ".trivyignore",
    ".pre-commit-config.yaml", "release-please-config.json", ".release-please-manifest.json",
}
VALID_ENTRIES = {
    ("040000", "tree"), ("100644", "blob"), ("100755", "blob"),
    ("120000", "blob"), ("160000", "commit"),
}


class ProofError(Exception):
    pass


@dataclass
class Budget:
    started: float
    calls: int = 0
    bytes_read: int = 0

    def check_time(self) -> None:
        if time.monotonic() - self.started > MAX_TOTAL_SECONDS:
            raise ProofError("proof exceeded its total time budget")


def api(repo: str, endpoint: str, budget: Budget, max_bytes: int) -> dict:
    budget.check_time()
    budget.calls += 1
    if budget.calls > MAX_API_CALLS:
        raise ProofError("proof exceeded its API-call budget")
    try:
        proc = subprocess.run(
            ["gh", "api", f"repos/{repo}/{endpoint}"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=API_TIMEOUT_SECONDS,
            env=os.environ.copy(),
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ProofError(f"GitHub API request failed or timed out ({type(exc).__name__})") from None
    budget.check_time()
    if proc.returncode != 0:
        raise ProofError("GitHub API request failed")
    budget.bytes_read += len(proc.stdout)
    if len(proc.stdout) > max_bytes:
        raise ProofError("GitHub API response exceeded its byte limit")
    if budget.bytes_read > 2 * MAX_TREE_BYTES + MAX_API_CALLS * MAX_BLOB_BYTES:
        raise ProofError("proof exceeded its total response-byte budget")
    try:
        value = json.loads(proc.stdout)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise ProofError("GitHub API returned malformed JSON") from None
    if not isinstance(value, dict):
        raise ProofError("GitHub API response was not an object")
    return value


def validate_tree_hashes(manifest: dict[str, tuple[str, str, str]], root_sha: str) -> None:
    children: dict[str, list[tuple[str, str, str, str]]] = {}
    for path, (mode, kind, sha) in manifest.items():
        parent, _, name = path.rpartition("/")
        if parent and manifest.get(parent, (None, None, None))[1] != "tree":
            raise ProofError(f"tree omits or mis-types parent directory for {path!r}")
        sort_name = name + ("/" if kind == "tree" else "")
        children.setdefault(parent, []).append((sort_name, mode.lstrip("0") or "0", name, sha))

    advertised = {path: sha for path, (mode, kind, sha) in manifest.items() if kind == "tree"}
    advertised[""] = root_sha
    for directory, expected in advertised.items():
        entries = children.get(directory, [])
        entries.sort(key=lambda child: child[0].encode("utf-8"))
        body = bytearray()
        for _sort_name, mode, name, sha in entries:
            body.extend(mode.encode("ascii"))
            body.extend(b" ")
            body.extend(name.encode("utf-8"))
            body.extend(b"\x00")
            body.extend(bytes.fromhex(sha))
        header = b"tree " + str(len(body)).encode("ascii") + b"\x00"
        actual = hashlib.sha1(header + body).hexdigest()
        if actual != expected:
            if directory:
                raise ProofError(f"recursive manifest does not reconstruct tree {directory!r}")
            raise ProofError("recursive manifest does not reconstruct its advertised root tree")


def fetch_tree(repo: str, revision: str, budget: Budget) -> dict[str, tuple[str, str, str]]:
    commit = api(repo, f"git/commits/{revision}", budget, MAX_BLOB_BYTES)
    full_sha = commit.get("sha")
    root = commit.get("tree")
    root_sha = root.get("sha") if isinstance(root, dict) else None
    if not isinstance(full_sha, str) or not SHA_RE.fullmatch(full_sha) or not isinstance(root_sha, str) or not SHA_RE.fullmatch(root_sha):
        raise ProofError(f"revision {revision[:8]} did not resolve to an immutable commit and root tree")
    if not full_sha.startswith(revision):
        raise ProofError("immutable commit response does not match the requested SHA prefix")
    doc = api(repo, f"git/trees/{root_sha}?recursive=1", budget, MAX_TREE_BYTES)
    if doc.get("truncated") is not False or not isinstance(doc.get("tree"), list):
        raise ProofError(f"tree for {revision[:8]} is truncated or malformed")
    advertised_root = doc.get("sha")
    if not isinstance(advertised_root, str) or not SHA_RE.fullmatch(advertised_root) or advertised_root != root_sha:
        raise ProofError("tree response has no valid root object id")
    rows = doc["tree"]
    if len(rows) > MAX_TREE_ENTRIES:
        raise ProofError("tree exceeded its entry limit")
    manifest: dict[str, tuple[str, str, str]] = {}
    for entry in rows:
        if not isinstance(entry, dict):
            raise ProofError("tree contains a malformed entry")
        path, mode, kind, sha = (entry.get(k) for k in ("path", "mode", "type", "sha"))
        if not all(isinstance(v, str) for v in (path, mode, kind, sha)):
            raise ProofError("tree entry lacks path, mode, type, or object id")
        if (mode, kind) not in VALID_ENTRIES or not SHA_RE.fullmatch(sha):
            raise ProofError(f"tree entry has invalid mode/type/object id: {path!r}")
        if (not path or path.startswith("/") or "\\" in path or "\x00" in path
                or any(part in ("", ".", "..") for part in path.split("/"))):
            raise ProofError("tree contains a non-canonical path")
        if path in manifest:
            raise ProofError(f"tree contains duplicate path {path!r}")
        manifest[path] = (mode, kind, sha)
    validate_tree_hashes(manifest, advertised_root)
    return manifest


def blob_text(repo: str, sha: str, budget: Budget, cache: dict[str, str]) -> str:
    if sha in cache:
        return cache[sha]
    doc = api(repo, f"git/blobs/{sha}", budget, MAX_BLOB_BYTES)
    if doc.get("sha") != sha or doc.get("encoding") != "base64" or not isinstance(doc.get("content"), str):
        raise ProofError("build descriptor blob response is malformed")
    try:
        # GitHub pretty-prints base64 with line wrapping in some responses.
        raw = base64.b64decode("".join(doc["content"].split()), validate=True)
        object_header = b"blob " + str(len(raw)).encode("ascii") + b"\x00"
        if hashlib.sha1(object_header + raw).hexdigest() != sha:
            raise ProofError("build descriptor content does not match its immutable blob id")
        decoded = raw.decode("utf-8")
        cache[sha] = decoded
        return decoded
    except (ValueError, UnicodeDecodeError):
        raise ProofError("build descriptor blob is not valid UTF-8 base64") from None


def dependency_modules(repo: str, manifest: dict[str, tuple[str, str, str]], svc: str,
                       budget: Budget, cache: dict[str, str]) -> set[str]:
    module_roots = {
        path[:-len("/build.gradle.kts")]
        for path, (mode, kind, _sha) in manifest.items()
        if path.endswith("/build.gradle.kts") and mode in ("100644", "100755") and kind == "blob"
    }
    if f"{svc}/build.gradle.kts" not in manifest:
        raise ProofError(f"{svc} has no build.gradle.kts in this commit")
    result: set[str] = set()
    pending = [svc]
    read_count = 0
    while pending:
        module = pending.pop()
        if module in result:
            continue
        if module not in module_roots:
            raise ProofError(f"dependency module {module!r} has no build descriptor")
        result.add(module)
        read_count += 1
        if read_count > MAX_BUILD_FILES_PER_SIDE:
            raise ProofError("Gradle dependency closure exceeded its module limit")
        path = f"{module}/build.gradle.kts"
        _mode, _kind, sha = manifest[path]
        text = blob_text(repo, sha, budget, cache)
        deps = {match.group(1)[1:] for match in PROJECT_RE.finditer(text)}
        for dep in sorted(deps):
            if dep not in module_roots:
                raise ProofError(f"{module} declares unknown Gradle dependency {dep!r}")
            pending.append(dep)
    result.update(root for root in module_roots if root.startswith("openbank-libs"))
    return result


def is_excluded(path: str, excludes: list[str]) -> bool:
    return any(path == item or path.startswith(item + "/") for item in excludes)


def root_file_is_relevant(path: str) -> bool:
    return "/" not in path and not ROOT_IGNORED_RE.fullmatch(path)


def classify(path: str, svc: str, modules: set[str], all_modules: set[str], excludes: list[str]) -> str:
    """Return relevant, irrelevant, or unknown under the existing JVM asking scope."""
    if path == svc or path.startswith(svc + "/"):
        return "irrelevant" if is_excluded(path, excludes) else "relevant"
    if path == ".github/workflows/Dockerfile.deploy":
        return "relevant"
    if path == ".github" or path.startswith(".github/"):
        return "irrelevant"  # includes the deliberate auto-deploy.yml exclusion
    if "/" not in path:
        return "relevant" if root_file_is_relevant(path) else "irrelevant"
    top, _rest = path.split("/", 1)
    if top in ("gradle", "config", "build-logic"):
        return "relevant"
    if top in IGNORED_TOPS:
        return "irrelevant"
    if top in modules:
        rest = path[len(top) + 1:]
        if top == svc:
            return "irrelevant" if is_excluded(path, excludes) else "relevant"
        if rest == "build.gradle.kts" or rest.startswith("src/main/") or rest == "src/main":
            return "relevant"
        if rest.startswith("gradle/") or rest == "gradle":
            return "relevant"
        return "irrelevant"  # dependency tests/docs/governance are outside asking scope
    # A non-dependency Gradle module is deliberately known irrelevant. New non-module top-level
    # directories are not: fail closed until classified.
    if top in all_modules:
        return "irrelevant"
    if top in {".github", *IGNORED_TOPS}:
        return "irrelevant"
    return "unknown"


def prove(repo: str, svc: str, published: str, deployed: str, excludes_text: str) -> None:
    if not REPO_RE.fullmatch(repo):
        raise ProofError("GH_REPO is missing or malformed")
    if not SVC_RE.fullmatch(svc) or not REV_RE.fullmatch(published) or not REV_RE.fullmatch(deployed):
        raise ProofError("service or commit argument is malformed")
    if svc == "openbank-admin-ui":
        raise ProofError("non-exact admin-ui equivalence is unsupported without a complete Node/Pact proof")
    excludes = [line for line in excludes_text.splitlines() if line]
    if any("\x00" in p or p.startswith("/") or ".." in p.split("/") for p in excludes):
        raise ProofError("exclude-path configuration is malformed")

    budget = Budget(time.monotonic())
    pub = fetch_tree(repo, published, budget)
    dep = fetch_tree(repo, deployed, budget)
    blob_cache: dict[str, str] = {}
    modules = (dependency_modules(repo, pub, svc, budget, blob_cache)
               | dependency_modules(repo, dep, svc, budget, blob_cache))
    all_modules = {
        path[:-len("/build.gradle.kts")]
        for manifest in (pub, dep)
        for path, (mode, kind, _sha) in manifest.items()
        if path.endswith("/build.gradle.kts") and mode in ("100644", "100755") and kind == "blob"
    }
    # Complete API manifests contain both directory nodes and leaves. Compare every leaf and
    # submodule by path+mode+type+object id; directory hashes are validated but not independently
    # classified because they summarize the child changes already represented by leaves.
    all_paths = pub.keys() | dep.keys()
    changed = sorted(path for path in all_paths if pub.get(path) != dep.get(path)
                     and (pub.get(path, (None, None, None))[1] != "tree"
                          or dep.get(path, (None, None, None))[1] != "tree"))
    # A complete recursive response must explain every changed directory hash with a changed
    # child. Otherwise an empty tree or omitted descendant could hide an input change.
    for path in all_paths:
        before, after = pub.get(path), dep.get(path)
        if before != after and before and after and before[1] == after[1] == "tree":
            if not any(child.startswith(path + "/") for child in changed):
                raise ProofError(f"tree hash changed without a changed descendant: {path}")
    for path in changed:
        disposition = classify(path, svc, modules, all_modules, excludes)
        if disposition == "relevant":
            raise ProofError(f"recorded input differs: {path}")
        if disposition == "unknown":
            raise ProofError(f"changed path is outside the known proof scope: {path}")
    # The supported package itself must exist at both revisions; dependency_modules above checks
    # its marker on each side. A clean result therefore means the manifests covered both roots.
    budget.check_time()


def self_test() -> int:
    fails = 0

    def check(label: str, condition: bool) -> None:
        nonlocal fails
        if condition:
            print(f"  ok   {label}")
        else:
            print(f"  FAIL {label}")
            fails += 1

    def fixture_tree(files: dict[str, tuple[str, str, str]]) -> tuple[dict[str, tuple[str, str, str]], str]:
        manifest = dict(files)
        directories: set[str] = set()
        for path in files:
            parts = path.split("/")[:-1]
            for index in range(1, len(parts) + 1):
                directories.add("/".join(parts[:index]))
        for directory in sorted(directories, key=lambda item: item.count("/"), reverse=True):
            prefix = directory + "/"
            direct = []
            for path, (mode, kind, sha) in manifest.items():
                if not path.startswith(prefix):
                    continue
                name = path[len(prefix):]
                if name and "/" not in name:
                    direct.append((name + ("/" if kind == "tree" else ""), mode, name, sha))
            direct.sort(key=lambda child: child[0].encode("utf-8"))
            body = bytearray()
            for _sort, mode, name, sha in direct:
                body += (mode.lstrip("0") or "0").encode("ascii") + b" " + name.encode() + b"\x00" + bytes.fromhex(sha)
            digest = hashlib.sha1(b"tree " + str(len(body)).encode() + b"\x00" + body).hexdigest()
            manifest[directory] = ("040000", "tree", digest)
        body = bytearray()
        roots = [
            (path + ("/" if kind == "tree" else ""), mode, path, sha)
            for path, (mode, kind, sha) in manifest.items() if "/" not in path
        ]
        roots.sort(key=lambda item: item[0].encode("utf-8"))
        for _sort, mode, name, sha in roots:
            body += (mode.lstrip("0") or "0").encode("ascii") + b" " + name.encode() + b"\x00" + bytes.fromhex(sha)
        root_sha = hashlib.sha1(b"tree " + str(len(body)).encode() + b"\x00" + body).hexdigest()
        return manifest, root_sha

    source_sha = lambda label: hashlib.sha1(label.encode()).hexdigest()
    files = {
        "openbank-ledger-service/build.gradle.kts": ("100644", "blob", source_sha("svc-gradle")),
        "openbank-ledger-service/src/main/Ledger.kt": ("100644", "blob", source_sha("own")),
        "openbank-ledger-service/src/test/LedgerTest.kt": ("100644", "blob", source_sha("test")),
        "openbank-ledger-service/version.txt": ("100644", "blob", source_sha("release")),
        "openbank-libs-runtime/build.gradle.kts": ("100644", "blob", source_sha("runtime-gradle")),
        "openbank-libs-runtime/src/main/Runtime.kt": ("100644", "blob", source_sha("runtime")),
        "openbank-libs-runtime/src/test/RuntimeTest.kt": ("100644", "blob", source_sha("runtime-test")),
        "openbank-libs-domain/build.gradle.kts": ("100644", "blob", source_sha("domain-gradle")),
        "openbank-libs-domain/src/main/Domain.kt": ("100644", "blob", source_sha("domain")),
        "openbank-fx-service/build.gradle.kts": ("100644", "blob", source_sha("fx-gradle")),
        "openbank-fx-service/src/main/Fx.kt": ("100644", "blob", source_sha("fx")),
        "gradle/libs.versions.toml": ("100644", "blob", source_sha("catalog")),
        "build-logic/build.gradle.kts": ("100644", "blob", source_sha("build-logic")),
        "build.gradle.kts": ("100644", "blob", source_sha("root-build")),
        ".github/workflows/Dockerfile.deploy": ("100644", "blob", source_sha("recipe")),
        ".github/workflows/auto-deploy.yml": ("100644", "blob", source_sha("workflow")),
        "docs/README.md": ("100644", "blob", source_sha("docs")),
    }
    manifest, root_sha = fixture_tree(files)
    try:
        validate_tree_hashes(manifest, root_sha)
        valid = True
    except ProofError:
        valid = False
    check("complete manifest reconstructs all tree ids", valid)
    omitted = dict(manifest)
    omitted.pop("openbank-libs-runtime/src/main/Runtime.kt")
    try:
        validate_tree_hashes(omitted, root_sha)
        complete = False
    except ProofError:
        complete = True
    check("omitted leaf cannot pass using unchanged root id", complete)

    modules = {"openbank-ledger-service", "openbank-libs-runtime", "openbank-libs-domain"}
    all_modules = modules | {"openbank-fx-service"}
    excludes = ["openbank-ledger-service/version.txt", "openbank-ledger-service/CHANGELOG.md"]
    expectations = {
        "own source": ("openbank-ledger-service/src/main/Ledger.kt", "relevant"),
        "own test remains in scope": ("openbank-ledger-service/src/test/LedgerTest.kt", "relevant"),
        "release marker exclusion": ("openbank-ledger-service/version.txt", "irrelevant"),
        "transitive shared production": ("openbank-libs-runtime/src/main/Runtime.kt", "relevant"),
        "undeclared common lib production": ("openbank-libs-domain/src/main/Domain.kt", "relevant"),
        "dependency tests stay excluded": ("openbank-libs-runtime/src/test/RuntimeTest.kt", "irrelevant"),
        "unrelated Gradle module stays excluded": ("openbank-fx-service/src/main/Fx.kt", "irrelevant"),
        "global Gradle catalog counts": ("gradle/libs.versions.toml", "relevant"),
        "build logic counts": ("build-logic/build.gradle.kts", "relevant"),
        "root build counts": ("build.gradle.kts", "relevant"),
        "canonical recipe counts": (".github/workflows/Dockerfile.deploy", "relevant"),
        "auto-deploy-only remains excluded": (".github/workflows/auto-deploy.yml", "irrelevant"),
        "known docs stay excluded": ("docs/README.md", "irrelevant"),
        "unknown path refuses": ("new-unknown-dir/file", "unknown"),
    }
    for label, (path, want) in expectations.items():
        check(label, classify(path, "openbank-ledger-service", modules, all_modules, excludes) == want)
    try:
        prove("example/repo", "openbank-admin-ui", "a" * 40, "b" * 40, "")
        unsupported = False
    except ProofError as exc:
        unsupported = "unsupported" in str(exc)
    check("non-exact admin-ui proof is explicitly unsupported", unsupported)
    print("check-recorded-source-manifests: self-test " + ("PASS" if fails == 0 else "FAIL"))
    return 0 if fails == 0 else 1


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "--self-test":
        return self_test()
    if len(argv) != 5:
        print("usage: check-recorded-source-manifests.py <service> <published-sha> <deployed-sha> <excludes>", file=sys.stderr)
        return 2
    svc, published, deployed, excludes = argv[1:]
    try:
        repo = os.environ.get("GH_REPO", "")
        prove(repo, svc, published, deployed, excludes)
    except ProofError as exc:
        print(f"recorded-source proof: {exc}", file=sys.stderr)
        return 1
    print("recorded-source proof: complete manifests agree under the supported Gradle scope", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
