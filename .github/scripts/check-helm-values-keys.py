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

NETWORK RETRIES (#5094). Fetching a chart reaches real upstream chart repositories and a
transient reset there is not this gate's business —
three PRs (#5065, #5068, #5086) failed on it and cleared on a bare re-run with zero
code change. `_pull_chart` now retries the pull a bounded number of times with a short
backoff before giving up, and the FAIL line it prints in that case says explicitly that
the upstream was unreachable, in a form that does not overlap the wording used for a
real values-key mismatch (`IGNORED by`) or a template-render failure (`helm template
failed`) — grep for `could not reach` to find the network case specifically. This does
NOT touch `ignored_top_level_keys`/`_render`, which never hit the network (they run
`helm template` against an already-pulled local chart directory): a chart that
genuinely has a mismatched values key still fails, retries or not.

CHART SOURCE (#15216). Charts are no longer pulled with `helm pull`: each one is fetched through
`fetch-artifact.py` from the entry `helm-chart/<chart>` in `ci/artifacts.lock`, sha256-verified,
from the Actions cache, the mirror or upstream in that order. A chart whose (name, version) is not
in the lock is a hard FAIL — bump the lock with the targetRevision. A chart no tier could deliver
is the THIRD state: `UNVERIFIED (artifact unavailable)`, printed distinctly from a finding, and
the gate still exits non-zero (75) because a required check that could not look must not pass.
The scan continues past it, so every reachable chart is still checked in the same run.

Usage:
    check-helm-values-keys.py              # check the fleet
    check-helm-values-keys.py --self-test  # offline detector self-test (no network)
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time

import yaml

# Retry budget for the network fetch step only (`helm pull`). Kept small: this is
# meant to ride out a transient reset within the gate's own 180s budget_seconds, not
# to mask a genuinely dead or renamed chart repo behind minutes of silent waiting.
PULL_RETRY_ATTEMPTS = 3
PULL_RETRY_BACKOFF_SECONDS = 2.0
# Wall-clock budget for ALL chart fetches in one run. run-gates.py kills a gate at 420 s; when
# both GitHub routes are down every chart costs ~40 s (a 30 s web timeout plus the API's 504),
# so 12 GitHub-hosted charts alone would overrun it and the gate would die as a TIMEOUT instead
# of reporting UNVERIFIED. Past the budget, remaining charts are not fetched and are reported
# UNVERIFIED immediately; renders of the charts already fetched still run (#15220 measured the
# working-API case at 88 s, well inside this).
FETCH_BUDGET_SECONDS = 240.0
_FETCH_DEADLINE: list[float] = [float("inf")]
# Set once a chart has exhausted every attempt as UNVERIFIED: the outage is real, so later
# charts get one resolver call each instead of PULL_RETRY_ATTEMPTS.
_OUTAGE: list[bool] = [False]


def _github_hosted(chart: str, version: str, lock_path: pathlib.Path | None = None) -> bool:
    """True when the lock's upstream for (chart, version) is a GitHub release asset."""
    for entry in json.loads((lock_path or LOCK).read_text()).get("artifacts", []):
        if entry.get("name") == f"helm-chart/{chart}" and str(entry.get("version")) == version:
            return any("github.com/" in (p.get("url") or "") for p in entry.get("platforms", {}).values())
    return False

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
APPS_DIR = REPO / "openbank-infra" / "gitops" / "apps"
LOCK = REPO / "ci" / "artifacts.lock"
RESOLVER = REPO / ".github" / "scripts" / "fetch-artifact.py"
UNVERIFIED_MARK = "UNVERIFIED (artifact unavailable)"
_PREFER_API = False
EXIT_UNVERIFIED = 75

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


def lock_name(chart: str, version: str, lock_path: pathlib.Path = LOCK) -> str | None:
    """The lock entry that pins (chart, version), or None when the lock does not cover it."""
    name = f"helm-chart/{chart}"
    for entry in json.loads(lock_path.read_text()).get("artifacts", []):
        if entry.get("name") == name and str(entry.get("version")) == version:
            return name
    return None


def _pull_chart(chart: str, repo_url: str, version: str, chart_root: pathlib.Path,
                 attempts: int = PULL_RETRY_ATTEMPTS,
                 backoff_seconds: float = PULL_RETRY_BACKOFF_SECONDS) -> str | None:
    """Fetch the chart through the resolver and untar it. None on success, else the error text.

    An error containing UNVERIFIED_MARK means no tier could deliver the archive (the third
    state); any other error is a hard failure (not in the lock, sha256 mismatch, bad archive).
    Only the resolver's "unavailable" outcome is retried — an integrity failure never heals.
    """
    name = lock_name(chart, version)
    if name is None:
        return (f"{chart}@{version} (repo {repo_url}) is not pinned in ci/artifacts.lock — add or "
                f"bump the `helm-chart/{chart}` entry with the targetRevision")
    archive = chart_root.parent / f".{chart}-{version}.tgz"
    last = ""
    if _OUTAGE[0]:
        attempts = 1
    for attempt in range(1, attempts + 1):
        env = {**os.environ, "OB_ARTIFACT_RETRIES": "1"}
        if _PREFER_API:
            env["OB_GITHUB_PREFER_API"] = "1"
        proc = subprocess.run(
            [sys.executable, "-I", str(RESOLVER), name, "--platform", "any", "--out", str(archive)],
            capture_output=True, text=True, env=env,
        )
        if proc.returncode == 0 and "served by the REST API asset route" in (proc.stderr or ""):
            # Lesson of #15220: once the web route is down and the API route works, use the API
            # first for the rest of the run, or a 21-chart scan exceeds the 420 s hard timeout.
            globals()["_PREFER_API"] = True
        if proc.returncode == 0:
            try:
                chart_root.mkdir(parents=True, exist_ok=True)
                with tarfile.open(archive) as tar:
                    tar.extractall(chart_root, filter="data")
            except (tarfile.TarError, OSError) as exc:
                return f"{chart}@{version}: verified archive did not unpack: {exc}"
            finally:
                archive.unlink(missing_ok=True)
            return None
        last = ((proc.stdout or "") + (proc.stderr or "")).strip()
        if proc.returncode != EXIT_UNVERIFIED:
            return f"{chart}@{version}: resolver exit {proc.returncode}: {last[-300:]}"
        if time.monotonic() >= _FETCH_DEADLINE[0]:
            break  # out of fetch budget: report UNVERIFIED rather than die on the hard timeout
        if attempt < attempts:
            time.sleep(backoff_seconds * attempt)
    _OUTAGE[0] = True
    return f"{UNVERIFIED_MARK}: {chart}@{version} from {repo_url}: {last[-300:]}"


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
    _FETCH_DEADLINE[0] = time.monotonic() + FETCH_BUDGET_SECONDS
    _OUTAGE[0] = False
    findings: list[str] = []
    stale: list[str] = []
    unreachable: list[str] = []
    checked = 0

    try:
        # Non-GitHub sources first (stable within each group): when a GitHub outage spends the
        # fetch budget, the charts that do not depend on GitHub have already been checked.
        sources = sorted(iter_helm_sources(), key=lambda s: _github_hosted(s[1], s[3]))
        for name, chart, repo_url, version, values in sources:
            chart_root = cache / f"{chart}-{version}"
            if not chart_root.exists():
                if time.monotonic() >= _FETCH_DEADLINE[0]:
                    pull_error = (f"{UNVERIFIED_MARK}: {chart}@{version} not attempted — the run's "
                                  f"{FETCH_BUDGET_SECONDS:.0f} s fetch budget is spent (upstream outage)")
                else:
                    pull_error = _pull_chart(chart, repo_url, version, chart_root)
                if pull_error is not None and UNVERIFIED_MARK not in pull_error:
                    print(f"FAIL {name}: {pull_error}", file=sys.stderr)
                    return 1
                if pull_error is not None:
                    # COULD-NOT-CHECK is a third state (2026-08-21: gitlab.com 502; 2026-11-08:
                    # GitHub Releases 504). It must not read as a finding, and it must not stop the
                    # scan — every reachable chart is still examined. But since #15216 it is no
                    # longer a pass either: a required check that could not look must not go green,
                    # so the run ends UNVERIFIED (exit 75) after the scan, worded so that grepping
                    # for `IGNORED by` or `helm template failed` never matches it.
                    unreachable.append(
                        f"{name}: {chart}@{version} — upstream/mirror/cache all unavailable, NOT a "
                        f"values-key finding: {pull_error[:300]}"
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
        # Infrastructure, not a finding: labelled UNVERIFIED so it never reads like `IGNORED by`.
        print(f"{UNVERIFIED_MARK}: helm-values-key-effect could not check {line}")

    if findings or stale:
        gatelib.subjects(checked, "Helm source(s) with valuesObject")
        return 1
    if unreachable:
        print(f"UNVERIFIED helm valuesObject key-effect gate: {checked} Helm source(s) checked, "
              f"{len(unreachable)} could not be fetched — infrastructure, not a finding; failing "
              f"because an unchecked chart must not pass.")
        gatelib.subjects(checked, "Helm source(s) with valuesObject")
        return EXIT_UNVERIFIED

    total_baselined = sum(len(v) for v in KNOWN_IGNORED.values())
    skipped_note = ""
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
    """An unreachable chart is the THIRD state: not a finding, not a pass, and not a scan-stopper.

    History: on 2026-08-21 gitlab.com answered 502 for one chart and the gate (a) failed as
    though the repo were broken and (b) returned immediately, so the other 19 sources were never
    examined. The #5094-era fix made it a warning and a pass. #15216 keeps (b) — every
    reachable source is still checked — and replaces the pass with UNVERIFIED, exit 75: a
    required check that could not look must not go green, and must not read like a finding.
    A hard resolver error (not in the lock, digest mismatch) is a plain FAIL, exit 1.
    """
    import io
    import unittest.mock as mock
    import contextlib

    sources = [
        ("reachable-a", "chart-a", "https://example.invalid/a", "1.0.0", {"k": 1}),
        ("dead-repo", "chart-b", "https://example.invalid/b", "2.0.0", {"k": 1}),
        ("reachable-c", "chart-c", "https://example.invalid/c", "3.0.0", {"k": 1}),
    ]

    def run(failure: str):
        pulled: list[str] = []

        def _pull(chart, repo_url, version, dest):
            pulled.append(chart)
            if chart == "chart-b":
                return failure
            pathlib.Path(dest, "inner").mkdir(parents=True, exist_ok=True)
            pathlib.Path(dest, "inner", "Chart.yaml").write_text("name: x\n")
            return None

        out, err = io.StringIO(), io.StringIO()
        with mock.patch(f"{__name__}.iter_helm_sources", return_value=sources), \
             mock.patch(f"{__name__}._pull_chart", side_effect=_pull), \
             mock.patch(f"{__name__}.ignored_top_level_keys", return_value=[]), \
             contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = check_fleet()
        return rc, pulled, out.getvalue() + err.getvalue()

    rc, pulled, combined = run(f"{UNVERIFIED_MARK}: chart-b@2.0.0: HTTP 504")
    if rc != EXIT_UNVERIFIED:
        return f"an unavailable chart must end UNVERIFIED (rc={EXIT_UNVERIFIED}), got rc={rc}"
    if pulled != ["chart-a", "chart-b", "chart-c"]:
        return f"the scan stopped at the unreachable repo instead of continuing: pulled={pulled}"
    if UNVERIFIED_MARK not in combined or "dead-repo" not in combined:
        return "the unavailable source was not reported as UNVERIFIED naming it"
    if "IGNORED by" in combined:
        return "an unavailable chart was worded like a values-key finding"
    if "SUBJECTS=2" not in combined:
        return f"subject count must exclude the unreachable source, got: {combined[-200:]!r}"

    with mock.patch(f"{__name__}.FETCH_BUDGET_SECONDS", 0.0):
        rc, pulled, combined = run("unused")
    if rc != EXIT_UNVERIFIED or pulled or "fetch budget is spent" not in combined:
        return (f"a spent fetch budget must report every chart UNVERIFIED without fetching it "
                f"(rc={rc}, pulled={pulled})")

    rc, _, combined = run("chart-b@2.0.0 is not pinned in ci/artifacts.lock")
    if rc != 1 or UNVERIFIED_MARK in combined:
        return f"a hard resolver error must FAIL (rc=1) and not read as UNVERIFIED, got rc={rc}"
    return None


def _self_test_pull_retry() -> str | None:
    """Offline check of `_pull_chart`'s retry/backoff and its distinct failure text (#5094).

    `subprocess.run` (the resolver call) is monkeypatched, so nothing touches the network.
    Retries apply ONLY to the resolver's "unavailable" exit (75): it must stop after exactly
    PULL_RETRY_ATTEMPTS, recover from a transient failure, and never retry an integrity error.
    """
    import io
    import unittest.mock as mock

    def _tgz(path: str) -> None:
        payload = b"apiVersion: v2\nname: probe\nversion: 1.0.0\n"
        info = tarfile.TarInfo("probe/Chart.yaml")
        info.size = len(payload)
        with tarfile.open(path, "w:gz") as tar:
            tar.addfile(info, io.BytesIO(payload))

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="helm-pull-selftest-"))
    try:
        with mock.patch(f"{__name__}.lock_name", return_value="helm-chart/probe"), \
             mock.patch("time.sleep", return_value=None):
            # Case 1: unavailable on every attempt.
            _OUTAGE[0] = False
            calls = {"n": 0}

            def _always_unavailable(*_a, **_k):
                calls["n"] += 1
                return _FakeCompletedProcess(EXIT_UNVERIFIED, "UNVERIFIED (artifact unavailable): x")

            with mock.patch("subprocess.run", side_effect=_always_unavailable):
                err = _pull_chart("probe", "https://example.invalid/c", "1.0.0", tmp / "one")
            if calls["n"] != PULL_RETRY_ATTEMPTS:
                return (f"_pull_chart made {calls['n']} attempt(s), expected exactly "
                        f"{PULL_RETRY_ATTEMPTS} — it must not retry forever nor give up early")
            if err is None or UNVERIFIED_MARK not in err:
                return f"an always-unavailable chart must return an UNVERIFIED error, got {err!r}"

            # Case 1b: once one chart exhausted its attempts, the outage is established and the
            # next chart gets a single call, so a GitHub outage cannot multiply the run time.
            calls["n"] = 0
            with mock.patch("subprocess.run", side_effect=_always_unavailable):
                _pull_chart("probe", "https://example.invalid/c", "1.0.0", tmp / "one-b")
            if calls["n"] != 1:
                return f"after an established outage a chart made {calls['n']} attempts, expected 1"

            # Case 2: transient, then success — must recover and unpack the verified archive.
            _OUTAGE[0] = False
            calls2 = {"n": 0}

            def _fail_once_then_succeed(cmd, *_a, **_k):
                calls2["n"] += 1
                if calls2["n"] == 1:
                    return _FakeCompletedProcess(EXIT_UNVERIFIED, "unavailable")
                _tgz(cmd[cmd.index("--out") + 1])
                return _FakeCompletedProcess(0, "")

            with mock.patch("subprocess.run", side_effect=_fail_once_then_succeed):
                err2 = _pull_chart("probe", "https://example.invalid/c", "1.0.0", tmp / "two")
            if err2 is not None or calls2["n"] != 2:
                return f"_pull_chart did not recover from one transient failure: {err2!r}, calls={calls2['n']}"
            if not (tmp / "two" / "probe" / "Chart.yaml").is_file():
                return "_pull_chart reported success without unpacking the chart"

            # Case 3: integrity failure — never retried, never reported as UNVERIFIED.
            _OUTAGE[0] = False
            calls3 = {"n": 0}

            def _mismatch(*_a, **_k):
                calls3["n"] += 1
                return _FakeCompletedProcess(65, "INTEGRITY FAILURE")

            with mock.patch("subprocess.run", side_effect=_mismatch):
                err3 = _pull_chart("probe", "https://example.invalid/c", "1.0.0", tmp / "three")
            if calls3["n"] != 1 or err3 is None or UNVERIFIED_MARK in err3:
                return f"a digest mismatch was retried or worded as unavailable: calls={calls3['n']} {err3!r}"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
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

    # Every Helm source the fleet scan reads must be pinned in the lock, or the real run FAILs
    # on it; and a chart the lock does not hold must not resolve (known negative).
    uncovered = [f"{c}@{v}" for _, c, _, v, _ in iter_helm_sources() if lock_name(c, v) is None]
    if uncovered:
        print(f"FAIL self-test (lock coverage): not in ci/artifacts.lock: {uncovered}", file=sys.stderr)
        return 1
    if lock_name("no-such-chart", "0.0.0") is not None:
        print("FAIL self-test (lock coverage): an unpinned chart resolved", file=sys.stderr)
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
