#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render the escalation issue body for a failed `perf-gate.yml` run (ADR-0243 D2).

WHY THIS EXISTS
---------------
ADR-0243 decision 2 and `perf-gate.yml`'s own header both say a threshold breach
during the advisory phase "creates issues rather than blocking merges". The merged
workflow had no such step: a breach just turned the weekly scheduled run red, and a
red push/schedule-triggered workflow on `main` is addressed to nobody. This repo has
already paid for that failure mode once — `dependency-submission.yml` died of
`Java heap space` for three days, red every run, while its consumers quietly read a
stale graph (issue #1449). The established remedy is a raise-or-refresh issue job,
as `fleet-attestation.yml` and `dependency-submission.yml` both carry; this script is
only the body renderer for perf-gate's copy of that same shape.

WHY A SCRIPT AND NOT AN INLINE `run:` BLOCK
-------------------------------------------
Two reasons, both load-bearing in this repo:
  * Prose in a `run:` block is charged against a hard ceiling. An oversized `run:`
    step makes the WHOLE workflow file unparseable, with no error from GitHub — every
    push then yields a run with zero jobs, killing the workflow for every contributor
    (measured boundary: 20054 chars accepted, 20654 rejected; `gates.yaml` enforces
    19000 via `check-workflow-run-step-size.py`). Reasoning belongs in a script header.
  * A `run:` block can only ever be syntax-checked by its own CI. This file has a
    `--self-test` so the failure path is exercised before it is trusted.

READING k6's `--summary-export` JSON
------------------------------------
`metrics.<name>.thresholds` maps a threshold expression to a BOOLEAN, and the
polarity is the one that reads backwards: **true means the threshold was CROSSED
(breached, `✗` in k6's own console output); false means it passed (`✓`)**. That is
not inferred from documentation — it was measured against a real k6 run in which a
single metric carried one passing and one failing threshold simultaneously:

    http_req_duration {"p(95)<0.0001": true, "p(95)<60000": false}
    ...console:  ✗ 'p(95)<0.0001' p(95)=1.84ms
                 ✓ 'p(95)<60000'  p(95)=1.84ms

Both polarities in one observation, so the mapping cannot be a coincidence of which
way that particular run went. A second control (all requests refused, so
`http_req_duration` had no samples) showed the same metric reporting `false` for an
impossibly tight bound — consistent, since a metric with no samples crosses nothing.

The measurement was taken on k6 v2.1.0; `perf-gate.yml` pins v0.54.0. The legacy
summary-export shape is stable across that range, but rather than bet on it, the
parser accepts BOTH encodings it could plausibly meet — a bare bool, and a
`{"ok": bool}` object (where `ok` is the inverse: ok=false means breached) — and
degrades to "could not classify" rather than guessing. A misread here would report a
green run as a breach, or worse, a breach as green, and the issue body is the only
artifact a human reads.

WHAT "FAILED" CAN MEAN
----------------------
A red perf-gate job is not necessarily a threshold breach. The k6 step also fails when
postgres never becomes ready, when the service fails to boot in dev mode, or when the
k6 binary install fails — in which case there is no summary JSON at all and the run
measured NOTHING. Reporting that as a performance regression would send the reader
hunting for a slowdown that never happened, so those cases are classified separately
and named as such. A service whose summary exists with zero breached thresholds is
reported too: it means k6 passed and the job died elsewhere.

NON-GOALS
---------
This never closes an issue. Acceptance criterion for #3768 is explicit that a later
green run must not auto-close the record without a note — the issue IS the triage
trail, and a bot that silently closes it deletes the only evidence anyone was ever
told. It also never opens a second issue: the title is deliberately constant and
service-independent, because a per-service title would file a fresh issue each time
the matrix grows, and an escalation that opens an issue every run is worse than
silence.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

TITLE = "Performance gate: the weekly k6 run is failing (ADR-0243 advisory phase)"


def _breached(value: object) -> bool | None:
    """True = breached, False = passed, None = shape not understood.

    See the module header: a bare bool is k6's summary-export encoding and its
    polarity is "true == crossed". The dict form is accepted defensively and its
    polarity is inverted (`ok: false` means the threshold failed).
    """
    if isinstance(value, bool):
        return value
    if isinstance(value, dict) and isinstance(value.get("ok"), bool):
        return not value["ok"]
    return None


def classify(report_dir: pathlib.Path, service: str) -> dict:
    """Return this service's outcome from whatever files its artifact contains."""
    summary = report_dir / f"{service}-summary.json"
    boot_log = report_dir / f"{service}-boot.log"

    if not summary.is_file():
        reason = (
            "k6 never produced a summary — the run did not reach the load test "
            "(postgres, service boot, or the k6 install failed). **Nothing was measured.**"
        )
        if boot_log.is_file():
            reason += f" A boot log is present (`{boot_log.name}`); read it first."
        return {"service": service, "kind": "not-measured", "detail": reason, "breaches": []}

    try:
        metrics = json.loads(summary.read_text()).get("metrics", {})
    except (json.JSONDecodeError, OSError) as exc:
        return {
            "service": service,
            "kind": "unparseable",
            "detail": f"`{summary.name}` could not be parsed ({exc}); read the run log directly.",
            "breaches": [],
        }

    breaches: list[str] = []
    unknown: list[str] = []
    for metric, body in sorted(metrics.items()):
        if not isinstance(body, dict):
            continue
        for expr, value in sorted((body.get("thresholds") or {}).items()):
            verdict = _breached(value)
            if verdict is None:
                unknown.append(f"{metric} `{expr}` (unrecognised encoding: {value!r})")
            elif verdict:
                breaches.append(f"{metric} `{expr}`")

    if breaches:
        return {"service": service, "kind": "breach", "detail": "", "breaches": breaches}
    if unknown:
        return {
            "service": service,
            "kind": "unparseable",
            "detail": "threshold verdicts in an unrecognised shape: " + "; ".join(unknown),
            "breaches": [],
        }
    return {
        "service": service,
        "kind": "no-breach",
        "detail": (
            "k6 ran and **every threshold passed** — this service's job failed for some "
            "other reason (teardown, artifact upload, timeout). Do not chase a regression here."
        ),
        "breaches": [],
    }


def collect(root: pathlib.Path) -> list[dict]:
    """Read `perf-reports-<service>/` directories as produced by the perf matrix job."""
    results = []
    if not root.is_dir():
        return results
    for entry in sorted(root.iterdir()):
        if not entry.is_dir() or not entry.name.startswith("perf-reports-"):
            continue
        results.append(classify(entry, entry.name[len("perf-reports-") :]))
    return results


def render(results: list[dict], run_url: str, event: str) -> str:
    lines = [
        f"The scheduled **performance gate** run FAILED (trigger: `{event}`).",
        "",
        f"Run: {run_url}",
        "",
    ]

    if not results:
        lines += [
            "**No per-service reports were recovered from the run.** The failure is likely "
            "before the matrix (scope derivation) or the artifacts were never uploaded — so "
            "no statement can be made about performance. Read the run log.",
            "",
        ]
    else:
        lines.append("## What each service reported")
        lines.append("")
        for r in results:
            if r["kind"] == "breach":
                lines.append(f"- **`{r['service']}` — threshold breach:**")
                lines += [f"  - {b}" for b in r["breaches"]]
            else:
                lines.append(f"- **`{r['service']}` — {r['kind']}:** {r['detail']}")
        lines.append("")

    breached = [r for r in results if r["kind"] == "breach"]
    if breached:
        lines += [
            "A breach here is **advisory** (ADR-0243 decision 2): it does not block any merge. "
            "It is a measurement that the service crossed a latency or error-rate bound the "
            "repo has committed to, and it stays crossed until someone looks.",
            "",
        ]

    lines += [
        "## Why this issue exists rather than just a red run",
        "",
        "`perf-gate.yml` runs weekly on a schedule. A red scheduled workflow on `main` is "
        "addressed to nobody — `dependency-submission.yml` was red for three days for exactly "
        "this reason while its consumers read stale data (#1449). This issue is the triage "
        "record.",
        "",
        "**It is refreshed, never auto-closed.** A later green run adds nothing here and does "
        "not close it: close it by hand once the breach is understood, so the reason survives "
        "in the thread. Each further failure comments on this issue rather than opening a new "
        "one.",
        "",
        "The `perf-reports-<service>` artifacts on the run above carry the full k6 output and "
        "the service boot log (30-day retention).",
        "",
        "Refs #3348, #3768 · ADR-0243",
    ]
    return "\n".join(lines)


# ── self-test ────────────────────────────────────────────────────────────────
# A gate or escalation that has only ever seen the happy input is unfalsified. Each
# case below is one the renderer MUST distinguish; the breach fixture is a verbatim
# `metrics` fragment from a real k6 run whose console printed `✗` for the true entry
# and `✓` for the false one.
def _self_test() -> int:
    import tempfile

    failures = []

    def check(label: str, cond: bool) -> None:
        print(f"  {'ok  ' if cond else 'FAIL'} {label}")
        if not cond:
            failures.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        # 1. real breach, mixed polarity within one metric
        d = root / "perf-reports-openbank-product-catalog"
        d.mkdir()
        (d / "openbank-product-catalog-summary.json").write_text(
            json.dumps(
                {
                    "metrics": {
                        "http_req_duration": {"thresholds": {"p(95)<0.0001": True, "p(95)<60000": False}},
                        "http_req_failed": {"thresholds": {"rate<0.01": False}},
                        "checks": {"thresholds": {"rate==1.0": True}},
                    }
                }
            )
        )
        # 2. never measured — no summary, boot log present
        d2 = root / "perf-reports-openbank-ledger-service"
        d2.mkdir()
        (d2 / "openbank-ledger-service-boot.log").write_text("boom")
        # 3. k6 green, job red elsewhere
        d3 = root / "perf-reports-openbank-fx-service"
        d3.mkdir()
        (d3 / "openbank-fx-service-summary.json").write_text(
            json.dumps({"metrics": {"http_req_failed": {"thresholds": {"rate<0.01": False}}}})
        )

        results = collect(root)
        by = {r["service"]: r for r in results}
        check("three services classified", len(results) == 3)
        pc = by.get("openbank-product-catalog", {})
        check("breach detected", pc.get("kind") == "breach")
        check(
            "only the crossed thresholds are named",
            sorted(pc.get("breaches", []))
            == ["checks `rate==1.0`", "http_req_duration `p(95)<0.0001`"],
        )
        check(
            "a passing threshold on a breached metric is NOT reported",
            all("60000" not in b for b in pc.get("breaches", [])),
        )
        check("missing summary => not-measured", by.get("openbank-ledger-service", {}).get("kind") == "not-measured")
        check("green k6 => no-breach", by.get("openbank-fx-service", {}).get("kind") == "no-breach")

        body = render(results, "https://example.invalid/run/1", "schedule")
        check("body names the breaching service", "openbank-product-catalog" in body)
        check("body names the breached threshold", "p(95)<0.0001" in body)
        check("body says it is never auto-closed", "auto-closed" in body)
        check("body links the run", "https://example.invalid/run/1" in body)
        check(
            "not-measured is not described as a regression",
            "Nothing was measured" in body,
        )

        empty = render([], "https://example.invalid/run/2", "schedule")
        check("empty input still renders a usable body", "No per-service reports" in empty)

        # unparseable shapes must not be silently read as "passed"
        d4 = root / "perf-reports-openbank-odd"
        d4.mkdir()
        (d4 / "openbank-odd-summary.json").write_text(
            json.dumps({"metrics": {"m": {"thresholds": {"x<1": "nope"}}}})
        )
        check("unknown verdict encoding => unparseable, not no-breach", classify(d4, "openbank-odd")["kind"] == "unparseable")
        (d4 / "openbank-odd-summary.json").write_text("{not json")
        check("corrupt json => unparseable", classify(d4, "openbank-odd")["kind"] == "unparseable")
        check("dict {'ok': False} reads as breached", _breached({"ok": False}) is True)
        check("dict {'ok': True} reads as passed", _breached({"ok": True}) is False)

    print(f"\nself-test: {len(failures)} failure(s)")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--reports-dir", default="perf-artifacts")
    ap.add_argument("--run-url", default="")
    ap.add_argument("--event", default="schedule")
    ap.add_argument("--out", help="write the body here instead of stdout")
    ap.add_argument("--print-title", action="store_true", help="print the issue title and exit")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return _self_test()
    if args.print_title:
        print(TITLE)
        return 0

    body = render(collect(pathlib.Path(args.reports_dir)), args.run_url, args.event)
    if args.out:
        pathlib.Path(args.out).write_text(body + "\n")
    else:
        print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
