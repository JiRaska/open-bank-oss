#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every secret / variable / OpenBao key the repo CONSUMES must be DECLARED in one inventory.

THE FAILURE THIS CATCHES
  A consumer is merged that reads a value nobody has provisioned. Nothing fails at merge:
  `${{ secrets.X }}` of an absent secret is an empty string, an ExternalSecret whose remote key
  does not exist is a perfectly valid object. The failure shows up later, somewhere else, and
  looks like an outage of the consumer rather than a missing input. Measured 2026-11-14, all at
  once:
    * ci-metrics-gateway: ExternalSecret `ci-metrics-ingest` -> "Secret does not exist", 0
      replicas for ~70h, four alerts firing about the symptom and none naming the cause;
    * card-processing-service: ExternalSecret `card-processing-service-issuer-oidc` not ready
      ~44h (the Keycloak client existed only in the realm template, never in the live realm);
    * `Standing critical alert digest` red on every run since it was added: its two secrets
      and its Slack webhook were never created — and could not have worked from a GitHub-hosted
      runner anyway, because Alertmanager is reachable from outside only through a cookie gate;
    * `CI artifact mirror` red on every run: its two repository variables wait on a
      `tofu apply` that has not happened.
  Each was "an input referenced but never provisioned". This makes the reference and the
  provisioning two halves of the SAME change: a PR adding a consumer must also name the input in
  `.github/config-inventory.yaml`, either as provisioned or as deliberately unprovisioned with an
  issue and a review date. The reviewer then sees the claim, instead of the cluster discovering
  its absence days later.

WHAT IT CHECKS (offline, every PR)
  1. every `secrets.X` / `vars.X` in .github/workflows and .github/actions resolves to the
     inventory: repository scope, or the environment scope of the job that reads it. Secrets a
     reusable workflow declares under `on.workflow_call.secrets` are its INPUTS (the caller's
     reference is the one checked) and GITHUB_TOKEN is platform-provided;
  2. every ExternalSecret remote key in openbank-infra/gitops whose store is listed in
     `openbao.stores` resolves to `openbao.keys` or `openbao.unprovisioned`;
  3. the inventory carries nothing stale: a declared name no consumer references fails, so the
     file cannot rot into a list that describes a past tree;
  4. every `unprovisioned` entry has a reason, an issue and a `review_by` date that has not
     passed — a disabled input is a decision with an owner and an expiry, never a default.

WHAT IT CANNOT CHECK OFFLINE, AND THE MODE THAT DOES
  Whether the inventory is TRUE. A PR can declare a secret provisioned that is not. `--live`
  compares the declarations against the live sources (`gh secret list` / `gh variable list` per
  repository and environment — needs an admin-scoped gh token, so it runs on an operator
  machine, not in Actions — and `kubectl get externalsecret -A` readiness for OpenBao keys) and
  exits 1 on any disagreement in either direction. Run it after provisioning a value and before
  moving a name from `unprovisioned` to provisioned.

MODES
  (default)     offline gate, as above
  --live        drift against GitHub + the cluster (operator machine)
  --self-test   fixtures, including the known-negatives
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

ROOT = Path(".")
INVENTORY = Path(".github/config-inventory.yaml")
WORKFLOW_DIRS = (Path(".github/workflows"), Path(".github/actions"))
GITOPS = Path("openbank-infra/gitops")
REPO = "JiRaska/open-bank-oss"

REF = re.compile(r"\b(secrets|vars)\.([A-Za-z_][A-Za-z0-9_]*)")
PLATFORM_SECRETS = {"GITHUB_TOKEN"}


# --------------------------------------------------------------------------- collection
def _on(doc: dict) -> dict:
    # PyYAML reads the bare key `on` as boolean True.
    on = doc.get("on", doc.get(True, {}))
    return on if isinstance(on, dict) else {}


def _env_name(job: dict) -> str | None:
    """The job's environment; for an expression, the quoted literals it can select, `|`-joined."""
    env = job.get("environment")
    if isinstance(env, dict):
        env = env.get("name")
    if not isinstance(env, str):
        return None
    if "${{" in env:
        lits = re.findall(r"'([A-Za-z0-9_.-]+)'", env)
        return "|".join(lits) or None
    return env


def workflow_refs(root: Path) -> list[tuple[str, str, str | None, str]]:
    """(kind, name, environment-or-None, file) for every reference."""
    out: list[tuple[str, str, str | None, str]] = []
    for base in WORKFLOW_DIRS:
        for path in sorted((root / base).rglob("*.y*ml")):
            text = path.read_text(encoding="utf-8")
            try:
                doc = yaml.safe_load(text) or {}
            except yaml.YAMLError as exc:
                raise SystemExit(f"FAIL {path}: unparseable YAML ({exc}) — cannot enumerate its inputs") from exc
            if not isinstance(doc, dict):
                continue
            rel = str(path.relative_to(root))
            call = _on(doc).get("workflow_call")
            inputs = set((call or {}).get("secrets") or {}) if isinstance(call, dict) else set()
            jobs = doc.get("jobs") if isinstance(doc.get("jobs"), dict) else {}
            scoped: list[tuple[str, str | None]] = []
            for job in jobs.values():
                if isinstance(job, dict):
                    scoped.append((json.dumps(job, default=str), _env_name(job)))
            rest = {k: v for k, v in doc.items() if k != "jobs"}
            scoped.append((json.dumps(rest, default=str), None))
            for blob, env in scoped:
                for kind, name in REF.findall(blob):
                    if kind == "secrets" and (name in PLATFORM_SECRETS or name in inputs):
                        continue
                    out.append(("secret" if kind == "secrets" else "variable", name, env, rel))
    return out


def eso_refs(root: Path, stores: set[str]) -> list[tuple[str, str]]:
    """(key, file) for every ExternalSecret remote key served by one of `stores`."""
    out: list[tuple[str, str]] = []
    for path in sorted((root / GITOPS).rglob("*.y*ml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "ExternalSecret" not in text:
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            continue  # helm-templated values files; never a raw ExternalSecret
        for d in docs:
            if not isinstance(d, dict) or d.get("kind") != "ExternalSecret":
                continue
            spec = d.get("spec") or {}
            if (spec.get("secretStoreRef") or {}).get("name") not in stores:
                continue
            rel = str(path.relative_to(root))
            for item in spec.get("data") or []:
                key = ((item or {}).get("remoteRef") or {}).get("key")
                if key:
                    out.append((key, rel))
            for item in spec.get("dataFrom") or []:
                key = ((item or {}).get("extract") or {}).get("key")
                if key:
                    out.append((key, rel))
    return out


# --------------------------------------------------------------------------- inventory
def load_inventory(root: Path) -> dict:
    return yaml.safe_load((root / INVENTORY).read_text(encoding="utf-8")) or {}


def _unprov(entries: list, today: dt.date, where: str, errors: list[str]) -> dict[tuple[str, str], dict]:
    out: dict[tuple[str, str], dict] = {}
    for i, e in enumerate(entries or []):
        tag = f"{where}[{i}]"
        for f in ("names", "reason", "issue", "review_by"):
            if not e.get(f):
                errors.append(f"{tag}: unprovisioned entry needs `{f}` — a disabled input is a decision with an owner and an expiry")
        rb = e.get("review_by")
        try:
            rb = rb if isinstance(rb, dt.date) else dt.date.fromisoformat(str(rb))
            if rb < today:
                errors.append(f"{tag}: review_by {rb} has passed — provision {e.get('names')} or re-decide (issue #{e.get('issue')})")
        except ValueError:
            errors.append(f"{tag}: review_by `{rb}` is not YYYY-MM-DD")
        for n in e.get("names") or []:
            out[(e.get("kind", "key"), n)] = e
    return out


def check(root: Path, today: dt.date) -> tuple[list[str], int]:
    inv = load_inventory(root)
    errors: list[str] = []
    gh = inv.get("github") or {}
    repo = gh.get("repository") or {}
    envs = gh.get("environments") or {}
    have = {("secret", n): {None} for n in repo.get("secrets") or []}
    for n in repo.get("variables") or []:
        have.setdefault(("variable", n), set()).add(None)
    for env, scope in envs.items():
        for n in (scope or {}).get("secrets") or []:
            have.setdefault(("secret", n), set()).add(env)
        for n in (scope or {}).get("variables") or []:
            have.setdefault(("variable", n), set()).add(env)
    gh_un = _unprov(gh.get("unprovisioned"), today, "github.unprovisioned", errors)
    for k in gh_un:
        if k in have:
            errors.append(f"{k[0]} {k[1]}: declared both provisioned and unprovisioned")

    refs = workflow_refs(root)
    used_gh: set[tuple[str, str]] = set()
    for kind, name, env, f in refs:
        key = (kind, name)
        used_gh.add(key)
        scopes = have.get(key, set())
        if None in scopes or (env and set(env.split("|")) & scopes) or key in gh_un:
            continue
        where = f"environment `{env}` or repository" if env else "repository"
        hint = f" (declared only for environments {sorted(s for s in scopes if s)})" if scopes else ""
        errors.append(
            f"{f}: {kind} `{name}` is read but not declared in {INVENTORY} ({where} scope){hint} — "
            f"provision it and list it, or list it under github.unprovisioned with reason/issue/review_by"
        )
    for key in sorted(set(have) | set(gh_un)):
        if key not in used_gh:
            errors.append(f"{INVENTORY}: {key[0]} `{key[1]}` is declared but no workflow reads it — remove it")

    bao = inv.get("openbao") or {}
    stores = set(bao.get("stores") or [])
    keys = set(bao.get("keys") or [])
    bao_un = {n for (_, n) in _unprov(bao.get("unprovisioned"), today, "openbao.unprovisioned", errors)}
    for n in keys & bao_un:
        errors.append(f"openbao key {n}: declared both provisioned and unprovisioned")
    erefs = eso_refs(root, stores)
    used_keys = {k for k, _ in erefs}
    for k, f in erefs:
        if k not in keys and k not in bao_un:
            errors.append(
                f"{f}: ExternalSecret remote key `{k}` is not declared in {INVENTORY} openbao.keys — "
                f"write it to OpenBao and list it, or list it under openbao.unprovisioned"
            )
    for k in sorted((keys | bao_un) - used_keys):
        errors.append(f"{INVENTORY}: openbao key `{k}` is declared but no ExternalSecret reads it — remove it")
    return errors, len(refs) + len(erefs)


# --------------------------------------------------------------------------- live drift
def _run(cmd: list[str]) -> str:
    p = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if p.returncode != 0:
        raise SystemExit(f"UNRESOLVED: `{' '.join(cmd)}` failed: {p.stderr.strip()[:300]}")
    return p.stdout


def _gh_names(kind: str, env: str | None) -> set[str]:
    cmd = ["gh", kind, "list", "-R", REPO, "--json", "name", "-q", ".[].name"]
    if env:
        cmd += ["-e", env]
    return set(_run(cmd).split())


def live(root: Path) -> int:
    inv = load_inventory(root)
    gh = inv.get("github") or {}
    drift: list[str] = []
    scopes = [(None, gh.get("repository") or {})] + list((gh.get("environments") or {}).items())
    for env, scope in scopes:
        for kind, field in (("secret", "secrets"), ("variable", "variables")):
            declared = set((scope or {}).get(field) or [])
            actual = _gh_names(kind, env)
            label = f"environment {env}" if env else "repository"
            for n in sorted(declared - actual):
                drift.append(f"{label}: {kind} {n} declared provisioned but ABSENT on GitHub")
            for n in sorted(actual - declared):
                # Not drift: a provisioned value nothing reads is cleanup, not an outage.
                print(f"INFO {label}: {kind} {n} exists on GitHub but no workflow reads it")
    for e in gh.get("unprovisioned") or []:
        actual = _gh_names(e.get("kind", "secret"), None)
        for n in e.get("names") or []:
            if n in actual:
                drift.append(f"repository: {e.get('kind')} {n} is listed unprovisioned but now EXISTS — move it")
    bao = inv.get("openbao") or {}
    stores = set(bao.get("stores") or [])
    items = json.loads(_run(["kubectl", "get", "externalsecret", "-A", "-o", "json"]))["items"]
    ready: dict[str, set[bool]] = {}
    for es in items:
        spec = es.get("spec") or {}
        if (spec.get("secretStoreRef") or {}).get("name") not in stores:
            continue
        ok = any(c.get("type") == "Ready" and c.get("status") == "True" for c in (es.get("status") or {}).get("conditions") or [])
        for item in spec.get("data") or []:
            ready.setdefault(((item or {}).get("remoteRef") or {}).get("key"), set()).add(ok)
        for item in spec.get("dataFrom") or []:
            ready.setdefault(((item or {}).get("extract") or {}).get("key"), set()).add(ok)
    unprov = {n for e in bao.get("unprovisioned") or [] for n in e.get("names") or []}
    for k in sorted(set(bao.get("keys") or [])):
        if k in ready and False in ready[k]:
            drift.append(f"openbao key {k}: declared provisioned but an ExternalSecret reading it is NOT Ready")
    for k in sorted(unprov):
        if ready.get(k) == {True}:
            drift.append(f"openbao key {k}: listed unprovisioned but every ExternalSecret reading it is Ready — move it")
    for d in drift:
        print(f"DRIFT {d}")
    print(f"live: {len(drift)} drift finding(s)")
    return 1 if drift else 0


# --------------------------------------------------------------------------- self-test
def _fixture(tmp: Path, workflow: str, inventory: dict, eso: str = "") -> Path:
    (tmp / ".github/workflows").mkdir(parents=True, exist_ok=True)
    (tmp / ".github/actions").mkdir(parents=True, exist_ok=True)
    (tmp / GITOPS / "c").mkdir(parents=True, exist_ok=True)
    (tmp / ".github/workflows/w.yml").write_text(workflow)
    (tmp / INVENTORY).write_text(yaml.safe_dump(inventory))
    (tmp / GITOPS / "c/es.yaml").write_text(eso)
    return tmp


def self_test() -> int:
    today = dt.date(2026, 11, 14)
    wf = (
        "on:\n  workflow_call:\n    secrets:\n      passed_in: {required: true}\n"
        "jobs:\n  a:\n    runs-on: x\n    steps:\n      - run: echo ${{ secrets.REPO_S }} ${{ vars.REPO_V }} "
        "${{ secrets.GITHUB_TOKEN }} ${{ secrets.passed_in }}\n"
        "  b:\n    environment: ${{ github.ref == 'refs/heads/main' && 'deploy' || '' }}\n    runs-on: x\n    steps:\n      - run: echo ${{ secrets.ENV_S }}\n"
    )
    eso = (
        "apiVersion: external-secrets.io/v1\nkind: ExternalSecret\nmetadata: {name: e}\n"
        "spec:\n  secretStoreRef: {kind: ClusterSecretStore, name: vault-kv}\n"
        "  data:\n    - secretKey: T\n      remoteRef: {key: app/one, property: p}\n"
        "---\napiVersion: external-secrets.io/v1\nkind: ExternalSecret\nmetadata: {name: k}\n"
        "spec:\n  secretStoreRef: {kind: SecretStore, name: kafka-certs}\n"
        "  data:\n    - secretKey: T\n      remoteRef: {key: not-checked}\n"
    )
    good = {
        "github": {
            "repository": {"secrets": ["REPO_S"], "variables": ["REPO_V"]},
            "environments": {"deploy": {"secrets": ["ENV_S"]}},
        },
        "openbao": {"stores": ["vault-kv"], "keys": ["app/one"]},
    }
    cases: list[tuple[str, str, dict, str, str | None]] = []
    cases.append(("clean tree passes", wf, good, eso, None))
    bad = json.loads(json.dumps(good)); bad["github"]["repository"]["secrets"] = []
    cases.append(("KNOWN-NEGATIVE undeclared repo secret fails", wf, bad, eso, "secret `REPO_S` is read but not declared"))
    bad = json.loads(json.dumps(good)); bad["github"]["environments"] = {"other": {"secrets": ["ENV_S"]}}
    cases.append(("env secret declared for the WRONG environment fails", wf, bad, eso, "declared only for environments ['other']"))
    bad = json.loads(json.dumps(good)); bad["openbao"]["keys"] = []
    cases.append(("KNOWN-NEGATIVE undeclared OpenBao key fails", wf, bad, eso, "remote key `app/one` is not declared"))
    bad = json.loads(json.dumps(good)); bad["github"]["repository"]["variables"].append("GONE")
    cases.append(("stale inventory entry fails", wf, bad, eso, "variable `GONE` is declared but no workflow reads it"))
    bad = json.loads(json.dumps(good)); bad["github"]["repository"]["secrets"] = []
    bad["github"]["unprovisioned"] = [{"kind": "secret", "names": ["REPO_S"], "reason": "r", "issue": 1, "review_by": "2026-11-01"}]
    cases.append(("expired unprovisioned entry fails", wf, bad, eso, "review_by 2026-11-01 has passed"))
    bad = json.loads(json.dumps(bad)); bad["github"]["unprovisioned"][0]["review_by"] = "2027-01-01"
    cases.append(("dated unprovisioned entry passes", wf, bad, eso, None))
    bad = json.loads(json.dumps(bad)); bad["github"]["unprovisioned"][0].pop("issue")
    cases.append(("unprovisioned entry without issue fails", wf, bad, eso, "needs `issue`"))
    failures = 0
    for label, w, inv, e, expect in cases:
        with tempfile.TemporaryDirectory() as d:
            errors, _ = check(_fixture(Path(d), w, inv, e), today)
        ok = (not errors) if expect is None else any(expect in x for x in errors)
        print(f"  {'ok  ' if ok else 'FAIL'} {label}" + ("" if ok else f" -> {errors}"))
        failures += not ok
    print(f"self-test: {len(cases) - failures}/{len(cases)} passed")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--live", action="store_true")
    ap.add_argument("--today", default=dt.datetime.now(dt.timezone.utc).date().isoformat())
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.live:
        return live(ROOT)
    errors, n = check(ROOT, dt.date.fromisoformat(a.today))
    for e in dict.fromkeys(errors):
        print(f"FAIL {e}")
    print(f"config-inventory: {n} references checked, {len(errors)} finding(s)")
    gatelib.subjects(n, "workflow + ExternalSecret references")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
