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
from concurrent.futures import ThreadPoolExecutor
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

GIT_ENV = {**gatelib.hermetic_git_env(), "GIT_CONFIG_COUNT": "2", "GIT_CONFIG_KEY_0": "core.fsync",
           "GIT_CONFIG_VALUE_0": "none", "GIT_CONFIG_KEY_1": "gc.auto", "GIT_CONFIG_VALUE_1": "0"}

ENV_NAME = "OPENBANK_OUTBOX_DISPATCH_ENABLED"
# Per-service override of the env key, for the rare service whose dispatcher switch is not the
# fleet-standard property. Add a line here (with the reason) when a service's own
# @ConfigProperty / manifest uses another name; otherwise the fleet key applies.
ENV_KEYS = {
    "openbank-pricing-service": "PRICING_OUTBOX_DISPATCH_ENABLED",  # pricing/workloads.yaml; its literal QUARKUS_PROFILE is resolvable
}


def env_name_for(svc: str) -> str:
    return ENV_KEYS.get(svc, ENV_NAME)


# Any added file under db/migration/ (any depth, any case). Renames count: a moved v2 file is still v2.
MIGRATION_RE = re.compile(r"^(openbank-[^/]+)/src/main/resources/db/migration/.+\.(sql|java|kt)$", re.I)
CLAIM_ADD_RE = re.compile(r'\bADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?"?claim_token"?(?![\w])', re.I)
CLAIM_RENAME_RE = re.compile(r'\bRENAME\s+(?:COLUMN\s+)?"?\w+"?\s+TO\s+"?claim_token"?(?![\w])', re.I)
CLAIM_TOKEN_RE = re.compile(r'(?<![\w])"?claim_token"?(?![\w])', re.I)
WORKLOAD_KINDS = {"Rollout", "Deployment", "StatefulSet", "DaemonSet", "CronJob", "Job"}
RUNBOOK = "docs/runbooks/<service>-outbox-v2-cutover.md, step 2 (set the dispatch switch to false through GitOps, roll it out to every pod and let in-flight claims drain; this gate checks only the DECLARED state on the base, so verify the rollout yourself before arming)"


def git_out(repo: str, *args: str, check_rc: bool = True) -> str:
    r = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, env=GIT_ENV, check=False)
    if r.returncode != 0 and check_rc:
        raise RuntimeError(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def sql_statements(sql: str) -> list[str]:
    """Split SQL into statements with comments removed. String-literal and quoted-identifier aware:
    `--`, `/*` and `;` inside '...' or "..." are data, not syntax."""
    out: list[str] = []
    cur: list[str] = []
    i, n, quote, esc = 0, len(sql), "", False
    while i < n:
        ch = sql[i]
        if quote:
            cur.append(ch)
            if esc and ch == "\\" and i + 1 < n:  # E'...' string: backslash escapes the next char
                cur.append(sql[i + 1])
                i += 1
            elif ch == quote:
                if i + 1 < n and sql[i + 1] == quote:  # doubled quote escape
                    cur.append(sql[i + 1])
                    i += 1
                else:
                    quote = ""
        elif ch in "'\"":
            quote = ch
            esc = ch == "'" and bool(cur) and cur[-1] in "eE" and (len(cur) < 2 or not (cur[-2].isalnum() or cur[-2] == "_"))
            cur.append(ch)
        elif sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j - 1
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j < 0 else j + 1
            cur.append(" ")
        elif ch == ";":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur))
    return [x.strip() for x in out if x.strip()]


def introduces_outbox_v2(sql: str) -> bool:
    """True when ONE statement both names an outbox table and introduces claim_token as a column
    (ADD [COLUMN], RENAME ... TO, or a CREATE TABLE definition, any type, any schema qualifier).
    An index or DROP naming it, and a claim_token elsewhere in the file, are not the introduction
    (they match none of the three shapes below)."""
    for st in sql_statements(sql):
        low = st.lower()
        if "outbox" not in low:
            continue
        if re.match(r"create\s+(?:\w+\s+)*table\b", low):
            if CLAIM_TOKEN_RE.search(st):
                return True
        elif CLAIM_ADD_RE.search(st) or CLAIM_RENAME_RE.search(st):
            return True
    return False


def truthy(v: object) -> bool | None:
    s = str(v).strip().lower()
    if s in ("true", "1", "yes", "on"):
        return True
    if s in ("false", "0", "no", "off"):
        return False
    return None


PROP = "dispatch-enabled"
ABSENT = "ABSENT: "
# Env names that can re-point config resolution; any presence on the container is UNKNOWN.
PROFILE_ENV = {"QUARKUS_PROFILE", "SMALLRYE_CONFIG_PROFILE"}
CONFIG_ENV = {"QUARKUS_PROFILE", "SMALLRYE_CONFIG_PROFILE", "SMALLRYE_CONFIG_LOCATIONS",
              "QUARKUS_CONFIG_LOCATIONS", "SMALLRYE_CONFIG_PROFILE_PARENT", "QUARKUS_CONFIG_PROFILE_PARENT"}
JVM_ENV = {"JAVA_TOOL_OPTIONS", "JAVA_OPTS", "JAVA_OPTS_APPEND", "JDK_JAVA_OPTIONS", "JAVA_OPTIONS"}
PATCH_KEYS = ("patches", "patchesStrategicMerge", "patchesJson6902", "replacements", "images", "transformers")


def mentions_prop(node: object) -> bool:
    if isinstance(node, dict):
        return any(PROP in str(k) or mentions_prop(v) for k, v in node.items())
    if isinstance(node, list):
        return any(mentions_prop(v) for v in node)
    return False


def app_default(repo: str, base: str, svc: str) -> tuple[bool | None, str]:
    """application.yaml `openbank.outbox.dispatch-enabled` at base -> (value, why-unknown).

    UNKNOWN (None) on EVERY unresolvable case: file missing/unreadable/unparseable, key absent, a
    non-literal value, any `%<profile>` section touching the property, or application.properties
    mentioning it. Only an explicit literal in the default section is a value."""
    res = f"{svc}/src/main/resources"
    try:
        text = git_out(repo, "show", f"{base}:{res}/application.yaml")
        docs = [d for d in gatelib.loads_all(text) if isinstance(d, dict)]
    except Exception as exc:  # noqa: BLE001 — any failure to read is UNKNOWN, never "paused"
        return None, f"application.yaml missing or unreadable at base ({type(exc).__name__})"
    if not docs:
        return None, "application.yaml has no mapping"
    for doc in docs:
        for k, v in doc.items():
            if str(k).startswith("%") and mentions_prop(v):
                return None, f"profile section `{k}` sets {PROP}"
            if str(k).startswith("%") and PROP in str(k):
                return None, f"profile key `{k}` sets {PROP}"
    try:
        props = git_out(repo, "show", f"{base}:{res}/application.properties")
        if PROP in props:
            return None, "application.properties mentions dispatch-enabled"
    except RuntimeError:
        pass
    for fn in git_out(repo, "ls-tree", "--name-only", base, f"{res}/", check_rc=False).splitlines():
        if re.fullmatch(r"application-[^/]+\.(ya?ml|properties)", fn.rsplit("/", 1)[-1]) and \
                PROP in git_out(repo, "show", f"{base}:{fn}", check_rc=False):
            return None, f"profile-specific {fn.rsplit('/', 1)[-1]} mentions {PROP}"
    value: object = None
    for doc in docs:
        node: object = doc
        flat = doc.get(f"openbank.outbox.{PROP}")
        if flat is not None:
            value = flat
            continue
        for k in ("openbank", "outbox", PROP):
            if not isinstance(node, dict) or k not in node:
                node = None
                break
            node = node[k]
        if node is not None:
            value = node
    if value is None:
        return None, f"{ABSENT}application.yaml does not set openbank.outbox.{PROP}"
    if isinstance(value, str):
        m = re.fullmatch(r"\$\{([^:}]+):([^}]*)\}", value.strip())
        if m:
            return None, f"application.yaml value is an expression (${{{m.group(1)}:...}}), not a literal"
    t = truthy(value)
    return t, "" if t is not None else "application.yaml value is not a boolean literal"


def container_taint(c: dict, env_name: str = ENV_NAME) -> str:
    """A reason another source could override the switch, or '' when none."""
    if c.get("envFrom"):
        return "container has envFrom"
    for arg in [*(c.get("command") or []), *(c.get("args") or [])]:
        if "-D" in str(arg) or "dispatch" in str(arg).lower():
            return "container command/args carry a -D / dispatch override"
    for e in c.get("env") or []:
        name = str(e.get("name", ""))
        val = str(e.get("value", ""))
        if name in PROFILE_ENV and "value" in e and str(e["value"]).strip() and "," not in str(e["value"]):
            continue  # a literal profile is resolvable; app_default vetoes any profile-specific dispatch config
        if name in CONFIG_ENV:
            return f"env {name} changes profile/config location"
        if name in JVM_ENV and ("-D" in val or PROP in val or "dispatch" in val.lower()):
            return f"env {name} carries a -D / dispatch override"
        if name != env_name and (PROP in val or "dispatch-enabled" in name.lower()
                                 or name.upper().endswith("DISPATCH_ENABLED")):
            return f"env {name} mentions {PROP}"
    return ""


def kustomize_taint(repo: str, base: str, path: str) -> str:
    parts = path.split("/")[:-1]
    while len(parts) >= 2:
        for fn in ("kustomization.yaml", "kustomization.yml"):
            try:
                text = git_out(repo, "show", f"{base}:{'/'.join(parts)}/{fn}")
            except RuntimeError:
                continue
            try:
                docs = [d for d in gatelib.loads_all(text) if isinstance(d, dict)]
            except Exception:  # noqa: BLE001
                return f"{'/'.join(parts)}/{fn} is unparseable"
            if any(k in d for d in docs for k in PATCH_KEYS):
                return f"{'/'.join(parts)}/{fn} patches/overlays the workload"
        parts.pop()
    return ""


def workloads(repo: str, base: str, svc: str) -> list[tuple[str, str, bool | None, str]]:
    """Every GitOps workload at base running this service's image -> (file, name, paused?, why-unknown).

    paused is True ONLY for a proven pause: the container carries the literal env
    the per-service dispatch env (OPENBANK_OUTBOX_DISPATCH_ENABLED by default) =false, or no env sets it and application.yaml literally says
    false — and in both cases nothing else could override it. Everything else is None (UNKNOWN,
    which fails the gate) or False (enabled)."""
    found = []
    default, default_why = app_default(repo, base, svc)
    env_name = env_name_for(svc)
    image_re = re.compile(rf"/{re.escape(svc)}[:@]")
    # ONE grep over the base tree finds the files naming this image; parsing every manifest (1000+)
    # one `git show` at a time took minutes against a 20 s budget.
    sha = git_out(repo, "rev-parse", base).strip()
    hits = git_out(repo, "grep", "-l", "-E", rf"/{svc}[:@]", sha, "--", "openbank-infra/gitops", check_rc=False)
    for line in sorted(hits.splitlines()):
        path = line[len(sha) + 1:]
        if not path.endswith((".yaml", ".yml")):
            continue
        text = git_out(repo, "show", f"{base}:{path}")
        for doc in gatelib.loads_all(text):
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            spec = doc.get("spec") or {}
            pod = ((spec.get("jobTemplate") or {}).get("spec") or spec).get("template", {}).get("spec", {})
            for c in pod.get("containers") or []:
                if not image_re.search(str(c.get("image", ""))):
                    continue
                name = (doc.get("metadata") or {}).get("name", "?")
                taint = container_taint(c, env_name) or kustomize_taint(repo, base, path)
                if taint:
                    found.append((path, name, None, taint))
                    continue
                soft = default is None and default_why.startswith(ABSENT)
                if default is None and not soft:
                    # Missing/unreadable yaml, profile section, properties file, expression: something
                    # other than the container env could decide the value, so stay UNKNOWN.
                    found.append((path, name, None, default_why))
                    continue
                env_val: object = None
                unresolvable = False
                for e in c.get("env") or []:
                    if e.get("name") == env_name:
                        if "value" in e:
                            env_val, unresolvable = e["value"], False
                        else:
                            env_val, unresolvable = None, True
                if unresolvable:
                    found.append((path, name, None, f"{env_name} comes from valueFrom, not a literal"))
                    continue
                if soft and env_val is None:
                    found.append((path, name, None, default_why + f" and no literal {env_name} on the container"))
                    continue
                enabled = default if env_val is None else truthy(env_val)
                if enabled is None:
                    found.append((path, name, None, f"{env_name} value `{env_val}` is not a boolean literal"))
                else:
                    found.append((path, name, not enabled, ""))
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
    hsha = git_out(repo, "rev-parse", head).strip()
    corpus = sum(1 for ln in git_out(repo, "grep", "-l", "dispatch-enabled", hsha, "--",
                                     "openbank-*/src/main/resources/application.yaml", check_rc=False).splitlines() if ln)
    subjects: dict[str, list[str]] = {}
    unknown: dict[str, list[str]] = {}
    # A/R/C: an added, renamed or copied file is new at this path; a moved v2 migration is still v2.
    for line in git_out(repo, "diff", "--name-status", "-M", "-C", f"{base}...{head}").splitlines():
        cols = line.split("\t")
        if cols[0][:1] not in ("A", "R", "C"):
            continue
        path = cols[-1]
        m = MIGRATION_RE.match(path)
        if not m:
            continue
        if path.lower().endswith(".sql"):
            if introduces_outbox_v2(git_out(repo, "show", f"{head}:{path}")):
                subjects.setdefault(m.group(1), []).append(path)
        else:  # Java/Kotlin Flyway migration: its SQL is invisible to this gate
            unknown.setdefault(m.group(1), []).append(path)
    rc = 0
    for svc, files in sorted(unknown.items()):
        print(f"::error file={files[0]}::{svc}: this PR adds a Java/Kotlin Flyway migration ({files[0]}), whose SQL "
              f"this gate cannot read. Only SQL v2 migrations can be verified, so a Java/Kotlin migration always fails "
              f"closed: provide an SQL equivalent (pausing dispatch does not help here).")
        rc = 1
    for svc, files in sorted(subjects.items()):
        wl = workloads(repo, base, svc)
        if not wl:
            print(f"::error file={files[0]}::{svc}: no GitOps workload running this service's image exists on the base, so "
                  f"a paused dispatcher cannot be proven. Fail closed. Runbook: {RUNBOOK}")
            rc = 1
        for path, name, paused, unk in wl:
            if paused is True:
                print(f"{svc}: {name} ({path}) dispatch is paused on base — ok")
                continue
            why = "dispatch is ENABLED" if paused is False else f"a paused dispatcher cannot be PROVEN ({unk}) — unknown is treated as enabled"
            print(f"::error file={files[0]}::{svc}: this PR adds the outbox v2 migration ({files[0]}) but on the base: "
                  f"{why} for workload `{name}` ({path}). A v2 migration under a live v1 dispatcher gives a mixed "
                  f"dispatcher window. First merge a GitOps PR setting {env_name_for(svc)}=\"false\" on {svc}, wait for it to "
                  f"roll out to every pod and for in-flight claims to drain (this gate checks the DECLARED state only), then update this branch. Runbook: {RUNBOOK}. There is no label override.")
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
%s"""
APP_YAML = "openbank:\n  outbox:\n    dispatch-enabled: %s\n"


def fixture(base_app: str | None, base_env: str | None, head_files: dict[str, str], head_env: str | None = None,
            extra: str = "", app_text: str | None = None, kustomization: str | None = None,
            base_files: dict[str, str] | None = None, rename: dict[str, str] | None = None) -> tuple[str, str, str]:
    """A tiny repo: base commit carries config, head commit adds `head_files` (and maybe a manifest edit)."""
    root = pathlib.Path(tempfile.mkdtemp(prefix="outbox-v2-fixture-"))
    repo = str(root)
    subprocess.run(["git", "-C", repo, "init", "-q", "-b", "main", "--template="], check=True, env=GIT_ENV)

    def env_block(v: str | None) -> str:
        if v is None:
            return "            - name: UNRELATED\n              value: x"
        if v == "valueFrom":
            return ("            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              valueFrom:\n"
                    "                configMapKeyRef: {name: cm, key: k}")
        if v.startswith("raw:"):
            return v[4:]
        return f"            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"{v}\""

    if base_app is not None:
        write(root, f"{SVC}/src/main/resources/application.yaml", app_text or APP_YAML % base_app)
    if kustomization:
        write(root, "openbank-infra/gitops/components/foo/kustomization.yaml", kustomization)
    write(root, "openbank-infra/gitops/components/foo/foo-service.yaml", MANIFEST % (SVC, env_block(base_env), extra))
    write(root, f"{SVC}/src/main/resources/db/migration/V1__init.sql", "CREATE TABLE foo_outbox (id BIGINT);\n")
    for rel, text in (base_files or {}).items():
        write(root, rel, text)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "base")
    base = git(repo, "rev-parse", "HEAD").strip()
    for old, new in (rename or {}).items():
        (root / new).parent.mkdir(parents=True, exist_ok=True)
        git(repo, "mv", old, new)
    for rel, text in head_files.items():
        write(root, rel, text)
    if head_env is not None:
        write(root, "openbank-infra/gitops/components/foo/foo-service.yaml", MANIFEST % (SVC, env_block(head_env), extra))
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
        ("claim_token in a DIFFERENT statement than the outbox table is not a subject", "true", None,
         {unrelated: "ALTER TABLE foo ADD COLUMN claim_token UUID;\nCREATE TABLE foo_outbox (id INT);\n"}, None, 0),
        ("index-only migration on claim_token is not a subject (post-cutover)", "true", None,
         {unrelated: "CREATE INDEX ix_c ON foo_outbox (claim_token);\n"}, None, 0),
    ]
    cases.append(("QUARKUS_PROFILE literal, no profile-specific dispatch config anywhere: resolvable, paused", "true",
                  "raw:            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"false\"\n"
                  "            - name: QUARKUS_PROFILE\n              value: prod", {mig: V2_SQL}, None, 0, {}))
    cases.append(("application.yaml lacks the key but the container carries literal false", "x", "false",
                  {mig: V2_SQL}, None, 0, dict(app_text="openbank:\n  other: 1\n")))
    d = f"{SVC}/src/main/resources/db/migration"
    detect = [  # subject-detection gaps: each must be a subject, dispatch enabled, so exit 1
        ("one-line CREATE TABLE with bigint claim_token", {mig: "CREATE TABLE foo_outbox (id BIGINT, claim_token BIGINT);\n"}, {}),
        ("RENAME COLUMN ... TO claim_token", {mig: "ALTER TABLE foo_outbox RENAME COLUMN tok TO claim_token;\n"}, {}),
        ("schema-qualified outbox table", {mig: "ALTER TABLE public.foo_outbox ADD COLUMN claim_token UUID;\n"}, {}),
        ("uppercase .SQL extension", {f"{d}/V2__OUTBOX.SQL": V2_SQL}, {}),
        ("migration in a db/migration subfolder", {f"{d}/sub/V2__outbox_v2.sql": V2_SQL}, {}),
        ("E'\\'' escape must not desync the scanner and swallow the next statement",
         {mig: "CREATE INDEX ix ON foo_outbox (a) WHERE note = E'it\\'s'; ALTER TABLE foo_outbox ADD COLUMN claim_token uuid;\n"}, {}),
        ("'--' inside a string literal must not hide the statement", {mig: "ALTER TABLE foo_outbox ADD COLUMN n TEXT DEFAULT '--'; ALTER TABLE foo_outbox ADD COLUMN claim_token UUID;\n"}, {}),
        ("RENAMED migration file", {}, dict(base_files={f"{d}/V2__draft.sql": V2_SQL}, rename={f"{d}/V2__draft.sql": f"{d}/V3__outbox_v2.sql"})),
        ("Java Flyway migration added is an unknown subject", {f"{d}/V2__Outbox.java": "class V2__Outbox {}\n"}, {}),
        ("Kotlin Flyway migration added is an unknown subject", {f"{d}/V2__Outbox.kt": "class V2__Outbox\n"}, {}),
    ]
    cases_detect = [(f"detect: {lbl}", "true", None, files, None, 1, kw) for lbl, files, kw in detect]
    # Fail-closed cases: each has an explicit literal `false` (or yaml false) AND a second source
    # that could override it, or no provable source at all. Each must FAIL (exit 1).
    cases_unknown = [
        ("envFrom on the container", "true", "false", {mig: V2_SQL}, None, 1,
         dict(extra="          envFrom:\n            - configMapRef: {name: cm}\n")),
        ("%prod profile sets dispatch-enabled", "false", "false", {mig: V2_SQL}, None, 1,
         dict(app_text="openbank:\n  outbox:\n    dispatch-enabled: false\n\"%prod\":\n  openbank:\n    outbox:\n      dispatch-enabled: true\n")),
        ("JAVA_TOOL_OPTIONS carries a -D override", "true", "raw:            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"false\"\n"
         "            - name: JAVA_TOOL_OPTIONS\n              value: \"-Dopenbank.outbox.dispatch-enabled=true\"", {mig: V2_SQL}, None, 1, {}),
        ("QUARKUS_PROFILE from valueFrom is unresolvable", "true", "raw:            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"false\"\n"
         "            - name: QUARKUS_PROFILE\n              valueFrom:\n                configMapKeyRef: {name: cm, key: k}", {mig: V2_SQL}, None, 1, {}),
        ("QUARKUS_PROFILE=prod and a %prod section sets dispatch-enabled", "false", "raw:            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"false\"\n"
         "            - name: QUARKUS_PROFILE\n              value: prod", {mig: V2_SQL}, None, 1,
         dict(app_text="openbank:\n  outbox:\n    dispatch-enabled: false\n\"%prod\":\n  openbank:\n    outbox:\n      dispatch-enabled: true\n")),
        ("QUARKUS_PROFILE=prod and application-prod.yaml sets dispatch-enabled", "false", "raw:            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"false\"\n"
         "            - name: QUARKUS_PROFILE\n              value: prod", {mig: V2_SQL}, None, 1,
         dict(base_files={f"{SVC}/src/main/resources/application-prod.yaml": "openbank:\n  outbox:\n    dispatch-enabled: true\n"})),
        ("QUARKUS_CONFIG_LOCATIONS adds a config file", "true", "raw:            - name: OPENBANK_OUTBOX_DISPATCH_ENABLED\n              value: \"false\"\n"
         "            - name: QUARKUS_CONFIG_LOCATIONS\n              value: /x/y.yaml", {mig: V2_SQL}, None, 1, {}),
        ("application.yaml missing and no env", None, None, {mig: V2_SQL}, None, 1, {}),
        ("application.yaml lacks the key and no env", "x", None, {mig: V2_SQL}, None, 1,
         dict(app_text="openbank:\n  other: 1\n")),
        ("kustomize patches touch the workload", "true", "false", {mig: V2_SQL}, None, 1,
         dict(kustomization="resources: [foo-service.yaml]\npatches:\n  - path: p.yaml\n")),
        ("container args carry a -D override", "true", "false", {mig: V2_SQL}, None, 1,
         dict(extra='          args: ["-Dopenbank.outbox.dispatch-enabled=true"]\n')),
        ("container command mentions dispatch", "true", "false", {mig: V2_SQL}, None, 1,
         dict(extra='          command: ["sh", "-c", "export DISPATCH=1; exec java -jar x"]\n')),
    ]
    failures = 0
    made: list[str] = []
    cases = [c if len(c) == 7 else (*c, {}) for c in cases] + cases_detect + cases_unknown
    prepared = []
    for c in cases:
        c = list(c)
        if not c[3]:
            c[3] = {"README.md": "x\n"}
        prepared.append(c)
    # Building fixtures is git-process bound; do it in parallel, then check sequentially
    # (check() prints, and redirect_stdout is process-global).
    with ThreadPoolExecutor(max_workers=8) as pool:
        repos = list(pool.map(lambda c: fixture(c[1], c[2], c[3], c[4], **c[6]), prepared))
    for case, (repo, base, head) in zip(prepared, repos, strict=True):
        label, want = case[0], case[5]
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
    if env_name_for("openbank-pricing-service") != "PRICING_OUTBOX_DISPATCH_ENABLED" or env_name_for("x") != ENV_NAME:
        print("  FAIL env-key map")
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
