#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Offline integration tests for the deployment-version resolver's complete-tree proof."""
from __future__ import annotations

import json
import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOLVER = ROOT / ".github/scripts/resolve-record-deployment-version.sh"
SERVICE = "openbank-ledger-service"
PUBLISHED_MISSING = "c" * 40


def run(args: list[str], cwd: pathlib.Path, *, env: dict[str, str] | None = None,
        check: bool = True, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=cwd, env=env, check=check, capture_output=True, text=text)


def write(repo: pathlib.Path, path: str, content: str) -> None:
    target = repo / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)


def fixture_scripts(bin_dir: pathlib.Path) -> None:
    bin_dir.mkdir()
    # The environment substitution is deliberately explicit to keep the fake broker offline.
    (bin_dir / "curl").write_text("""#!/usr/bin/env python3
import json, os, sys
if '-w' in sys.argv:
    print('200' if os.environ.get('EXACT') == '1' else '404', end='')
else:
    print(json.dumps({'number': os.environ['PUBLISHED_SHA']}))
""")
    (bin_dir / "gh").write_text("""#!/usr/bin/env python3
import base64, json, os, subprocess, sys
repo = os.environ['TEST_REPO']
case = os.environ.get('FIXTURE_CASE', '')
endpoint = sys.argv[-1]
if case == 'missing-object':
    sys.exit(1)
if case == 'api-failure':
    sys.exit(1)
parts = endpoint.split('/')
try:
    i = parts.index('git')
    kind, oid = parts[i + 1], parts[i + 2].split('?', 1)[0]
except (ValueError, IndexError):
    sys.exit(2)
try:
    if kind == 'commits':
        full = subprocess.check_output(['git', '-C', repo, 'rev-parse', oid + '^{commit}'], text=True).strip()
        if case == 'wrong-commit':
            full = subprocess.check_output(['git', '-C', repo, 'rev-parse', 'HEAD^{commit}'], text=True).strip()
        root = subprocess.check_output(['git', '-C', repo, 'rev-parse', full + '^{tree}'], text=True).strip()
        print(json.dumps({'sha': full, 'tree': {'sha': root}}))
        sys.exit(0)
    if kind == 'blobs':
        data = subprocess.check_output(['git', '-C', repo, 'cat-file', 'blob', oid])
        print(json.dumps({'sha': oid, 'encoding': 'base64', 'content': base64.encodebytes(data).decode()}))
        sys.exit(0)
    if kind != 'trees':
        sys.exit(2)
    recursive = 'recursive=1' in endpoint
    if recursive and case in ('truncated-tree', 'malformed-api'):
        if case == 'malformed-api':
            print('{not-json')
        else:
            print(json.dumps({'sha': '0' * 40, 'truncated': True, 'tree': []}))
        sys.exit(0)
    args = ['git', '-C', repo, 'ls-tree', '-z']
    if recursive:
        args += ['-r', '-t', '--full-tree']
    args += [oid]
    raw = subprocess.check_output(args)
    entries = []
    for row in raw.split(b'\\0'):
        if not row:
            continue
        metadata, path = row.split(b'\\t', 1)
        mode, typ, sha = metadata.decode().split()
        entries.append({'path': path.decode(), 'mode': mode, 'type': typ, 'sha': sha})
    if recursive and case == 'omitted-leaf':
        entries = [e for e in entries if e['path'] != 'openbank-libs-runtime/src/main/Runtime.kt']
    tree_sha = subprocess.check_output(['git', '-C', repo, 'rev-parse', oid + '^{tree}'], text=True).strip()
    print(json.dumps({'sha': tree_sha, 'truncated': False, 'tree': entries}))
except subprocess.CalledProcessError:
    sys.exit(1)
""")
    for script in bin_dir.iterdir():
        script.chmod(0o755)


def init_repo(repo: pathlib.Path) -> str:
    run(["git", "init", "-q", str(repo)], repo.parent)
    run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], repo.parent)
    run(["git", "-C", str(repo), "config", "user.name", "Recorder proof test"], repo.parent)
    run(["git", "-C", str(repo), "config", "commit.gpgsign", "false"], repo.parent)
    files = {
        f"{SERVICE}/build.gradle.kts": 'implementation(project(":openbank-libs-runtime"))\n',
        f"{SERVICE}/src/main/Ledger.kt": "class Ledger\n",
        f"{SERVICE}/src/test/LedgerTest.kt": "class LedgerTest\n",
        f"{SERVICE}/version.txt": "1.0.0\n",
        f"{SERVICE}/CHANGELOG.md": "release one\n",
        "openbank-libs-runtime/build.gradle.kts": 'plugins { kotlin("jvm") }\nimplementation(project(":openbank-contracts"))\n',
        "openbank-libs-runtime/src/main/Runtime.kt": "class RuntimeLib\n",
        "openbank-libs-runtime/src/main/resources/rules.yaml": "enabled: true\n",
        "openbank-libs-runtime/src/test/RuntimeTest.kt": "class RuntimeTest\n",
        "openbank-libs-domain/build.gradle.kts": "plugins { kotlin(\"jvm\") }\n",
        "openbank-libs-domain/src/main/Domain.kt": "class DomainLib\n",
        "openbank-libs-iso20022/build.gradle.kts": "plugins { kotlin(\"jvm\") }\n",
        "openbank-libs-iso20022/src/main/Message.kt": "class MessageLib\n",
        "openbank-contracts/build.gradle.kts": 'plugins { kotlin("jvm") }\n',
        "openbank-contracts/src/main/Contract.kt": "class Contract\n",
        "openbank-contracts/src/test/ContractTest.kt": "class ContractTest\n",
        "openbank-fx-service/build.gradle.kts": "plugins { kotlin(\"jvm\") }\n",
        "openbank-fx-service/src/main/Fx.kt": "class FxService\n",
        "gradle/libs.versions.toml": "kotlin = '2.0.0'\n",
        "gradle/verification-metadata.xml": "<verification-metadata/>\n",
        "build-logic/build.gradle.kts": "plugins {}\n",
        "config/detekt.yml": "build:\n  maxIssues: 0\n",
        "build.gradle.kts": "plugins {}\n",
        "settings.gradle.kts": 'include(":openbank-ledger-service")\n',
        ".github/workflows/Dockerfile.deploy": "FROM eclipse-temurin:21\n",
        ".github/workflows/auto-deploy.yml": "name: auto-deploy\n",
        "docs/README.md": "docs\n",
        "openbank-infra/gitops/app.yaml": "apiVersion: v1\n",
        "README.md": "repo\n",
        "release-please-config.json": json.dumps({"packages": {
            SERVICE: {"exclude-paths": [f"{SERVICE}/src/test"]},
            "openbank-admin-ui": {"exclude-paths": ["openbank-admin-ui/e2e"]},
        }}),
    }
    for path, content in files.items():
        write(repo, path, content)
    run(["git", "-C", str(repo), "add", "-A"], repo.parent)
    run(["git", "-C", str(repo), "commit", "-qm", "fixture base"], repo.parent)
    return run(["git", "-C", str(repo), "rev-parse", "HEAD"], repo.parent).stdout.strip()


def reset(repo: pathlib.Path, base: str) -> None:
    run(["git", "-C", str(repo), "reset", "--hard", base], repo.parent)


def commit_change(repo: pathlib.Path, path: str | None = None, content: str = "changed\n",
                  *, mode: bool = False, extra: list[tuple[str, str]] | None = None) -> str:
    if path:
        if mode:
            target = repo / path
            target.chmod(0o755)
        else:
            write(repo, path, content)
    for extra_path, extra_content in extra or []:
        write(repo, extra_path, extra_content)
    run(["git", "-C", str(repo), "add", "-A"], repo.parent)
    run(["git", "-C", str(repo), "commit", "-qm", "fixture change"], repo.parent)
    return run(["git", "-C", str(repo), "rev-parse", "HEAD"], repo.parent).stdout.strip()


def resolve(repo: pathlib.Path, bin_dir: pathlib.Path, published: str, deployed: str,
            case: str, *, svc: str = SERVICE, exact: bool = False) -> tuple[str, int, str]:
    env = os.environ.copy()
    env.update({
        "PATH": f"{bin_dir}:{env.get('PATH', '/usr/bin:/bin')}",
        "TEST_REPO": str(repo), "FIXTURE_CASE": case,
        "PACT_BROKER_URL": "https://fixture.invalid", "PACT_BROKER_USERNAME": "fixture",
        "PACT_BROKER_PASSWORD": "fixture", "GH_REPO": "fixture/open-bank-oss",
        "PUBLISHED_SHA": published, "EXACT": "1" if exact else "0",
    })
    for inherited_git_var in (
        "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
        "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    ):
        env.pop(inherited_git_var, None)
    result = run(["bash", str(RESOLVER), svc, deployed], repo, env=env, check=False)
    return result.stdout.strip(), result.returncode, result.stderr


def main() -> int:
    failed = []
    results = []
    with tempfile.TemporaryDirectory(prefix="recorded-source-proof-") as temp:
        tmp = pathlib.Path(temp)
        repo, bin_dir = tmp / "repo", tmp / "bin"
        repo.mkdir()
        fixture_scripts(bin_dir)
        base = init_repo(repo)
        cases: list[tuple[str, str, str, str | None, bool, str]] = []
        # Safe boundaries: exact, unchanged after an ignored change, release metadata,
        # unrelated Gradle service, dependency tests, and reviewed irrelevant domains.
        safe_specs = [
            ("unchanged", "README.md", "unrelated docs/repo metadata", False),
            ("release-only", f"{SERVICE}/version.txt", "release metadata", False),
            ("unrelated-service", "openbank-fx-service/src/main/Fx.kt", "independent service", False),
            ("shared-tests-only", "openbank-libs-runtime/src/test/RuntimeTest.kt", "dependency tests", False),
            ("ignored-top-dependency-tests", "openbank-contracts/src/test/ContractTest.kt", "dependency tests", False),
            ("docs", "docs/README.md", "docs", False),
            ("gitops", "openbank-infra/gitops/app.yaml", "gitops", False),
            ("root-markdown", "README.md", "root prose", False),
            ("auto-deploy-only", ".github/workflows/auto-deploy.yml", "deliberate CI exclusion", False),
            ("nondependency-gradle-build", "openbank-fx-service/build.gradle.kts", "independent module", False),
            ("nondependency-gradle-main", "openbank-fx-service/src/main/Fx.kt", "independent module source", False),
            ("config-outside-build", "openbank-infra/gitops/another.yaml", "infra", False),
        ]
        for name, path, content, _mode in safe_specs:
            reset(repo, base)
            deployed = commit_change(repo, path, content + " changed\n")
            cases.append((name, "equivalent", deployed, None, False, ""))
        reset(repo, base)
        # Identical commit is not equivalent because a missing broker record is inconsistent;
        # use an irrelevant root commit to model same recorded source at a distinct SHA.

        # Policy negatives including unsupported or incomplete proof.
        negative_specs = [
            ("own-source", f"{SERVICE}/src/main/Ledger.kt", "own production source", False),
            ("own-test", f"{SERVICE}/src/test/LedgerTest.kt", "own Pact test", False),
            ("shared-production", "openbank-libs-runtime/src/main/Runtime.kt", "dependency production", False),
            ("ignored-top-transitive-production", "openbank-contracts/src/main/Contract.kt", "transitive dependency production", False),
            ("ignored-top-transitive-build", "openbank-contracts/build.gradle.kts", "transitive dependency build", False),
            ("shared-resource", "openbank-libs-domain/src/main/Domain.kt", "shared module", False),
            ("undeclared-shared", "openbank-libs-iso20022/src/main/Message.kt", "undeclared shared lib", False),
            ("gradle-catalog", "gradle/libs.versions.toml", "catalog", False),
            ("gradle-verification", "gradle/verification-metadata.xml", "dependency verification", False),
            ("build-logic", "build-logic/build.gradle.kts", "build logic", False),
            ("root-build", "build.gradle.kts", "root build", False),
            ("runtime-recipe", ".github/workflows/Dockerfile.deploy", "canonical recipe", False),
            ("unknown-path", "new-unknown-dir/file.txt", "unclassified top-level path", False),
            ("release-plus-shared", f"{SERVICE}/version.txt", "release metadata", False),
            ("mode-change", "openbank-libs-runtime/src/main/Runtime.kt", "mode change", True),
        ]
        for name, path, content, is_mode in negative_specs:
            reset(repo, base)
            extra = [("openbank-libs-runtime/src/main/Runtime.kt", "changed shared input\n")] if name == "release-plus-shared" else None
            deployed = commit_change(repo, path, content + " changed\n", mode=is_mode, extra=extra)
            cases.append((name, "none", deployed, None, False, ""))

        # Incomplete/malformed APIs and missing objects must also refuse equivalence.
        for name in ("missing-object", "truncated-tree", "malformed-api", "omitted-leaf", "api-failure", "wrong-commit"):
            reset(repo, base)
            deployed = commit_change(repo, "README.md", name)
            pub = PUBLISHED_MISSING if name == "missing-object" else base
            cases.append((name, "none", deployed, pub, False, ""))

        # Exact broker versions remain accepted before any equivalence proof; non-exact Admin UI
        # is rejected because its complete Node/Pact generator closure is not supported.
        cases.extend([
            ("exact", "exact", base, None, True, SERVICE),
            ("admin-exact", "exact", base, None, True, "openbank-admin-ui"),
        ])
        reset(repo, base)
        admin_deployed = commit_change(repo, "README.md", "admin nonexact\n")
        cases.append(("admin-nonexact", "none", admin_deployed, None, False, "openbank-admin-ui"))

        for name, expected, deployed, pub_override, exact, service in cases:
            pub = pub_override or base
            print(f"checking {name}", flush=True)
            output, rc, err = resolve(repo, bin_dir, pub, deployed, name, svc=service or SERVICE, exact=exact)
            actual = output.split(":", 1)[0] if output else ""
            ok = actual == expected and (rc == 0 if expected in ("none", "exact", "equivalent") else rc != 0)
            results.append({"case": name, "expected": expected, "actual": actual, "ok": ok})
            if not ok:
                failed.append(f"{name}: expected {expected}, got {output!r} rc={rc}; stderr={err[-300:]!r}")

        # No record-deployment command is invoked by the resolver. The production caller must
        # continue before its CLI record on the `none` arm; pin this source-level control flow.
        workflow = (ROOT / ".github/workflows/record-deployment-on-merge.yml").read_text()
        none_arm = workflow.find("*)            echo \"::warning::${svc}: no published version")
        record_call = workflow.find('"$CLI" record-deployment', none_arm)
        if none_arm < 0 or record_call < 0 or "continue ;;" not in workflow[none_arm:record_call]:
            failed.append("caller does not continue past record-deployment when resolver returns none")

    for row in results:
        print(f"{row['case']}: {'PASS' if row['ok'] else 'FAIL'} expected={row['expected']} actual={row['actual']}")
    print(f"summary: {sum(row['ok'] for row in results)}/{len(results)} cases passed")
    for error in failed:
        print(error, file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
