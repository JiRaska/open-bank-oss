#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Load every committed Loki rule ConfigMap into a real Loki ruler and assert it accepts them.

WHY THIS IS NOT OPTIONAL, and why structural YAML validation would not do.

Loki's ruler lists rule groups per tenant as one operation. When ANY file in the tenant directory
fails to parse, that operation fails wholesale:

    msg="unable to list rules" err="failed to list rule groups for user fake: ...
    error parsing /rules/fake/broken.yaml: could not parse expression for alert '...'"

and `/prometheus/api/v1/rules` then returns an EMPTY group list. Measured, not assumed: with four
valid rules loaded and healthy, adding one file with a single unbalanced parenthesis dropped the
loaded groups from four to zero. So a typo in a new alert does not disable that alert — it disables
every log-based alert in the estate, silently, with the ruler still running and Loki still ready.

That is the worst possible blast radius for the cheapest possible mistake, and nothing else in this
repo would catch it: `yamllint` sees valid YAML, ArgoCD syncs a valid ConfigMap, the sidecar copies
a valid file, and the pod stays Running. The only signal is one ERROR line in the ruler's own log —
which is exactly the class of failure these rules exist to catch, so relying on it would be circular.

Structural validation cannot substitute: the failure is in the LogQL *expression*, and only a LogQL
parser can judge it. Loki ships no `lokitool` in its container image (verified against
grafana/loki:3.6.7 — `usr/bin/loki` is the only binary), so the parser we can reach is the ruler
itself.

Usage:
    check-loki-rules.py                       # always run the load test
    check-loki-rules.py --since origin/main   # run it only when a rule input changed (CI)
    check-loki-rules.py --self-test           # prove the gate can fail

Requires docker. Without it the check SKIPS with a warning and exit 0 — deliberately, so this
cannot become a hard dependency that blocks unrelated work on a machine without docker; the CI job
that runs it does have docker.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

REPO = Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra" / "gitops" / "components"
LOKI_IMAGE = "grafana/loki:3.6.7"
PORT = 13199
CONTAINER = "openbank-loki-rule-check"

# Minimal single-binary config whose ruler section mirrors apps/loki.yaml's: local rule storage in
# /rules, single-tenant (auth_enabled: false, so the tenant directory is `fake`). Storage is
# filesystem rather than S3 because this only exercises the RULE PARSER, and an S3 dependency would
# make the gate fail for reasons that have nothing to do with the rules.
LOKI_CONFIG = """
auth_enabled: false
server:
  http_listen_port: 3100
common:
  path_prefix: /loki
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules-tmp
schema_config:
  configs:
    - from: "2024-04-01"
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h
ruler:
  enable_api: true
  storage:
    type: local
    local:
      directory: /rules
  rule_path: /tmp/loki-rules
  ring:
    kvstore:
      store: inmemory
"""


def rule_configmaps(root: Path) -> dict[str, str]:
    """{filename: rule-file body} for every ConfigMap labelled loki_rule.

    Derived from the label the Loki sidecar actually selects on, not from a hand-kept path list —
    a rules ConfigMap added in a new directory is in scope automatically. The label IS the contract.
    """
    out: dict[str, str] = {}
    for f in sorted(root.rglob("*.yaml")):
        text = f.read_text(encoding="utf-8", errors="ignore")
        if "loki_rule" not in text:
            continue
        for doc in yaml.safe_load_all(text):
            if not isinstance(doc, dict) or doc.get("kind") != "ConfigMap":
                continue
            if "loki_rule" not in (doc.get("metadata", {}).get("labels") or {}):
                continue
            for key, body in (doc.get("data") or {}).items():
                out[f"{doc['metadata']['name']}__{key}"] = body
    return out


def _get(url: str, timeout: float = 5.0) -> str | None:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read().decode()
    except (urllib.error.URLError, OSError):
        return None


def load_into_ruler(bodies: dict[str, str], quiet: bool = False) -> tuple[bool, str]:
    """-> (accepted, detail). accepted is False when the ruler lists fewer groups than we gave it."""
    tmp = Path(tempfile.mkdtemp())
    rules_dir = tmp / "rules" / "fake"
    rules_dir.mkdir(parents=True)
    (tmp / "config.yaml").write_text(LOKI_CONFIG)
    expected_groups = 0
    for name, body in bodies.items():
        (rules_dir / f"{name}.yaml").write_text(body)
        doc = yaml.safe_load(body) or {}
        expected_groups += len(doc.get("groups") or [])

    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    run = subprocess.run(
        ["docker", "run", "-d", "--name", CONTAINER, "-p", f"{PORT}:3100",
         "-v", f"{tmp / 'config.yaml'}:/etc/loki/local-config.yaml",
         "-v", f"{tmp / 'rules'}:/rules", LOKI_IMAGE,
         "-config.file=/etc/loki/local-config.yaml"],
        capture_output=True, text=True)
    if run.returncode != 0:
        return False, f"could not start {LOKI_IMAGE}: {run.stderr.strip()[:300]}"
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if (_get(f"http://localhost:{PORT}/ready") or "").strip().startswith("ready"):
                break
            time.sleep(2)
        else:
            return False, "Loki never became ready within 120s"

        # The ruler lists lazily; poll until it reports the expected count or we run out of patience.
        deadline = time.monotonic() + 60
        loaded: list[str] = []
        while time.monotonic() < deadline:
            raw = _get(f"http://localhost:{PORT}/prometheus/api/v1/rules")
            if raw:
                groups = (json.loads(raw).get("data") or {}).get("groups") or []
                loaded = [g["name"] for g in groups]
                if len(loaded) >= expected_groups:
                    break
            time.sleep(2)

        if len(loaded) < expected_groups:
            logs = subprocess.run(["docker", "logs", CONTAINER], capture_output=True, text=True)
            err = [ln for ln in (logs.stderr + logs.stdout).splitlines()
                   if "unable to list rules" in ln or "error parsing" in ln]
            return False, (
                f"ruler loaded {len(loaded)} group(s), expected {expected_groups}. "
                f"A SINGLE unparseable file empties the whole list, so the broken rule may not be "
                f"the one you just added. Ruler said: "
                + (err[-1][:600] if err else "(no parse error logged)"))
        if not quiet:
            print(f"  ruler accepted {len(loaded)} group(s): {', '.join(sorted(loaded))}")
        return True, ""
    finally:
        subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)


def run_gate() -> int:
    if not shutil.which("docker"):
        print("::warning title=Loki rules::docker not available — skipping the Loki rule load test. "
              "The CI job that gates this does have docker.")
        return 0
    bodies = rule_configmaps(COMPONENTS)
    if not bodies:
        print("check-loki-rules: no ConfigMaps labelled loki_rule found — nothing to check.")
        return 0
    print(f"check-loki-rules: loading {len(bodies)} rule file(s) into {LOKI_IMAGE}")
    ok, detail = load_into_ruler(bodies)
    if ok:
        print("check-loki-rules: every committed Loki rule file parses.")
        return 0
    print(f"::error title=Loki rules::{detail}")
    return 1


def self_test(since: str | None = None) -> int:
    """A gate that has only ever passed is unfalsified. Feed it a file it MUST reject.

    Scoped by --since for the same reason the gate itself is, and measured the same way: the
    self-test boots Loki TWICE (a case it must pass, a case it must flag), which cost 1m46s on the
    first main run after it shipped, against 0s for the enforced step it sits in front of. Scoping
    only the enforced half — as the first version of this did — moved the cheap step and left the
    expensive one charging every PR in the fleet. Found by reading the step timings CI printed, not
    from the design.

    Skipping is safe here in a way it usually is not, because RULE_INPUTS already contains
    everything that could newly break this self-test: the rule ConfigMaps, apps/loki.yaml, and THIS
    SCRIPT — which is where LOKI_IMAGE lives. So the self-test runs on exactly the changes that
    could invalidate it, and stays silent on the ones that cannot.
    """
    if not shutil.which("docker"):
        print("::warning title=Loki rules::docker not available — cannot run the self-test.")
        return 0
    if since:
        changed, detail = rule_inputs_changed(since)
        if not changed:
            print(f"check-loki-rules --self-test: SKIPPED — {detail}. The gate is unchanged and "
                  f"was falsified on the PR that last touched it.")
            return 0
        print(f"check-loki-rules --self-test: {detail}")
    failures = []
    good = {"good": yaml.safe_dump({"groups": [{
        "name": "selftest.good", "interval": "5m", "rules": [{
            "alert": "SelfTestGood",
            "expr": 'sum by (namespace) (count_over_time({namespace=~".+"} |= "x" [15m])) > 0',
            "labels": {"severity": "warning"}}]}]})}
    # Unbalanced parenthesis: valid YAML, invalid LogQL — the exact shape yamllint cannot see.
    bad = dict(good, bad='groups:\n  - name: selftest.bad\n    rules:\n      - alert: SelfTestBad\n'
                         '        expr: sum by (namespace ( count_over_time({namespace=~".+"} |= "x" [15m])\n')

    print("self-test: the case the gate MUST pass")
    ok, detail = load_into_ruler(good)
    print(f"  {'ok  ' if ok else 'FAIL'} a valid rule file is accepted" + ("" if ok else f" — {detail}"))
    if not ok:
        failures.append("valid rule rejected")

    print("self-test: the case the gate MUST flag")
    ok, _ = load_into_ruler(bad, quiet=True)
    print(f"  {'ok  ' if not ok else 'FAIL'} a file with unparseable LogQL is rejected")
    if ok:
        failures.append("broken rule accepted")

    if failures:
        print(f"\n::error::check-loki-rules --self-test: {', '.join(failures)}")
        return 1
    print("\nself-test passed: the gate accepts valid LogQL and rejects a broken file.")
    return 0


# What this gate costs, stated rather than assumed (the house rule is to quantify FinOps with a
# denominator). Booting a real Loki means pulling ~100 MB and waiting ~25 s for readiness, so a full
# run adds roughly 90 s. It lives in `Validate manifests`, which is UNCONDITIONAL and required — so
# without scoping, every PR in the fleet pays that, including the overwhelming majority that touch
# no rule at all. Direct dollar cost is $0 (public repo, GitHub-hosted runners are free), but 90 s
# on a required check is 90 s every contributor waits, on every PR, forever.
#
# So the expensive part is scoped to the PRs that can actually invalidate it, and the STEP still
# runs unconditionally and PRINTS what it decided. That distinction matters: a path-filtered
# workflow can never be a required check (a required context that never reports blocks every PR
# that misses its paths), and a step that silently no-ops reads exactly like a step that passed.
# This one says which it did.
RULE_INPUTS = [
    "openbank-infra/gitops/components",       # where loki_rule ConfigMaps live
    "openbank-infra/gitops/apps/loki.yaml",   # the ruler config the rules load under
    ".github/scripts/check-loki-rules.py",    # the gate itself
]


def rule_inputs_changed(base_ref: str) -> tuple[bool, str]:
    """-> (changed, detail). Fails OPEN: an unanswerable git question runs the check.

    Counts UNCOMMITTED work as changed, not just the committed diff. `git diff base...HEAD` compares
    commits, so a developer who edits a rule and runs this before committing would be told "skipped"
    and could push a broken rule believing it was checked. CI never hits that (its changes are always
    committed) which is exactly why it would have gone unnoticed — the local experience is the one
    that misleads.
    """
    files: list[str] = []
    try:
        files += subprocess.run(
            ["git", "diff", "--name-only", f"{base_ref}...HEAD", "--", *RULE_INPUTS],
            capture_output=True, text=True, cwd=REPO, check=True).stdout.splitlines()
    except (subprocess.CalledProcessError, OSError) as ex:
        # Never skip because git was unhappy — that would turn an infrastructure hiccup into a
        # silently unchecked rule set, which is the failure mode this whole file exists to prevent.
        return True, f"could not diff against {base_ref} ({ex}) — running the load test anyway"
    try:
        # Working tree + index + untracked, so a brand-new rule file counts before its first commit.
        porcelain = subprocess.run(
            ["git", "status", "--porcelain", "--", *RULE_INPUTS],
            capture_output=True, text=True, cwd=REPO, check=True).stdout
        files += [ln[3:].strip() for ln in porcelain.splitlines() if ln[3:].strip()]
    except (subprocess.CalledProcessError, OSError):
        return True, "could not read the working tree — running the load test anyway"

    uniq = sorted({f for f in files if f.strip()})
    if not uniq:
        return False, f"no rule inputs changed vs {base_ref} (committed or working tree)"
    return True, f"{len(uniq)} rule input(s) changed vs {base_ref}: {', '.join(uniq[:5])}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="prove the gate can fail")
    ap.add_argument("--since", metavar="REF",
                    help="skip when no rule input changed vs REF (e.g. origin/main). Applies to "
                         "BOTH the gate and --self-test — each boots Loki, and the self-test boots "
                         "it twice. Omit to always run.")
    args = ap.parse_args()
    if args.self_test:
        return self_test(args.since)
    if args.since:
        changed, detail = rule_inputs_changed(args.since)
        if not changed:
            n = len(rule_configmaps(COMPONENTS))
            print(f"check-loki-rules: SKIPPED the Loki load test — {detail}. "
                  f"{n} rule file(s) in tree, unchanged by this PR, and validated on the PR that "
                  f"last touched them.")
            return 0
        print(f"check-loki-rules: {detail}")
    return run_gate()


if __name__ == "__main__":
    sys.exit(main())
