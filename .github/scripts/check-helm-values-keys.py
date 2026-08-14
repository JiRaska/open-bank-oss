#!/usr/bin/env python3
"""Helm valuesObject key-effect gate (rules.yaml: observability).

Enforces the invariant: every top-level key an ArgoCD Application passes to a Helm
chart via `spec.source.helm.valuesObject` must actually DO something.

WHY THIS EXISTS (#4584). Helm silently discards a values key the chart does not
read. There is no error, no warning, no non-zero exit, and — because the rendered
manifest simply never contains the thing you asked for — no ArgoCD diff either.
The committed file goes on asserting the configuration in plain YAML, with a
comment next to it explaining the behaviour it does not have, and every layer that
could contradict it agrees: `Synced`, `Healthy`, green.

`apps/argo-rollouts.yaml` spelled `replicaCount`, `resources` and `metrics` at the
top level. The chart defines all three under `controller.*`. So for the whole life
of that file:

  * `controller.metrics.serviceMonitor` was never enabled, so the chart never
    created the `argo-rollouts-metrics` Service or its ServiceMonitor, so NOTHING
    scraped the controller, so Prometheus held ZERO `rollout_*` series;
  * three alerts — RolloutNoAvailableReplicas, RolloutProgressDeadlineExceeded,
    RolloutAnalysisAborted — evaluated over metric families that did not exist and
    therefore covered 0 of 21 Rollouts, every one of them money-path;
  * the controller ran with no resource requests at all (a Karpenter bin-packing
    hazard) and at 2 replicas while the file said 1.

None of that is visible by reading the file, which is why it is a gate. The
failure mode it guards is the repo's most-repeated one in a new dress: a control
that reports healthy about a subject set it never reached. An alert over an absent
metric is not an error in Prometheus — it is an empty result, indistinguishable in
the API, the UI and the rule's own `health: ok` field from "everything is fine".

HOW IT DECIDES, and why it is not a schema check. The obvious implementation —
"every key must appear in the chart's values.yaml" — was built first and measured:
it false-positives. Charts legitimately read values they never declare
(`prometheus-blackbox-exporter` honours `fullnameOverride` without listing it), and
they legitimately accept free-form sub-trees (`grafana.ini`, `config.exporters.*`,
`loki.limits_config.*`), which produced 15-of-20 apps flagged. A gate that fires on
three quarters of the fleet teaches people to ignore it.

So this measures EFFECT, not declaration. A key is reported only when BOTH hold:

  1. it is absent from the chart's own values.yaml, AND
  2. rendering the chart with the full valuesObject and rendering it again with
     that one key REMOVED produce byte-identical output.

Condition 2 is the real test; condition 1 only keeps the cost down and, more
importantly, stops the gate from flagging a key that is merely REDUNDANT — one
whose value happens to equal the chart default (`installCRDs: true`) is inert on
removal too, but it is declared, correct, and not a bug. Measured over the fleet
this pair yields zero false positives.

Only TOP-LEVEL keys are checked. That is where a chart's schema is genuinely
exhaustive and where a mis-nesting silently voids an entire block; deeper keys are
where the free-form sub-trees live, and the same rule there is noise.

Requires `helm` on PATH and network access to the chart repositories. Both are
treated as hard requirements: if a chart cannot be pulled, this gate FAILS rather
than passing quietly, because "could not check" and "checked and clean" must never
render the same — that equivalence is the whole defect class above.

Usage:
    check-helm-values-keys.py              # check the fleet
    check-helm-values-keys.py --self-test  # offline detector self-test (no network)
"""

from __future__ import annotations

import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
APPS_DIR = REPO / "openbank-infra" / "gitops" / "apps"

# Keys measured as ignored by their chart that this PR does not fix, each with the
# spelling the chart actually wants. They are unrelated to the Rollout alerting gap
# (#4584) and fixing them means restarting those workloads, so they are declared
# here and tracked rather than swept in silently. An entry that becomes covered
# (i.e. the key starts having an effect, or disappears from the manifest) FAILS
# this gate, so a baseline cannot quietly become permanent.
KNOWN_IGNORED: dict[str, dict[str, str]] = {
    "pyroscope.yaml": {
        "fullnameOverride": "chart nests it as `pyroscope.fullnameOverride`",
    },
    "vpa.yaml": {
        "crds": "chart (cowboysysop/vpa) has no CRD-install toggle at all",
        "podPriorityClassName": "chart spells it `priorityClassName`",
        "tolerations": "chart takes it per-component, e.g. `recommender.tolerations`",
    },
}


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def _render(chart_dir: str, values: dict) -> str | None:
    """Render chart_dir with `values`; None if helm failed."""
    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as fh:
        yaml.safe_dump(values, fh)
        values_path = fh.name
    try:
        proc = subprocess.run(
            ["helm", "template", "gate-probe", chart_dir, "-f", values_path],
            capture_output=True,
            text=True,
        )
    finally:
        pathlib.Path(values_path).unlink(missing_ok=True)
    return proc.stdout if proc.returncode == 0 else None


def ignored_top_level_keys(chart_dir: str, values: dict) -> list[str] | None:
    """Top-level keys of `values` that the chart at chart_dir demonstrably ignores."""
    schema_path = pathlib.Path(chart_dir) / "values.yaml"
    schema = yaml.safe_load(schema_path.read_text()) if schema_path.exists() else {}
    schema = schema or {}

    baseline = _render(chart_dir, values)
    if baseline is None:
        return None
    baseline_sha = _sha(baseline)

    ignored = []
    for key in values:
        if key in schema:
            continue  # declared by the chart — not the defect this gate hunts
        trimmed = {k: v for k, v in values.items() if k != key}
        rendered = _render(chart_dir, trimmed)
        if rendered is None:
            return None
        if _sha(rendered) == baseline_sha:
            ignored.append(key)
    return ignored


def iter_helm_sources():
    """Yield (manifest_name, chart, repo_url, version, values_object)."""
    for path in sorted(APPS_DIR.glob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(path.read_text()))
        except yaml.YAMLError as exc:
            print(f"FAIL {path.name}: unparseable YAML: {exc}", file=sys.stderr)
            sys.exit(1)
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "Application":
                continue
            spec = doc.get("spec", {})
            sources = spec.get("sources") or ([spec["source"]] if "source" in spec else [])
            for src in sources:
                values = (src.get("helm") or {}).get("valuesObject")
                chart = src.get("chart")
                if not values or not chart:
                    continue  # path-based or values-less source: nothing to check
                yield path.name, chart, src.get("repoURL"), str(src.get("targetRevision", "")), values


def check_fleet() -> int:
    if shutil.which("helm") is None:
        print("FAIL: `helm` is not on PATH — cannot verify, and 'unchecked' must not "
              "look like 'clean'.", file=sys.stderr)
        return 1

    cache = pathlib.Path(tempfile.mkdtemp(prefix="helm-values-gate-"))
    findings: list[str] = []
    stale: list[str] = []
    checked = 0

    try:
        for name, chart, repo_url, version, values in iter_helm_sources():
            chart_root = cache / f"{chart}-{version}"
            if not chart_root.exists():
                pull = subprocess.run(
                    ["helm", "pull", chart, "--repo", repo_url, "--version", version,
                     "--untar", "--untardir", str(chart_root)],
                    capture_output=True, text=True,
                )
                if pull.returncode != 0:
                    print(f"FAIL {name}: could not pull {chart}@{version} from {repo_url}: "
                          f"{pull.stderr.strip()[:300]}", file=sys.stderr)
                    return 1

            chart_yamls = list(chart_root.glob("*/Chart.yaml"))
            if not chart_yamls:
                print(f"FAIL {name}: pulled {chart}@{version} but found no Chart.yaml",
                      file=sys.stderr)
                return 1

            ignored = ignored_top_level_keys(str(chart_yamls[0].parent), values)
            if ignored is None:
                print(f"FAIL {name}: `helm template` failed for {chart}@{version}",
                      file=sys.stderr)
                return 1
            checked += 1

            baselined = KNOWN_IGNORED.get(name, {})
            for key in ignored:
                if key not in baselined:
                    findings.append(
                        f"{name}: valuesObject key '{key}' is IGNORED by {chart}@{version} "
                        f"— Helm discards it silently; find the key's real nesting in the "
                        f"chart's values.yaml"
                    )
            for key, reason in baselined.items():
                if key not in ignored:
                    stale.append(
                        f"{name}: '{key}' is declared in KNOWN_IGNORED ({reason}) but is no "
                        f"longer ignored — remove the baseline entry"
                    )
    finally:
        shutil.rmtree(cache, ignore_errors=True)

    for line in findings + stale:
        print(f"FAIL {line}", file=sys.stderr)

    if findings or stale:
        return 1

    total_baselined = sum(len(v) for v in KNOWN_IGNORED.values())
    print(f"PASS helm valuesObject key-effect gate: {checked} Helm source(s) checked, "
          f"0 silently-ignored keys ({total_baselined} baselined in KNOWN_IGNORED).")
    # Declare the subject count so run-gates.py can enforce min_subjects. Without it a
    # renamed apps/ directory or a changed glob turns this gate into a no-op that still
    # exits 0 — which is precisely the shape of defect it was written to catch.
    gatelib.subjects(checked, "Helm source(s) with valuesObject")
    return 0


def self_test() -> int:
    """Offline detector self-test: a synthetic chart with one honoured and one ignored key.

    This is what makes a PASS from this gate mean something. The gate's own failure
    mode is that it reaches nothing and prints success, so the detector is exercised
    against a case it MUST flag and a case it MUST NOT — no network, no fixtures.
    """
    if shutil.which("helm") is None:
        print("FAIL self-test: `helm` is not on PATH", file=sys.stderr)
        return 1

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="helm-values-selftest-"))
    try:
        chart = tmp / "probe"
        (chart / "templates").mkdir(parents=True)
        (chart / "Chart.yaml").write_text(
            "apiVersion: v2\nname: probe\nversion: 0.0.1\n"
        )
        # The chart declares and reads `honoured`, and reads nothing else.
        (chart / "values.yaml").write_text("honoured: default\n")
        (chart / "templates" / "cm.yaml").write_text(
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: probe\n"
            "data:\n  honoured: {{ .Values.honoured | quote }}\n"
        )

        values = {"honoured": "set-by-operator", "ignoredKey": {"enabled": True}}
        ignored = ignored_top_level_keys(str(chart), values)

        if ignored is None:
            print("FAIL self-test: helm template failed on the synthetic chart", file=sys.stderr)
            return 1
        # MUST flag the key the chart never reads...
        if "ignoredKey" not in ignored:
            print("FAIL self-test: detector did not flag 'ignoredKey' (known positive) — "
                  "the gate cannot see the defect it exists for", file=sys.stderr)
            return 1
        # ...and MUST NOT flag the key it does read.
        if "honoured" in ignored:
            print("FAIL self-test: detector flagged 'honoured' (known negative)", file=sys.stderr)
            return 1

        print("PASS self-test: detector flags an ignored key and clears an honoured one.")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(check_fleet())
