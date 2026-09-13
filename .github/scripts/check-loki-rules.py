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
        print("::warning title=Loki rules::docker not available — cannot run the LOAD-TEST half "
              "of the self-test. The wiring half below needs no docker and still runs.")
    if since:
        changed, detail = rule_inputs_changed(since)
        if not changed:
            print(f"check-loki-rules --self-test: SKIPPED — {detail}. The gate is unchanged and "
                  f"was falsified on the PR that last touched it.")
            return 0
        print(f"check-loki-rules --self-test: {detail}")
    failures = []

    # The wiring half, falsified first: it needs no docker, so it runs even where the load test
    # cannot. Feed it the exact defect #5734 was — the block keyed `ruler` instead of
    # `rulerConfig` — and require it to flag. A gate that has only ever seen a passing input has
    # not been shown to be able to fail.
    print("self-test: the wiring case the gate MUST flag")
    with tempfile.TemporaryDirectory() as td:
        app = yaml.safe_load(LOKI_APP.read_text(encoding="utf-8"))
        loki = app["spec"]["source"]["helm"]["valuesObject"]["loki"]
        loki["ruler"] = loki.pop("rulerConfig")          # the pre-#5734 shape, verbatim
        broken = Path(td) / "loki-broken.yaml"
        broken.write_text(yaml.safe_dump(app, sort_keys=False))
        flagged = check_ruler_wiring(broken)
    print(f"  {'ok  ' if flagged else 'FAIL'} `loki.ruler:` instead of `loki.rulerConfig:` is "
          f"rejected" + (f" ({len(flagged)} problem(s))" if flagged else ""))
    if not flagged:
        failures.append("silently-dropped ruler key accepted")

    print("self-test: the wiring case the gate MUST pass")
    live = check_ruler_wiring()
    print(f"  {'ok  ' if not live else 'FAIL'} the committed loki.yaml is accepted"
          + ("" if not live else f" — {live[0]}"))
    if live:
        failures.append("committed loki.yaml rejected")

    if not shutil.which("docker"):
        # Reached only when docker appeared between the guard above and here; keep it honest.
        return 1 if failures else 0

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
LOKI_APP = REPO / "openbank-infra" / "gitops" / "apps" / "loki.yaml"


def check_ruler_wiring(app_file: Path = LOKI_APP) -> list[str]:
    """Assert the DEPLOYED chart values actually produce the ruler this gate simulates.

    WHY THIS EXISTS, AND WHY THE LOAD TEST ABOVE COULD NOT SEE IT (issue #5734). The load test
    boots Loki with LOKI_CONFIG — a ruler section written to mirror apps/loki.yaml. It therefore
    validates the rules against the config the repo MEANT to deploy, and is structurally blind to
    whether that config is the one that actually runs. It was not. From #3060 until 2026-08-20 the
    block in apps/loki.yaml was keyed `loki.ruler:`; the grafana/loki chart's key is
    `loki.rulerConfig:`, and Helm discards an unknown value key silently. So the live ruler ran on
    defaults — rule storage `s3` instead of `local:/rules`, and an EMPTY alertmanager_url — while
    the sidecar dutifully wrote three rule files to /rules/fake that nothing ever read. Measured
    live: `/prometheus/api/v1/rules` returned zero groups with all three files present on disk.
    Every log-based alert in the estate had never evaluated, and the load test was green
    throughout, about a config nobody deployed.

    Deliberately offline and unconditional: no docker, no chart download, no network, single-digit
    milliseconds. This is the half of the check that must never be skipped, because the failure it
    catches produces no error anywhere else — not in Helm, not in ArgoCD, not in the pod's health.

    Nothing here is hardcoded that can be derived: the expected rule directory comes from the
    sidecar's own `folder`, so moving the sidecar moves the assertion with it.
    """
    problems: list[str] = []
    try:
        app = yaml.safe_load(app_file.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as ex:
        return [f"could not read {app_file}: {ex}"]

    values = (((app or {}).get("spec") or {}).get("source") or {}).get("helm", {}).get(
        "valuesObject") or {}
    loki = values.get("loki") or {}

    # (1) The key itself. `ruler` is the Loki CONFIG name and the intuitive guess; `rulerConfig`
    #     is the CHART name and the only one that survives templating.
    if "ruler" in loki:
        problems.append(
            "loki.yaml sets `loki.ruler:` — not a chart key. The grafana/loki chart calls it "
            "`loki.rulerConfig:`, and Helm drops unknown value keys SILENTLY, so every setting "
            "under it is inert and the ruler runs on defaults (s3 rule storage, no "
            "alertmanager_url). Rename the key to `rulerConfig`.")
    ruler = loki.get("rulerConfig")
    if not isinstance(ruler, dict):
        problems.append(
            "loki.yaml declares no `loki.rulerConfig:` block, so the Loki ruler has no rule "
            "storage and no Alertmanager — every loki_rule ConfigMap in this repo would be "
            "written to disk and never read.")
        return problems

    # (2) Rule storage must be LOCAL and point at the directory the sidecar writes into.
    #     Derived from the sidecar's folder rather than restated, so the two cannot drift.
    sidecar_folder = ((values.get("sidecar") or {}).get("rules") or {}).get("folder")
    storage = ruler.get("storage") or {}
    if storage.get("type") != "local":
        problems.append(
            f"loki.rulerConfig.storage.type is {storage.get('type')!r}, not 'local'. Rules reach "
            f"this ruler as files written by the k8s-sidecar; any other storage type makes the "
            f"ruler read somewhere nothing writes and load zero groups.")
    directory = (storage.get("local") or {}).get("directory")
    if sidecar_folder and directory:
        # The sidecar writes into <directory>/<tenant>; single-tenant Loki calls the tenant `fake`.
        if not str(sidecar_folder).rstrip("/").startswith(str(directory).rstrip("/") + "/"):
            problems.append(
                f"the sidecar writes rules to {sidecar_folder!r} but the ruler reads "
                f"{directory!r} — the ruler will never see them.")
    elif not directory:
        problems.append("loki.rulerConfig.storage.local.directory is unset — the ruler has no "
                        "rule directory to read.")

    # (3) A rule that fires and is delivered nowhere is the same write-only control this whole
    #     issue is about, one layer up.
    if not (ruler.get("alertmanager_url") or "").strip():
        problems.append(
            "loki.rulerConfig.alertmanager_url is empty — a rule can evaluate and fire, and the "
            "alert is dropped on the floor with no error anywhere.")

    # (4) Keep the simulation honest: the ruler this gate boots must match the deployed one on the
    #     two settings that decide whether rules load at all. Otherwise the load test drifts back
    #     into being green about a config nobody runs, which is exactly how #5734 happened.
    sim = (yaml.safe_load(LOKI_CONFIG) or {}).get("ruler") or {}
    sim_dir = ((sim.get("storage") or {}).get("local") or {}).get("directory")
    if (sim.get("storage") or {}).get("type") != storage.get("type") or sim_dir != directory:
        problems.append(
            f"this gate boots a ruler with storage "
            f"{(sim.get('storage') or {}).get('type')!r}:{sim_dir!r} but loki.yaml deploys "
            f"{storage.get('type')!r}:{directory!r}. The load test would be validating rules "
            f"against a ruler the estate does not run — update LOKI_CONFIG in this file.")
    return problems


def run_wiring_gate() -> int:
    problems = check_ruler_wiring()
    if not problems:
        print("check-loki-rules: the deployed Loki ruler is wired to read these rules and to "
              "deliver what they fire.")
        return 0
    for p in problems:
        print(f"::error title=Loki ruler wiring::{p}")
    return 1


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
    # ALWAYS, and before anything else: offline, no docker, no --since skip. The load test can be
    # skipped when no rule changed; this cannot, because it does not check the rules — it checks
    # that a ruler capable of loading them exists at all, and that regresses from an edit to
    # loki.yaml that touches no rule file.
    wiring = run_wiring_gate()
    if args.self_test:
        return self_test(args.since) or wiring
    if wiring:
        return wiring
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
