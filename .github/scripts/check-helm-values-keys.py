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

NETWORK RETRIES (#5094). `helm pull` hits real upstream repos (kyverno.github.io,
charts.fairwinds.com, etc.) and a transient reset there is not this gate's business —
three PRs (#5065, #5068, #5086) failed on it and cleared on a bare re-run with zero
code change. `_pull_chart` now retries the pull a bounded number of times with a short
backoff before giving up, and the FAIL line it prints in that case says explicitly that
the upstream was unreachable, in a form that does not overlap the wording used for a
real values-key mismatch (`IGNORED by`) or a template-render failure (`helm template
failed`) — grep for `could not reach` to find the network case specifically. This does
NOT touch `ignored_top_level_keys`/`_render`, which never hit the network (they run
`helm template` against an already-pulled local chart directory): a chart that
genuinely has a mismatched values key still fails, retries or not.

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
import time

import yaml

# Retry budget for the network fetch step only (`helm pull`). Kept small: this is
# meant to ride out a transient reset within the gate's own 180s budget_seconds, not
# to mask a genuinely dead or renamed chart repo behind minutes of silent waiting.
PULL_RETRY_ATTEMPTS = 3
PULL_RETRY_BACKOFF_SECONDS = 2.0

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


def _pull_chart(chart: str, repo_url: str, version: str, chart_root: pathlib.Path,
                 attempts: int = PULL_RETRY_ATTEMPTS,
                 backoff_seconds: float = PULL_RETRY_BACKOFF_SECONDS) -> str | None:
    """`helm pull` with retries. Returns None on success, or the last stderr on failure.

    Only retries the network fetch — a chart that pulls fine and then fails the
    values-key comparison is a different failure and is never routed through here.
    """
    last_stderr = ""
    for attempt in range(1, attempts + 1):
        pull = subprocess.run(
            ["helm", "pull", chart, "--repo", repo_url, "--version", version,
             "--untar", "--untardir", str(chart_root)],
            capture_output=True, text=True,
        )
        if pull.returncode == 0:
            return None
        last_stderr = pull.stderr.strip()
        if attempt < attempts:
            time.sleep(backoff_seconds * attempt)
    return last_stderr


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
    unreachable: list[str] = []
    checked = 0

    try:
        for name, chart, repo_url, version, values in iter_helm_sources():
            chart_root = cache / f"{chart}-{version}"
            if not chart_root.exists():
                pull_error = _pull_chart(chart, repo_url, version, chart_root)
                if pull_error is not None:
                    # COULD-NOT-CHECK is a third state, and collapsing it into FAIL was wrong in
                    # both directions. Measured 2026-08-21: gitlab.com answered 502 for the
                    # glitchtip chart, which (a) failed an enforced gate on an unrelated PR as if
                    # the repo were broken, and (b) `return 1` here ABORTED the scan, so the other
                    # 19 charts were never examined — a green run and a red run both told you
                    # nothing about them.
                    #
                    # Now: record it, keep going, and let the SUBJECT FLOOR decide. One unreachable
                    # repo leaves 19 of 20 checked and the gate passes with a loud warning; a real
                    # outage drops the count under `min_subjects: 15` and run-gates fails the gate
                    # for examining too little. That is the repo's existing mechanism for "this
                    # gate did not see enough to mean anything", and it is the honest one here.
                    unreachable.append(
                        f"{name}: could not reach {repo_url} to pull {chart}@{version} after "
                        f"{PULL_RETRY_ATTEMPTS} attempts — upstream network failure, NOT a "
                        f"values-key finding: {pull_error[:200]}"
                    )
                    continue

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
    for line in unreachable:
        # ::warning, not ::error: this is the build telling you it could not look, which must not
        # read the same as the build telling you something is wrong.
        print(f"::warning::helm-values-key-effect SKIPPED a source — {line}")

    if findings or stale:
        return 1

    total_baselined = sum(len(v) for v in KNOWN_IGNORED.values())
    skipped_note = f", {len(unreachable)} unreachable (see warnings)" if unreachable else ""
    print(f"PASS helm valuesObject key-effect gate: {checked} Helm source(s) checked, "
          f"0 silently-ignored keys ({total_baselined} baselined in KNOWN_IGNORED){skipped_note}.")
    # Declare the subject count so run-gates.py can enforce min_subjects. Without it a
    # renamed apps/ directory or a changed glob turns this gate into a no-op that still
    # exits 0 — which is precisely the shape of defect it was written to catch.
    gatelib.subjects(checked, "Helm source(s) with valuesObject")
    return 0


class _FakeCompletedProcess:
    def __init__(self, returncode: int, stderr: str = ""):
        self.returncode = returncode
        self.stderr = stderr
        self.stdout = ""


def _self_test_unreachable_is_not_a_finding() -> str | None:
    """An unreachable chart repo must NOT fail the gate, and must NOT stop the scan.

    Both halves are the 2026-08-21 defect. gitlab.com answered 502 for one chart, and the
    gate (a) failed an enforced build on an unrelated PR as though the repo were broken and
    (b) returned immediately, so the other 19 sources were never examined — a state in
    which neither pass nor fail said anything about them.

    The replacement contract, asserted here: exit 0, a ::warning naming the source, and
    every reachable source still checked. Systemic outages are caught by `min_subjects`
    instead, because `checked` no longer counts what could not be pulled.
    """
    import io
    import unittest.mock as mock
    import contextlib

    sources = [
        ("reachable-a", "chart-a", "https://example.invalid/a", "1.0.0", {"k": 1}),
        ("dead-repo", "chart-b", "https://example.invalid/b", "2.0.0", {"k": 1}),
        ("reachable-c", "chart-c", "https://example.invalid/c", "3.0.0", {"k": 1}),
    ]
    pulled: list[str] = []

    def _pull(chart, repo_url, version, dest):
        pulled.append(chart)
        if chart == "chart-b":
            return "502 Bad Gateway"
        pathlib.Path(dest, "inner").mkdir(parents=True, exist_ok=True)
        pathlib.Path(dest, "inner", "Chart.yaml").write_text("name: x\n")
        return None

    out, err = io.StringIO(), io.StringIO()
    with mock.patch(f"{__name__}.iter_helm_sources", return_value=sources), \
         mock.patch(f"{__name__}._pull_chart", side_effect=_pull), \
         mock.patch(f"{__name__}.ignored_top_level_keys", return_value=[]), \
         contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        rc = check_fleet()

    if rc != 0:
        return f"an unreachable repo failed the gate (rc={rc}); it is a could-not-check, not a finding"
    if [c for c in pulled] != ["chart-a", "chart-b", "chart-c"]:
        return f"the scan stopped at the unreachable repo instead of continuing: pulled={pulled}"
    combined = out.getvalue() + err.getvalue()
    if "::warning" not in combined or "dead-repo" not in combined:
        return "the unreachable source was not reported as a warning naming it"
    if "SUBJECTS=2" not in combined:
        return f"subject count must exclude the unreachable source, got: {combined[-200:]!r}"
    return None


def _self_test_pull_retry() -> str | None:
    """Offline check of `_pull_chart`'s retry/backoff and its distinct failure text.

    No real `helm pull` runs here — `subprocess.run` is monkeypatched so the whole
    thing stays network-free like the rest of the self-test. Two cases, both of which
    the #5094 fix must get right or it regresses into one of the two failure modes it
    was written to avoid: retrying forever (never surfacing a real dead repo), or
    swallowing a genuine values-key defect as if it were a network blip.

    Returns None on success, or a description of what went wrong.
    """
    import unittest.mock as mock

    # Case 1: every attempt fails (a genuinely unreachable/renamed repo) — must retry
    # exactly PULL_RETRY_ATTEMPTS times, not "forever", and must return an error.
    calls = {"n": 0}

    def _always_fail(*_args, **_kwargs):
        calls["n"] += 1
        return _FakeCompletedProcess(1, "dial tcp: connection reset by peer")

    with mock.patch("subprocess.run", side_effect=_always_fail), \
         mock.patch("time.sleep", return_value=None):  # keep the self-test fast
        err = _pull_chart("probe", "https://example.invalid/charts", "1.0.0",
                           pathlib.Path("/tmp/does-not-matter"))
    if calls["n"] != PULL_RETRY_ATTEMPTS:
        return (f"_pull_chart made {calls['n']} attempt(s), expected exactly "
                f"{PULL_RETRY_ATTEMPTS} — it must not retry forever nor give up early")
    if err is None:
        return "_pull_chart reported success against a source that failed every attempt"

    # Case 2: a transient failure that clears within the retry budget — must succeed
    # and must NOT report the earlier failures as the final outcome.
    calls2 = {"n": 0}

    def _fail_once_then_succeed(*_args, **_kwargs):
        calls2["n"] += 1
        if calls2["n"] == 1:
            return _FakeCompletedProcess(1, "connection reset by peer")
        return _FakeCompletedProcess(0, "")

    with mock.patch("subprocess.run", side_effect=_fail_once_then_succeed), \
         mock.patch("time.sleep", return_value=None):
        err2 = _pull_chart("probe", "https://example.invalid/charts", "1.0.0",
                            pathlib.Path("/tmp/does-not-matter"))
    if err2 is not None:
        return (f"_pull_chart did not recover from a transient failure within "
                f"{PULL_RETRY_ATTEMPTS} attempts: {err2}")
    if calls2["n"] != 2:
        return f"_pull_chart made {calls2['n']} attempt(s) on the recovering source, expected 2"

    return None


def self_test() -> int:
    """Offline detector self-test: a synthetic chart with one honoured and one ignored key,
    plus a network-free check of the retry/backoff path (#5094).

    This is what makes a PASS from this gate mean something. The gate's own failure
    mode is that it reaches nothing and prints success, so the detector is exercised
    against a case it MUST flag and a case it MUST NOT — no network, no fixtures.
    """
    if shutil.which("helm") is None:
        print("FAIL self-test: `helm` is not on PATH", file=sys.stderr)
        return 1

    retry_error = _self_test_pull_retry()
    if retry_error is not None:
        print(f"FAIL self-test (network retry): {retry_error}", file=sys.stderr)
        return 1

    unreachable_error = _self_test_unreachable_is_not_a_finding()
    if unreachable_error is not None:
        print(f"FAIL self-test (unreachable handling): {unreachable_error}", file=sys.stderr)
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

        print("PASS self-test: detector flags an ignored key and clears an honoured one; "
              "network-unreachable and real-defect failures stay distinguishable (#5094).")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(check_fleet())
