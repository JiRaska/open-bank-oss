#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A PR that ADDS the claim-fenced outbox v2 migration may only merge while dispatch is paused on main.

Subject: a diff that ADDS `<service>/src/main/resources/db/migration/V*.sql` introducing `claim_token`
on an outbox table (the v2 shape in #15663 / #15665 / #15643). Pass only when, at the BASE
($PR_DIFF_BASE), every GitOps workload running that service's image has OPENBANK_OUTBOX_DISPATCH_ENABLED
effectively false (env literal, else application.yaml `openbank.outbox.dispatch-enabled`, else the
code default false). A pause in the same PR does not count — it is not live when the migration is.
Fail closed: unparseable / valueFrom / no workload found. No label escape.

  python3 .github/scripts/check-outbox-v2-cutover-quiesced.py --base "$PR_DIFF_BASE"
  python3 .github/scripts/check-outbox-v2-cutover-quiesced.py --self-test
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

GIT_ENV = gatelib.hermetic_git_env()

ENV_NAME = "OPENBANK_OUTBOX_DISPATCH_ENABLED"
MIGRATION_RE = re.compile(r"^(openbank-[^/]+)/src/main/resources/db/migration/[^/]+\.sql$")
# Introduces the v2 claim column: `ADD [COLUMN] [IF NOT EXISTS] claim_token` or a CREATE TABLE
# column definition. A later migration that merely INDEXES or mentions it (after the cutover,
# with dispatch re-enabled) is deliberately not a subject.
CLAIM_ADD_RE = re.compile(r'\bADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?"?claim_token"?\b', re.I)
CLAIM_DEF_RE = re.compile(r'^\s*"?claim_token"?\s+(?:uuid|text|varchar)', re.I | re.M)
WORKLOAD_KINDS = {"Rollout", "Deployment", "StatefulSet", "DaemonSet", "CronJob", "Job"}
RUNBOOK = "docs/runbooks/<service>-outbox-v2-cutover.md, step 2 (set OPENBANK_OUTBOX_DISPATCH_ENABLED=false through GitOps and verify old dispatchers are drained)"


def git_out(repo: str, *args: str, check_rc: bool = True) -> str:
    r = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, env=GIT_ENV, check=False)
    if r.returncode != 0 and check_rc:
        raise RuntimeError(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def strip_sql_comments(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.S)
    return re.sub(r"--[^\n]*", "", sql)


def introduces_outbox_v2(sql: str) -> bool:
    body = strip_sql_comments(sql)
    return "outbox" in body.lower() and bool(CLAIM_ADD_RE.search(body) or CLAIM_DEF_RE.search(body))


def truthy(v: object) -> bool | None:
    s = str(v).strip().lower()
    if s in ("true", "1", "yes", "on"):
        return True
    if s in ("false", "0", "no", "off"):
        return False
    return None


def app_default(repo: str, base: str, svc: str) -> bool | None:
    """application.yaml `openbank.outbox.dispatch-enabled` at base; the code default is false."""
    try:
        text = git_out(repo, "show", f"{base}:{svc}/src/main/resources/application.yaml")
    except RuntimeError:
        return False
    node: object = None
    for doc in gatelib.loads_all(text):
        if isinstance(doc, dict):
            node = doc
            break
    for k in ("openbank", "outbox", "dispatch-enabled"):
        if not isinstance(node, dict) or k not in node:
            return False
        node = node[k]
    if isinstance(node, str):
        m = re.fullmatch(r"\$\{[^:}]+:([^}]*)\}", node.strip())
        node = m.group(1) if m else node
    return truthy(node)


def workloads(repo: str, base: str, svc: str) -> list[tuple[str, str, bool | None]]:
    """Every GitOps workload at base running this service's image -> (file, name, effective paused?)."""
    found = []
    default = app_default(repo, base, svc)
    image_re = re.compile(rf"/{re.escape(svc)}[:@]")
    for path in git_out(repo, "ls-tree", "-r", "--name-only", base, "openbank-infra/gitops").splitlines():
        if not path.endswith((".yaml", ".yml")):
            continue
        text = git_out(repo, "show", f"{base}:{path}")
        if not image_re.search(text):
            continue
        for doc in gatelib.loads_all(text):
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            spec = doc.get("spec") or {}
            pod = ((spec.get("jobTemplate") or {}).get("spec") or spec).get("template", {}).get("spec", {})
            for c in pod.get("containers") or []:
                if not image_re.search(str(c.get("image", ""))):
                    continue
                enabled = default
                for e in c.get("env") or []:
                    if e.get("name") == ENV_NAME:
                        enabled = truthy(e["value"]) if "value" in e else None
                found.append((path, (doc.get("metadata") or {}).get("name", "?"), None if enabled is None else not enabled))
    return found


def check(repo: str, base: str, head: str) -> tuple[int, int]:
    base = (base or os.environ.get("PR_DIFF_BASE", "")).strip()
    if not base:
        print("::error::PR_DIFF_BASE is empty but this gate requires it — refusing to run vacuously")
        return 1, 0
    for ref in (base, head):
        if subprocess.run(["git", "-C", repo, "rev-parse", "--verify", "-q", f"{ref}^{{commit}}"],
                          capture_output=True, env=GIT_ENV).returncode != 0:
            print(f"::error::`{ref}` is not a commit in this checkout — refusing to run vacuously")
            return 1, 0
    # Corpus: every service that carries an outbox dispatch switch at HEAD (floor for the manifest).
    corpus = sum(1 for p in git_out(repo, "ls-tree", "-r", "--name-only", head).splitlines()
                 if re.fullmatch(r"openbank-[^/]+/src/main/resources/application\.yaml", p)
                 and "dispatch-enabled" in git_out(repo, "show", f"{head}:{p}"))
    subjects: dict[str, list[str]] = {}
    for line in git_out(repo, "diff", "--name-status", "--diff-filter=A", f"{base}...{head}").splitlines():
        _, _, path = line.partition("\t")
        m = MIGRATION_RE.match(path)
        if m and introduces_outbox_v2(git_out(repo, "show", f"{head}:{path}")):
            subjects.setdefault(m.group(1), []).append(path)
    rc = 0
    for svc, files in sorted(subjects.items()):
        wl = workloads(repo, base, svc)
        if not wl:
            print(f"::error file={files[0]}::{svc}: no GitOps workload running this service's image exists on the base, so "
                  f"a paused dispatcher cannot be proven. Fail closed. Runbook: {RUNBOOK}")
            rc = 1
        for path, name, paused in wl:
            if paused is True:
                print(f"{svc}: {name} ({path}) dispatch is paused on base — ok")
                continue
            why = "dispatch is ENABLED" if paused is False else "dispatch state is not a literal (valueFrom / unparseable)"
            print(f"::error file={files[0]}::{svc}: this PR adds the outbox v2 migration ({files[0]}) but on the base "
                  f"{why} for workload `{name}` ({path}). A v2 migration under a live v1 dispatcher gives a mixed "
                  f"dispatcher window. First merge a GitOps PR setting {ENV_NAME}=\"false\" on {svc}, wait for it to "
                  f"roll, then update this branch. Runbook: {RUNBOOK}. There is no label override.")
            rc = 1
    return rc, corpus


def git(repo: str, *args: str) -> str:
    r = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, check=True, env=GIT_ENV)
    return r.stdout


def write(root: pathlib.Path, rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


SVC = "openbank-foo-service"
V2_SQL = """-- outbox v2
ALTER TABLE foo_outbox
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;
"""
MANIFEST = """apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: foo-service
spec:
  template:
    spec:
      containers:
        - name: foo-service
          image: registry.example.invalid/%s:sandbox-1234
          env:
%s
"""
APP_YAML = "openbank:\n  outbox:\n    dispatch-enabled: %s\n"


def fixture(base_app: str, base_env: str | None, head_files: dict[str, str], head_env: str | None = None) -> tuple[str, str, str]:
    """A tiny repo: base commit carries config, head commit adds `head_files` (and maybe a manifest edit)."""
    root = pathlib.Path(tempfile.mkdtemp(prefix="outbox-v2-fixture-"))
    repo = str(root)
    subprocess.run(["git", "-C", repo, "init", "-q", "-b", "main"], check=True, env=GIT_ENV)

    def env_block(v: str | None) -> str:
        if v is None:
            return "            - name: UNRELATED\n              value: x"
        if v == "valueFrom":
            return ("            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              valueFrom:\n"
                    "                configMapKeyRef: {name: cm, key: k}")
        return f"            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"{v}\""

    write(root, f"{SVC}/src/main/resources/application.yaml", APP_YAML % base_app)
    write(root, "openbank-infra/gitops/components/foo/foo-service.yaml", MANIFEST % (SVC, env_block(base_env)))
    write(root, f"{SVC}/src/main/resources/db/migration/V1__init.sql", "CREATE TABLE foo_outbox (id BIGINT);\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "base")
    base = git(repo, "rev-parse", "HEAD").strip()
    for rel, text in head_files.items():
        write(root, rel, text)
    if head_env is not None:
        write(root, "openbank-infra/gitops/components/foo/foo-service.yaml", MANIFEST % (SVC, env_block(head_env)))
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "head")
    return repo, base, git(repo, "rev-parse", "HEAD").strip()


def self_test() -> int:
    mig = f"{SVC}/src/main/resources/db/migration/V2__outbox_v2.sql"
    unrelated = f"{SVC}/src/main/resources/db/migration/V2__add_note.sql"
    # (label, base app value, base env, head files, head env, expected exit)
    cases = [
        ("v2 migration + dispatch enabled (application.yaml true, no env)", "true", None, {mig: V2_SQL}, None, 1),
        ("v2 migration + env true overrides application.yaml false", "false", "true", {mig: V2_SQL}, None, 1),
        ("v2 migration + env valueFrom cannot be proven paused", "true", "valueFrom", {mig: V2_SQL}, None, 1),
        ("v2 migration + pause only in the SAME PR (not live yet)", "true", None, {mig: V2_SQL}, "false", 1),
        ("v2 migration + dispatch paused by gitops env on base", "true", "false", {mig: V2_SQL}, None, 0),
        ("v2 migration + dispatch paused by application.yaml on base", "false", None, {mig: V2_SQL}, None, 0),
        ("unrelated migration + dispatch enabled is not a subject", "true", None,
         {unrelated: "ALTER TABLE foo ADD COLUMN note TEXT;\n"}, None, 0),
        ("comment-only mention of claim_token is not a subject", "true", None,
         {unrelated: "-- ADD COLUMN claim_token later\nALTER TABLE foo ADD COLUMN note TEXT;\n"}, None, 0),
        ("re-enable PR (config only, no migration) is not a subject", "true", "false", {}, "true", 0),
    ]
    failures = 0
    made: list[str] = []
    for label, app, env, files, head_env, want in cases:
        if not files:
            files = {"README.md": "x\n"}
        repo, base, head = fixture(app, env, files, head_env)
        made.append(repo)
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got, _ = check(repo, base, head)
        ok = got == want
        print(f"  {'ok  ' if ok else 'FAIL'} {label}: exit {got}, want {want}")
        failures += 0 if ok else 1
        if want == 1 and ok and "::error" not in buf.getvalue():
            print(f"  FAIL {label}: failed without an ::error:: annotation")
            failures += 1
    for d in made:
        shutil.rmtree(d, ignore_errors=True)
    print("self-test: " + ("falsifiable" if not failures else f"{failures} FAILED"))
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=".")
    ap.add_argument("--base", default="")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    rc, n = check(args.repo, args.base, args.head)
    gatelib.subjects(n)
    return rc


if __name__ == "__main__":
    sys.exit(main())
