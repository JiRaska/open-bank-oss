#!/usr/bin/env python3
"""Resolved-alert ledger, MTTR guard and recurrence guard for ADR-0241.

Companion to `standing-critical-digest.py` (#5469), which reports the age of
alerts that are *currently standing*.  This script keeps the complementary
record: when a critical alert stopped being observed, so that recurrence can be
counted and — once a source with adequate timestamp resolution exists — MTTR can
be computed.

WHY THIS DOES NOT PUBLISH AN MTTR TODAY
---------------------------------------
ADR-0241 D1 sets the target at **P90 <= 4 h**.  A P90 is only meaningful if the
resolution timestamps are finer-grained than the target.  The sources available
to a GitHub Actions workflow today are not:

  * Alertmanager `/api/v2/alerts` — the source #5469 already uses — returns
    ACTIVE alerts.  It is not a history API; a resolved alert is simply absent,
    so resolution time is only ever known to the polling interval.
  * Prometheus — 12 h retention, no long-term store (ADR-0027).  The `ALERTS`
    series cannot cover the 7-day review window of ADR-0241 D3 at all, and a
    range query silently truncates rather than erroring.
  * Loki — 168 h retention, and carries notification logs, not an authoritative
    open/close record.
  * GoAlert Postgres — DOES hold durable open/close history with real
    timestamps, and is the intended source.  It is cluster-internal with no
    Ingress (ADR-0056/ADR-0088) and no workflow-reachable credential exists.

So the daily poll yields `granularity_seconds = 86400` against a 14400 s target.
`mttr_p90` therefore REFUSES to emit a number and returns a reason instead.  The
refusal is a rule, not a placeholder: it passes the moment records arrive from a
fine-grained source (see `self_test`, which proves both directions).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from collections import defaultdict

# ADR-0241 D1.
MTTR_TARGET_SECONDS = 4 * 3600
# A P90 is not credible unless resolution timestamps are at least twice as fine
# as the target; otherwise the bucket width dominates the statistic.
MAX_CREDIBLE_GRANULARITY_SECONDS = MTTR_TARGET_SECONDS // 2
# ADR-0241 D3 rate-of-recurrence guard.
RECURRENCE_THRESHOLD = 3
RECURRENCE_WINDOW_DAYS = 14

DEFAULT_LEDGER = "docs/runbooks/alert-rca/ledger.jsonl"

# --------------------------------------------------------------------------- #
# COVERAGE — "no alerts" and "no observations" are DIFFERENT outcomes.
#
# The daily standing-critical digest archives an envelope every day it succeeds,
# INCLUDING on a day with zero critical alerts (`{"observedAt": ..., "alerts": []}`).
# So a genuinely quiet week still yields ~7 envelopes. Zero envelopes therefore
# does not mean "nothing fired" — it means the producer never ran, and the weekly
# review has measured nothing at all.
#
# Those two states must never share an outcome or an exit code. This repo already
# paid for that lesson once: `PushResult.skipped()` returned `success = True` when
# APNs was disabled, the fan-out counted it with `count { success }`, and every
# push in a credential-less environment was recorded as delivered. A no-op that
# reports success is indistinguishable from a working control, and nothing
# downstream can disagree with it.
EXIT_OK = 0
EXIT_USAGE = 1
EXIT_NO_OBSERVATIONS = 2

COVERAGE_OBSERVED = "OBSERVED"
COVERAGE_NO_OBSERVATIONS = "NO_OBSERVATIONS"


def coverage(observation_count: int) -> dict:
    """Classify what this run actually measured.

    Deliberately NOT a boolean: a bool invites `if ok:` at the call site, which is
    exactly how a no-op gets folded back into the success path.
    """
    if observation_count > 0:
        return {
            "outcome": COVERAGE_OBSERVED,
            "observationCount": observation_count,
            "detail": (
                f"{observation_count} daily observation envelope(s) folded; the "
                "findings below describe the window that was actually measured."
            ),
        }
    return {
        "outcome": COVERAGE_NO_OBSERVATIONS,
        "observationCount": 0,
        "detail": (
            "No observation envelope covered this window. The daily "
            "standing-critical digest archives an envelope even on a day with zero "
            "critical alerts, so this is NOT a quiet week — it means the producer "
            "did not run (or ran and failed). Nothing below has been measured."
        ),
    }


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def fmt_time(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p
    low, high = int(rank), min(int(rank) + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def load_ledger(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    records = []
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def write_ledger(path: str, records: list[dict]) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    records = sorted(records, key=lambda r: (r["startsAt"], r["key"]))
    with open(path, "w", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, sort_keys=True) + "\n")


def observe(
    records: list[dict],
    active: list[dict],
    observed_at: dt.datetime,
    granularity_seconds: int,
    source: str,
) -> list[dict]:
    """Fold one observation of currently-active criticals into the ledger.

    An alert present in `active` is opened (or refreshed).  An OPEN ledger
    record whose key is absent from this observation is closed at `observed_at`.
    Closing is only correct for a source that enumerates every active alert, so
    an observation is authoritative for the keys it does not contain too.
    """
    by_key = {r["key"]: dict(r) for r in records}
    seen = set()
    for alert in active:
        labels = alert.get("labels") or {}
        if labels.get("severity") != "critical":
            continue
        starts_at = alert.get("startsAt")
        if not starts_at:
            continue
        alertname = labels.get("alertname", "<unnamed>")
        service = labels.get("service", labels.get("job", "-"))
        key = f"{alertname}|{service}|{fmt_time(parse_time(starts_at))}"
        seen.add(key)
        existing = by_key.get(key)
        if existing is None:
            by_key[key] = {
                "key": key,
                "alertname": alertname,
                "service": service,
                "severity": "critical",
                "startsAt": fmt_time(parse_time(starts_at)),
                "lastSeenAt": fmt_time(observed_at),
                "resolvedAt": None,
                "source": source,
                "granularitySeconds": granularity_seconds,
            }
        elif existing.get("resolvedAt") is None:
            existing["lastSeenAt"] = fmt_time(observed_at)
    for key, record in by_key.items():
        if key in seen or record.get("resolvedAt") is not None:
            continue
        # Never close a record with an observation that predates its last sighting.
        if parse_time(record["lastSeenAt"]) <= observed_at:
            record["resolvedAt"] = fmt_time(observed_at)
    return list(by_key.values())


def mttr_p90(records: list[dict]) -> dict:
    """Return the P90 time-to-resolve, or a refusal with a machine-readable reason.

    Never returns a number the underlying timestamps cannot support.
    """
    resolved = [r for r in records if r.get("resolvedAt")]
    if not resolved:
        return {
            "p90Seconds": None,
            "sampleCount": 0,
            "reason": "no-resolved-alert-history",
            "detail": "The ledger holds no resolved critical alert yet.",
        }
    worst = max(int(r.get("granularitySeconds", MTTR_TARGET_SECONDS)) for r in resolved)
    if worst > MAX_CREDIBLE_GRANULARITY_SECONDS:
        return {
            "p90Seconds": None,
            "sampleCount": len(resolved),
            "reason": "granularity-coarser-than-target",
            "detail": (
                f"Resolution timestamps are known only to {worst}s; a "
                f"{MTTR_TARGET_SECONDS}s P90 target needs "
                f"<={MAX_CREDIBLE_GRANULARITY_SECONDS}s. Needs a durable "
                "alert open/close source (GoAlert history), not a faster poll."
            ),
        }
    durations = [
        (parse_time(r["resolvedAt"]) - parse_time(r["startsAt"])).total_seconds()
        for r in resolved
    ]
    return {
        "p90Seconds": percentile(durations, 0.90),
        "sampleCount": len(resolved),
        "reason": None,
        "detail": None,
        "granularitySeconds": worst,
    }


def recurrences(records: list[dict], now: dt.datetime) -> list[dict]:
    """Alert names that fired RECURRENCE_THRESHOLD+ times inside the window.

    Counted by distinct FIRE (each ledger record is one firing), on `startsAt`,
    over the trailing RECURRENCE_WINDOW_DAYS. Resolved and still-open both count
    — ADR-0241 D3 says "resolved or still open".
    """
    cutoff = now - dt.timedelta(days=RECURRENCE_WINDOW_DAYS)
    grouped: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        if parse_time(record["startsAt"]) >= cutoff:
            grouped[record["alertname"]].append(record)
    flagged = []
    for alertname, group in sorted(grouped.items()):
        if len(group) >= RECURRENCE_THRESHOLD:
            flagged.append(
                {
                    "alertname": alertname,
                    "count": len(group),
                    "services": sorted({r["service"] for r in group}),
                    "windowDays": RECURRENCE_WINDOW_DAYS,
                    "firstSeen": min(r["startsAt"] for r in group),
                    "lastSeen": max(r["startsAt"] for r in group),
                }
            )
    return flagged


def render_rca(records: list[dict], now: dt.datetime, cover: dict) -> str:
    """One line per critical alert in the trailing 7 days (ADR-0241 D3).

    `cover` is REQUIRED, not defaulted. A default would let a caller that never
    established coverage render a document that reads as a measured clean week.
    """
    cutoff = now - dt.timedelta(days=7)
    window = sorted(
        (r for r in records if parse_time(r["startsAt"]) >= cutoff),
        key=lambda r: r["startsAt"],
    )
    mttr = mttr_p90(records)
    flagged = recurrences(records, now)
    lines = [
        f"# Critical alert RCA review — week ending {now.strftime('%Y-%m-%d')}",
        "",
        "Generated by `.github/scripts/alert-rca-ledger.py` (ADR-0241 D3).",
        "",
        "## Coverage",
        "",
    ]
    if cover["outcome"] == COVERAGE_NO_OBSERVATIONS:
        lines += [
            "> [!CAUTION]",
            "> **NO OBSERVATIONS — this review measured nothing.**",
            f"> {cover['detail']}",
            ">",
            "> Every finding below is the *absence of data*, not a clean week. Treat this",
            "> review as FAILED until the daily digest is producing envelopes again.",
            "",
        ]
    else:
        lines += [f"{cover['detail']}", ""]
    lines += [
        "## MTTR (ADR-0241 D1 target: P90 <= 4 h)",
        "",
    ]
    if mttr["p90Seconds"] is None:
        lines += [
            f"**Not reported** — `{mttr['reason']}` "
            f"({mttr['sampleCount']} resolved sample(s) in the ledger).",
            "",
            f"> {mttr['detail']}",
        ]
    else:
        lines.append(
            f"**P90 = {mttr['p90Seconds'] / 3600:.2f} h** over "
            f"{mttr['sampleCount']} resolved critical alert(s), timestamps "
            f"accurate to {mttr['granularitySeconds']}s."
        )
    lines += ["", "## Alerts in the last 7 days", ""]
    if window:
        lines += ["| Alert | Service | First fired | Resolved | RCA |", "|---|---|---|---|---|"]
        for record in window:
            resolved = record.get("resolvedAt") or "_still open_"
            lines.append(
                f"| `{record['alertname']}` | `{record['service']}` | "
                f"{record['startsAt']} | {resolved} | _(fill in)_ |"
            )
    elif cover["outcome"] == COVERAGE_NO_OBSERVATIONS:
        lines.append("**Unknown** — no observation covered this window.")
    else:
        lines.append("No critical alerts recorded in the window.")
    lines += ["", "## Rate-of-recurrence guard (ADR-0241 D3)", ""]
    if flagged:
        lines.append(
            f"The following fired {RECURRENCE_THRESHOLD}+ times in "
            f"{RECURRENCE_WINDOW_DAYS} days and must have a follow-up issue "
            "before the next review:"
        )
        lines.append("")
        for item in flagged:
            lines.append(
                f"- **`{item['alertname']}`** — {item['count']} fires on "
                f"{', '.join('`' + s + '`' for s in item['services'])} "
                f"({item['firstSeen']} .. {item['lastSeen']})"
            )
    elif cover["outcome"] == COVERAGE_NO_OBSERVATIONS:
        lines.append(
            "**Unknown** — the recurrence guard saw no observations, so a silent "
            "recurrence would look identical to this."
        )
    else:
        lines.append("No alert reached the recurrence threshold in the window.")
    return "\n".join(lines) + "\n"


def replay(records: list[dict], observations: list[tuple[dt.datetime, list[dict]]],
           granularity_seconds: int, source: str) -> list[dict]:
    """Fold observations in observedAt order.

    Order matters: `gh run download` yields artifacts in no meaningful order, and
    folding an older observation after a newer one leaves a resolved alert marked
    open forever (the close is attempted before the record exists).
    """
    for observed_at, alerts in sorted(observations, key=lambda o: o[0]):
        records = observe(records, alerts, observed_at, granularity_seconds, source)
    return records


def read_observation(payload) -> tuple[dt.datetime, list[dict]]:
    """Unpack an observation envelope `{observedAt, alerts}`.

    A bare array is REFUSED rather than defaulted to "now": defaulting would let
    a replay of a week-old artifact close records at replay time and silently
    inflate every duration in the ledger.
    """
    if isinstance(payload, dict) and "observedAt" in payload and "alerts" in payload:
        alerts = payload["alerts"]
        if not isinstance(alerts, list):
            raise ValueError("`alerts` must be an array")
        return parse_time(payload["observedAt"]), alerts
    raise ValueError("expected an observation envelope {observedAt, alerts}")


# --------------------------------------------------------------------------- #
# self-test — every guard is exercised with the case it MUST reject as well as
# the case it must accept.  A guard proven only by what it prints is not proven.
# --------------------------------------------------------------------------- #
def _alert(name: str, service: str, starts: str) -> dict:
    return {"labels": {"severity": "critical", "alertname": name, "service": service}, "startsAt": starts}


def self_test() -> None:
    day = lambda d, h=0: dt.datetime(2026, 8, d, h, tzinfo=dt.timezone.utc)  # noqa: E731

    # --- observe: opens, refreshes, and closes on absence -------------------
    led = observe([], [_alert("LedgerLag", "ledger", "2026-08-01T00:00:00Z")], day(1, 8), 86400, "poll")
    assert len(led) == 1 and led[0]["resolvedAt"] is None, led
    led = observe(led, [_alert("LedgerLag", "ledger", "2026-08-01T00:00:00Z")], day(2, 8), 86400, "poll")
    assert led[0]["resolvedAt"] is None and led[0]["lastSeenAt"] == "2026-08-02T08:00:00Z"
    led = observe(led, [], day(3, 8), 86400, "poll")
    assert led[0]["resolvedAt"] == "2026-08-03T08:00:00Z", led
    # a later observation must not reopen or re-close an already closed record
    led = observe(led, [], day(4, 8), 86400, "poll")
    assert led[0]["resolvedAt"] == "2026-08-03T08:00:00Z"
    # a non-critical alert is never admitted
    assert observe([], [{"labels": {"severity": "warning", "alertname": "W"}, "startsAt": "2026-08-01T00:00:00Z"}], day(1), 86400, "poll") == []

    # --- MTTR guard: MUST refuse, and MUST also be able to pass -------------
    empty = mttr_p90([])
    assert empty["p90Seconds"] is None and empty["reason"] == "no-resolved-alert-history", empty
    coarse = mttr_p90(led)
    assert coarse["p90Seconds"] is None, "a daily-granularity ledger must never yield a P90"
    assert coarse["reason"] == "granularity-coarser-than-target", coarse
    # NEGATIVE CASE for the refusal itself: identical records from a fine-grained
    # source must produce a real number, or the guard is unconditional and the
    # 'refusal' proves nothing about granularity.
    fine = [
        {"key": f"k{i}", "alertname": "A", "service": "s", "startsAt": "2026-08-01T00:00:00Z",
         "resolvedAt": f"2026-08-01T0{i}:00:00Z", "granularitySeconds": 1}
        for i in range(1, 6)
    ]
    passed = mttr_p90(fine)
    assert passed["p90Seconds"] is not None, "the guard must pass a fine-grained source"
    assert abs(passed["p90Seconds"] - 4.6 * 3600) < 1, passed
    # boundary: exactly at the credible-granularity limit passes, one second over refuses
    at_limit = [dict(fine[0], granularitySeconds=MAX_CREDIBLE_GRANULARITY_SECONDS)]
    over_limit = [dict(fine[0], granularitySeconds=MAX_CREDIBLE_GRANULARITY_SECONDS + 1)]
    assert mttr_p90(at_limit)["p90Seconds"] is not None
    assert mttr_p90(over_limit)["p90Seconds"] is None
    # a single coarse sample poisons a mixed batch (worst granularity wins)
    assert mttr_p90(fine + over_limit)["p90Seconds"] is None

    # --- recurrence guard: FALSIFICATION FIRST ------------------------------
    def rec(name, dates, service="ledger"):
        return [{"key": f"{name}{d}", "alertname": name, "service": service,
                 "startsAt": f"2026-08-{d:02d}T00:00:00Z", "resolvedAt": None} for d in dates]

    now = day(20, 12)
    # must NOT fire: two fires inside the window is below threshold
    assert recurrences(rec("Twice", [18, 19]), now) == [], "2 fires must not flag"
    # must NOT fire: three fires, but spread wider than the 14-day window
    assert recurrences(rec("Spread", [1, 2, 19]), now) == [], "fires outside the window must not count"
    # must NOT fire: three fires of DIFFERENT alert names
    mixed = rec("A", [17]) + rec("B", [18]) + rec("C", [19])
    assert recurrences(mixed, now) == [], "distinct alertnames must not aggregate"
    # MUST fire: three fires of one name inside the window
    hit = recurrences(rec("Recurring", [10, 15, 19]), now)
    assert len(hit) == 1 and hit[0]["count"] == 3 and hit[0]["alertname"] == "Recurring", hit
    # boundary: exactly on the window edge counts, one day past it does not
    # cutoff is 2026-08-06T12:00Z, so a fire dated 08-07T00:00Z is inside it and
    # one dated 08-06T00:00Z is outside — the window is 14*24h, not 14 calendar days.
    assert len(recurrences(rec("Edge", [7, 15, 19]), now)) == 1
    assert recurrences(rec("Edge", [6, 15, 19]), now) == [], "08-06T00:00Z precedes the 08-06T12:00Z cutoff"
    # a recurring alert that RESOLVED each time still counts (ADR-0241 D3)
    resolved_each = [dict(r, resolvedAt="2026-08-20T00:00:00Z") for r in rec("Flappy", [10, 15, 19])]
    assert len(recurrences(resolved_each, now)) == 1

    # --- replay must sort: artifacts arrive in arbitrary order --------------
    a1 = [_alert("Sorted", "ledger", "2026-08-01T00:00:00Z")]
    shuffled = [(day(3, 8), []), (day(1, 8), a1), (day(2, 8), a1)]
    ordered = replay([], shuffled, 86400, "poll")
    assert len(ordered) == 1, ordered
    assert ordered[0]["resolvedAt"] == "2026-08-03T08:00:00Z", (
        "out-of-order replay leaves a resolved alert open forever", ordered)

    # --- observation envelope: a bare array must be REFUSED, not defaulted --
    try:
        read_observation([_alert("A", "s", "2026-08-01T00:00:00Z")])
    except ValueError:
        pass
    else:
        raise AssertionError("a bare array must be refused, never dated 'now'")
    for bad in [{}, {"observedAt": "2026-08-01T00:00:00Z"}, {"observedAt": "2026-08-01T00:00:00Z", "alerts": {}}]:
        try:
            read_observation(bad)
        except ValueError:
            pass
        else:
            raise AssertionError(f"malformed envelope accepted: {bad}")
    when, alerts = read_observation({"observedAt": "2026-08-01T06:00:00Z", "alerts": [_alert("A", "s", "2026-08-01T00:00:00Z")]})
    assert when == day(1, 6) and len(alerts) == 1

    # --- coverage: an EMPTY window is not a clean window --------------------
    # FALSIFICATION FIRST. The defect this guards is that a run which folded zero
    # observations rendered exactly the same document, and exited 0, as a week in
    # which the producer ran daily and genuinely saw nothing. Prove the two are
    # distinguishable in BOTH directions, or the guard proves nothing.
    uncovered = coverage(0)
    covered = coverage(7)
    assert uncovered["outcome"] == COVERAGE_NO_OBSERVATIONS, uncovered
    assert covered["outcome"] == COVERAGE_OBSERVED, covered
    assert uncovered["outcome"] != covered["outcome"], (
        "the empty case must not share an outcome with the measured case"
    )
    # one observation is enough to be covered — the boundary is 0/1, not a quorum
    assert coverage(1)["outcome"] == COVERAGE_OBSERVED
    # and the outcomes must map to DIFFERENT exit codes, not merely different prose
    assert EXIT_NO_OBSERVATIONS != EXIT_OK, "a no-op must never share exit 0 with success"

    empty_doc = render_rca([], now, uncovered)
    clean_doc = render_rca([], now, covered)
    assert "NO OBSERVATIONS" in empty_doc, empty_doc
    assert "measured nothing" in empty_doc
    # the exact sentence that used to be emitted for BOTH states must now appear
    # only for the genuinely-measured one
    assert "No critical alerts recorded in the window." in clean_doc
    assert "No critical alerts recorded in the window." not in empty_doc, (
        "an uncovered window must not claim there were no alerts"
    )
    assert "No alert reached the recurrence threshold" in clean_doc
    assert "No alert reached the recurrence threshold" not in empty_doc
    assert "NO OBSERVATIONS" not in clean_doc, (
        "a genuinely quiet, fully-observed week must not be reported as uncovered"
    )
    assert empty_doc != clean_doc, "the two states must not render identically"

    # The prose assertions above cannot see main()'s EXIT CODE, and the exit code is
    # what the scheduled workflow actually reads. Mutating `--allow-no-observations`
    # from opt-in to the default survived every in-process assertion (it changes no
    # string), so drive the REAL CLI in a subprocess: a guard is proven by what it
    # prevents, and here that is a green run.
    import subprocess, tempfile
    with tempfile.TemporaryDirectory() as tmp:
        def cli(*extra: str) -> int:
            return subprocess.run(
                [sys.executable, os.path.abspath(__file__),
                 "--ledger", os.path.join(tmp, "l.jsonl"),
                 "--rca-output", os.path.join(tmp, "r.md"), *extra],
                capture_output=True, text=True,
            ).returncode

        # MUST REJECT: no observations, no override -> non-zero, and specifically
        # the dedicated code, not a generic usage error.
        rc_uncovered = cli()
        assert rc_uncovered == EXIT_NO_OBSERVATIONS, (
            f"an uncovered window must exit {EXIT_NO_OBSERVATIONS}, got {rc_uncovered} "
            "— the empty case has fallen back into the success path"
        )
        assert rc_uncovered != EXIT_OK
        # MUST ACCEPT: the explicit opt-out is honoured (so the refusal is a rule with
        # a documented escape, not an unconditional failure).
        assert cli("--allow-no-observations") == EXIT_OK
        # MUST ACCEPT: a real observation -> exit 0 with no override needed.
        envelope = os.path.join(tmp, "obs.json")
        with open(envelope, "w", encoding="utf-8") as stream:
            json.dump({"observedAt": "2026-08-20T08:46:00Z", "alerts": []}, stream)
        assert cli("--observe", envelope) == EXIT_OK, (
            "a genuinely observed quiet week must pass without an override"
        )

    # --- rendering never launders a refusal into a number -------------------
    doc = render_rca(led, now, covered)
    assert "Not reported" in doc and "granularity-coarser-than-target" in doc, doc
    assert "P90 = " not in doc, "a refused MTTR must not render a P90 figure"
    assert "P90 = " in render_rca(fine, now, covered)
    flagged_doc = render_rca(rec("Recurring", [10, 15, 19]), now, covered)
    assert "**`Recurring`** — 3 fires" in flagged_doc, flagged_doc
    assert "No alert reached the recurrence threshold" in render_rca(rec("Twice", [18, 19]), now, covered)

    print(
        "self-test ok: alert-rca-ledger "
        "(observe open/refresh/close, MTTR refuses coarse AND passes fine, "
        "recurrence flags 3-in-14 and rejects 2-in-14 / 3-outside-14 / mixed names, "
        "render never prints a refused P90, "
        "an UNOBSERVED window is distinguishable from a clean one in prose AND exit code)"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--ledger", default=DEFAULT_LEDGER)
    parser.add_argument("--observe", metavar="OBSERVATION_JSON", nargs="*", default=[],
                        help="observation envelope(s) to fold into the ledger, replayed "
                             "in observedAt order")
    parser.add_argument("--source", default="alertmanager-poll")
    parser.add_argument("--granularity-seconds", type=int, default=86400,
                        help="how precisely this source dates a resolution (daily poll = 86400)")
    parser.add_argument("--rca-output", help="write the weekly RCA markdown here")
    parser.add_argument("--recurrence-output", help="write flagged recurrences as JSON here")
    parser.add_argument(
        "--allow-no-observations", action="store_true",
        help="exit 0 even when no observation covered the window. For local/ad-hoc "
             "rendering ONLY — never in the scheduled review, where zero observations "
             "means the producer is down and the review measured nothing.",
    )
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    now = dt.datetime.now(dt.timezone.utc)
    records = load_ledger(args.ledger)
    if args.observe:
        observations = []
        for path in args.observe:
            with open(path, encoding="utf-8") as stream:
                payload = json.load(stream)
            try:
                observed_at, alerts = read_observation(payload)
            except ValueError as exc:
                print(f"ERROR: {path}: {exc}", file=sys.stderr)
                return EXIT_USAGE
            observations.append((observed_at, alerts, path))
        records = replay(records, [(w, a) for w, a, _ in observations],
                         args.granularity_seconds, args.source)
        write_ledger(args.ledger, records)
        print(f"ledger: {len(records)} record(s) from {len(observations)} observation(s) -> {args.ledger}")
    cover = coverage(len(args.observe))
    if args.rca_output:
        with open(args.rca_output, "w", encoding="utf-8") as stream:
            stream.write(render_rca(records, now, cover))
        print(f"rca: {args.rca_output}")
    flagged = recurrences(records, now)
    if args.recurrence_output:
        with open(args.recurrence_output, "w", encoding="utf-8") as stream:
            json.dump(flagged, stream, indent=2, sort_keys=True)
    mttr = mttr_p90(records)
    print(json.dumps(
        {"coverage": cover, "mttr": mttr, "recurrences": flagged},
        indent=2, sort_keys=True,
    ))
    if cover["outcome"] == COVERAGE_NO_OBSERVATIONS:
        print(
            "ERROR: no observation envelope covered this window — the weekly review "
            "measured nothing. This is not a clean week; the daily standing-critical "
            "digest is not producing envelopes. See #5869.",
            file=sys.stderr,
        )
        if not args.allow_no_observations:
            return EXIT_NO_OBSERVATIONS
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
