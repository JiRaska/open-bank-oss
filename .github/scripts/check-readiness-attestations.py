#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Format + freshness discipline for openbank-libs/governance/attestations.yaml (#2365).

WHY THIS EXISTS
---------------
That file is the ONLY place production-readiness maturity is asserted by hand. Every other
signal the collector scores is derived from the repo; these are the facts that cannot be
derived and someone has to witness -- a restore actually performed, a pentest actually run.
An entry there raises a money-path dimension from 2 to 3, and the runbooks, the OpenSSF
gap doc and the threat models all read the resulting matrix as fact.

Nothing checked the file before this gate. Three failure modes were live when it was written:

  1. A DEAD SERVICE KEY. `collect-prod-readiness.mjs` looks attestations up by the SHORT
     service name (`consent`, from `openbank-consent-service`). The committed file said
     `consent-service:`, so consent's pentest attestation matched nothing and scored
     nothing -- measured: renaming that one key flips consent C7 from 2 to 3. A wrong key
     is invisible from every angle. The file looks attested, the matrix looks underscored,
     and neither one points at the other.

  2. AN UNKNOWN ATTESTATION KEY. The collector reads exactly eight keys. Any other key is
     parsed, stored and never consulted -- an attestation that does nothing, silently.

  3. DECAY. Every entry carries `ttl_days` precisely because a green is not forever. After
     expiry the collector stops counting the bonus, so the SCORE self-corrects -- but the
     file keeps reading as a live claim to every human and every document that cites it.

WHAT IT DOES NOT DO
-------------------
It cannot prove the event happened. Nothing in a repo can. It makes a fabrication require
a deliberate lie -- a real date, a resolvable reference, a service that exists -- rather
than a careless line.

FAIL vs WARN (the deliberate split)
-----------------------------------
AUTHORING DEFECTS FAIL: a missing field, a bad date, a future date, a dead service key, an
unknown attestation key, an unresolvable `ref`. Each is introduced by the PR that writes it
and fixed by that same PR; failing is proportional and actionable.

DECAY WARNS, until it is stale. Failing the whole fleet's CI the morning a pentest TTL rolls
over punishes every unrelated PR for an operational event none of them can fix, and this
repo has already paid for that class of alert (a `critical` that is the resting state stops
being read). The collector's TTL decay is the real enforcement -- an expired attestation
cannot buy a green cell, it just stops counting. So: expiring soon -> warning with a
countdown; expired -> warning; expired for more than --stale-fail-days (default 30) -> ERROR,
because at that point it is not a lapse, it is stale bookkeeping asserting a control that
ended a month ago. The fix is always one line: delete the entry, or re-attest a real event.

The attestation count is printed on every run. A green that says "0 attestations" is a gate
that never opened the file, and that has to be visible.
"""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[2]
FILE_REL = "openbank-libs/governance/attestations.yaml"

# The keys collect-prod-readiness.mjs actually consults (attestFresh call sites). Any other
# key parses fine and influences nothing.
KNOWN_KEYS = {
    "code_complete",
    "coverage_floor",
    "contract_verified",
    "restore_drill",
    "dr_drill",
    "pentest",
    "slo_defined",
    "oncall",
}

REQUIRED_FIELDS = ("date", "ttl_days", "by", "ref")

# Keys that assert an EXERCISE HAPPENED on a date, as opposed to a property that holds.
# For these a runbook is not evidence: a runbook is the plan, undated and unchanged
# whether or not anyone ever executed it, so citing one says "we know how to do this",
# which is precisely the claim ADR-0242 says the estate must stop counting as a drill.
# Measured 2026-08-19: the fleet's ONLY drill attestation was
# `ledger.restore_drill … ref: runbook-0003`, and runbook-0003 is the PostgreSQL 16->18
# major-upgrade procedure — not a restore, not a drill, and dated nowhere. It raised a
# money-path readiness dimension from 2 to 3 on that basis.
EXERCISE_KEYS = {"restore_drill", "dr_drill"}

# A drill log entry is the durable artifact: `## YYYY-MM-DD — <scenario>` in one of these.
DRILL_LOGS = ("docs/bcp/dr-test-log.md", "docs/bcp/chaos-test-log.md")

# Exercise attestations that predate this rule and still cite a runbook. Shrink-only and
# checked BOTH WAYS: a new one fails, and an entry that healed is reported so the list
# cannot rot into a permanent exemption. Key: "<service>.<attestation key>".
EXERCISE_REF_DEBT: dict[str, str] = {}

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
ISSUE_RE = re.compile(r"^#\d+$")
RUNBOOK_RE = re.compile(r"^runbook-(\d+)$")
FIELD_RE = re.compile(r"(\w+):\s*([^\s,}]+)")

DEFAULT_WARN_WITHIN_DAYS = 30
DEFAULT_STALE_FAIL_DAYS = 30


# --------------------------------------------------------------------------- parsing
# Deliberately mirrors loadAttestations() in collect-prod-readiness.mjs. The question this
# gate has to answer is not "is this valid YAML" but "does the consumer SEE this entry" --
# a construct the collector's line grammar skips is exactly the silent failure.


def parse(text: str) -> list[dict]:
    """Return one record per attestation line the collector would ingest."""
    out: list[dict] = []
    cur_svc: str | None = None
    for lineno, raw in enumerate(text.split("\n"), start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if indent == 0 and line.endswith(":"):
            cur_svc = line[:-1]
        elif indent == 2 and "{" in line:
            key = line.split(":")[0].strip()
            fields = dict(FIELD_RE.findall(line))
            # The collector's regex also captures the entry key itself against the opening
            # brace; drop that so it is not mistaken for a field.
            fields.pop(key, None)
            out.append(
                {"lineno": lineno, "service": cur_svc, "key": key, "fields": fields}
            )
    return out


def yaml_pairs(text: str) -> set[tuple[str, str]] | None:
    """Every (service, key) pair a YAML reader sees. None if PyYAML is unavailable.

    This exists to catch the one thing a mirror of the collector's grammar can never catch
    on its own: an attestation written in BLOCK style. It is valid YAML and reads to a human
    exactly like an attestation, and the collector's line grammar -- which requires a `{` on
    the entry line -- skips it entirely. Without this cross-check both the collector and this
    gate would be silent about it, which is the failure this gate exists to end.
    """
    try:
        import yaml  # noqa: PLC0415 -- optional; the gate degrades rather than crashing
    except ImportError:  # pragma: no cover
        return None
    try:
        doc = yaml.safe_load(text)
    except Exception:
        # Not just YAMLError: a calendar-invalid timestamp such as 2026-02-30 raises a bare
        # ValueError out of the constructor. The line-grammar rules report that case with a
        # far better message, so degrade to skipping the cross-check rather than crashing.
        return None
    if not isinstance(doc, dict):
        return set()
    return {
        (svc, key)
        for svc, entries in doc.items()
        if isinstance(entries, dict)
        for key in entries
    }


def drill_log_records(repo: pathlib.Path, ref: str, date: str) -> bool:
    """True when `ref` is a drill log that actually carries an entry for `date`.

    The point is the artifact, not the citation: a log the attestation names but that
    never got the entry is the same silence as no evidence at all.
    """
    if ref.rstrip("/") not in DRILL_LOGS:
        return True  # not a drill log; other rules judge it
    path = repo / ref.rstrip("/")
    if not path.is_file():
        return False
    return re.search(rf"^##\s+{re.escape(date)}\b", path.read_text(encoding="utf-8"), re.MULTILINE) is not None


def ref_resolves(repo: pathlib.Path, ref: str) -> bool:
    """A `ref` must point at something durable and checkable."""
    if ref.startswith(("http://", "https://")):
        return True
    if ISSUE_RE.match(ref):
        return True
    m = RUNBOOK_RE.match(ref)
    if m:
        return any(repo.glob(f"docs/runbooks/{m.group(1)}-*.md"))
    if "/" in ref:
        return (repo / ref.rstrip("/")).exists()
    return False


def check(
    repo: pathlib.Path,
    file_rel: str,
    today: dt.date,
    warn_within: int = DEFAULT_WARN_WITHIN_DAYS,
    stale_fail_days: int = DEFAULT_STALE_FAIL_DAYS,
) -> tuple[list[str], list[str], int]:
    """Return (errors, warnings, n_attestations)."""
    path = repo / file_rel
    if not path.is_file():
        return ([f"{file_rel}: not found"], [], 0)

    text = path.read_text(encoding="utf-8")
    records = parse(text)
    errors: list[str] = []
    warnings: list[str] = []

    # Anything a YAML reader sees but the collector's grammar does not is an attestation
    # that exists on the page and nowhere else.
    seen = yaml_pairs(text)
    if seen is not None:
        ingested = {(r["service"], r["key"]) for r in records}
        for svc, key in sorted(seen - ingested):
            errors.append(
                f"{file_rel}: `{svc}.{key}` is valid YAML but the collector's parser never "
                f"ingests it -- an attestation must be written inline as "
                f"`{key}: {{ date: ..., ttl_days: N, by: ..., ref: ... }}`, not in block style"
            )

    for rec in records:
        where = f"{file_rel}:{rec['lineno']}"
        svc, key, f = rec["service"], rec["key"], rec["fields"]

        if not svc:
            errors.append(f"{where}: attestation `{key}` has no service heading above it")
            continue

        # R1 -- the service key must be the SHORT name the collector looks up. `consent`,
        # never `consent-service`: the latter resolves to openbank-consent-service-service.
        if not (repo / f"openbank-{svc}-service").is_dir():
            hint = ""
            if svc.endswith("-service") and (repo / f"openbank-{svc}").is_dir():
                hint = f" -- did you mean `{svc[: -len('-service')]}`?"
            elif (repo / f"openbank-{svc}").is_dir():
                # e.g. openbank-sepa-payment: a real money-path module whose directory has
                # no `-service` suffix, so the collector's allServices() regex never scores
                # it and no attestation for it can ever apply (#2364).
                hint = (
                    f" -- openbank-{svc} exists but has no `-service` suffix, so the "
                    f"collector scores no row for it at all (#2364); it cannot be attested "
                    f"until that is resolved"
                )
            errors.append(
                f"{where}: service `{svc}` does not resolve to openbank-{svc}-service, so "
                f"the collector looks it up under a name that never matches and this "
                f"attestation scores NOTHING{hint}"
            )
            continue

        # R2 -- an attestation key the collector never reads is a no-op.
        if key not in KNOWN_KEYS:
            errors.append(
                f"{where}: `{svc}.{key}` is not a key the collector consults "
                f"(known: {', '.join(sorted(KNOWN_KEYS))}) -- it would score nothing"
            )
            continue

        # R3 -- every documented field must be present.
        missing = [x for x in REQUIRED_FIELDS if x not in f]
        if missing:
            errors.append(
                f"{where}: `{svc}.{key}` is missing {', '.join(missing)} "
                f"(every attestation needs {', '.join(REQUIRED_FIELDS)})"
            )
            continue

        # R4 -- a real calendar date, not in the future.
        if not DATE_RE.match(f["date"]):
            errors.append(f"{where}: `{svc}.{key}` date {f['date']!r} is not YYYY-MM-DD")
            continue
        try:
            date = dt.date.fromisoformat(f["date"])
        except ValueError:
            errors.append(f"{where}: `{svc}.{key}` date {f['date']!r} is not a real date")
            continue
        if date > today:
            errors.append(
                f"{where}: `{svc}.{key}` is dated {date} -- in the future; an attestation "
                f"records an event that already happened"
            )
            continue

        # R5 -- a positive integer TTL. No ttl_days means the collector silently assumes
        # 365, which is how an entry outlives the thing it attests.
        try:
            ttl = int(f["ttl_days"])
        except ValueError:
            errors.append(f"{where}: `{svc}.{key}` ttl_days {f['ttl_days']!r} is not an integer")
            continue
        if ttl <= 0:
            errors.append(f"{where}: `{svc}.{key}` ttl_days must be positive, got {ttl}")
            continue

        # R6 -- the ref must be resolvable. This is what makes the entry checkable by a
        # reader who was not there.
        if not ref_resolves(repo, f["ref"]):
            errors.append(
                f"{where}: `{svc}.{key}` ref {f['ref']!r} resolves to nothing -- cite a URL, "
                f"a #issue, an existing repo path, or runbook-NNNN"
            )
            continue

        # R7 -- an EXERCISE key must cite evidence the exercise happened, not the procedure
        # for it. A runbook is undated and identical whether or not anyone ever ran it.
        if key in EXERCISE_KEYS:
            debt_key = f"{svc}.{key}"
            cites_runbook = bool(RUNBOOK_RE.match(f["ref"]))
            if cites_runbook and debt_key not in EXERCISE_REF_DEBT:
                errors.append(
                    f"{where}: `{debt_key}` cites {f['ref']!r} -- a runbook is the PLAN, not "
                    f"evidence anyone executed it, and it carries no date to compare against "
                    f"{f['date']}. Cite the drill record instead: an entry in "
                    f"{' or '.join(DRILL_LOGS)} dated {f['date']}, the run URL, or a #issue"
                )
                continue
            if not drill_log_records(repo, f["ref"], f["date"]):
                errors.append(
                    f"{where}: `{debt_key}` cites {f['ref']!r}, which carries no `## {f['date']}` "
                    f"entry -- the log it names never got the record, so the attestation is "
                    f"backed by nothing a reader can check"
                )
                continue

        # Freshness. Exact calendar arithmetic, deliberately: the collector approximates a
        # month as 30 days, which lets a TTL run a day or two past its own expiry.
        age = (today - date).days
        remaining = ttl - age
        if remaining < 0:
            msg = (
                f"{where}: `{svc}.{key}` EXPIRED {-remaining} day(s) ago "
                f"({date} + {ttl}d); the collector no longer counts it, but the file still "
                f"reads as a live claim"
            )
            if -remaining > stale_fail_days:
                errors.append(
                    msg + f" -- expired for more than {stale_fail_days} days: delete the "
                    f"entry, or re-attest a real event"
                )
            else:
                warnings.append(msg)
        elif remaining <= warn_within:
            warnings.append(
                f"{where}: `{svc}.{key}` decays in {remaining} day(s) "
                f"(on {date + dt.timedelta(days=ttl)}) -- the dimension falls back to its "
                f"derived score then"
            )

    # The debt list is checked the other way too: an entry that healed must leave, or the
    # exemption silently becomes permanent and reads as a discharged obligation.
    # Only against the real file: the self-test's fixtures are single-entry synthetic
    # documents, and asking them about fleet-wide debt would make every unrelated case red.
    live = {
        f"{r['service']}.{r['key']}"
        for r in records
        if r["key"] in EXERCISE_KEYS and RUNBOOK_RE.match(r["fields"].get("ref", ""))
    }
    for stale in sorted(set(EXERCISE_REF_DEBT) - live) if file_rel == FILE_REL else []:
        errors.append(
            f"{file_rel}: `{stale}` is in EXERCISE_REF_DEBT but no longer cites a runbook "
            f"-- remove the entry from check-readiness-attestations.py"
        )

    return (errors, warnings, len(records))


# --------------------------------------------------------------------------- self-test

_TODAY = dt.date(2026, 8, 6)


def _self_test(stale_fail_days: int = DEFAULT_STALE_FAIL_DAYS) -> int:
    """Falsify in BOTH directions: it must flag what is broken and pass what is true."""
    good_ref = "https://github.com/JiRaska/open-bank-oss/actions/runs/1"
    cases: list[tuple[str, str, str, bool]] = [
        # (name, body, expect: 'error' | 'warn' | 'clean')
        (
            "TRUE ENTRY must not be flagged",
            f"ledger:\n  pentest:       {{ date: 2026-08-01, ttl_days: 365, by: ext, ref: {good_ref} }}\n",
            "clean",
            True,
        ),
        (
            # This line used to be the gate's own proof that a runbook is acceptable
            # evidence for a drill. It is not (R7) — it is the fleet's one baselined debt,
            # and the case now tests the EXEMPTION, not the rule.
            "BASELINED DEBT: ledger.restore_drill's runbook ref is exempt, not endorsed",
            "ledger:\n  restore_drill: { date: 2026-07-26, ttl_days: 180, by: jiri, ref: runbook-0003 }\n",
            "clean",
            True,
        ),
        (
            "R7: an exercise attestation citing a runbook fails for anyone not baselined",
            "consent:\n  dr_drill: { date: 2026-07-26, ttl_days: 180, by: jiri, ref: runbook-0003 }\n",
            "error",
            True,
        ),
        (
            "R7: a drill log that never got the entry is not evidence",
            ("consent:\n  dr_drill: { date: 2026-07-26, ttl_days: 180, by: jiri, "
             "ref: docs/bcp/dr-test-log.md }\n"),
            "error",
            True,
        ),
        (
            "R7 TRUE ENTRY: a drill log carrying that exact date is evidence",
            ("consent:\n  dr_drill: { date: 2026-06-30, ttl_days: 180, by: jiri, "
             "ref: docs/bcp/dr-test-log.md }\n"),
            "clean",
            True,
        ),
        (
            "the real #2365 defect: service key is the LONG name, so it scores nothing",
            f"consent-service:\n  pentest:       {{ date: 2026-08-01, ttl_days: 365, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "service that does not exist at all",
            f"nosuchsvc:\n  pentest:       {{ date: 2026-08-01, ttl_days: 365, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "attestation key the collector never reads",
            f"ledger:\n  penetration:   {{ date: 2026-08-01, ttl_days: 365, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "MALFORMED: missing ref and by",
            "ledger:\n  pentest:       { date: 2026-08-01, ttl_days: 365 }\n",
            "error",
            True,
        ),
        (
            "MALFORMED: missing ttl_days -- the collector would silently assume 365",
            f"ledger:\n  pentest:       {{ date: 2026-08-01, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "MALFORMED: date is not a date",
            f"ledger:\n  pentest:       {{ date: yesterday, ttl_days: 365, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "MALFORMED: calendar-invalid date",
            f"ledger:\n  pentest:       {{ date: 2026-02-30, ttl_days: 365, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "FUTURE-DATED: attests an event that has not happened",
            f"ledger:\n  pentest:       {{ date: 2026-12-01, ttl_days: 365, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "MALFORMED: ttl_days not an integer",
            f"ledger:\n  pentest:       {{ date: 2026-08-01, ttl_days: forever, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "MALFORMED: ttl_days zero",
            f"ledger:\n  pentest:       {{ date: 2026-08-01, ttl_days: 0, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "UNRESOLVABLE REF: a phrase, not an artefact",
            "ledger:\n  pentest:       { date: 2026-08-01, ttl_days: 365, by: ci, ref: we-did-one }\n",
            "error",
            True,
        ),
        (
            "UNRESOLVABLE REF: a repo path that does not exist",
            "ledger:\n  pentest:       { date: 2026-08-01, ttl_days: 365, by: ci, ref: docs/nope/none.md }\n",
            "error",
            True,
        ),
        (
            "UNRESOLVABLE REF: runbook number nobody wrote",
            "ledger:\n  restore_drill: { date: 2026-08-01, ttl_days: 180, by: ci, ref: runbook-9999 }\n",
            "error",
            True,
        ),
        (
            "EXPIRED yesterday: warns, does not fail",
            f"ledger:\n  pentest:       {{ date: 2026-07-15, ttl_days: 21, by: ci, ref: {good_ref} }}\n",
            "warn",
            True,
        ),
        (
            "EXPIRED long ago: fails",
            f"ledger:\n  pentest:       {{ date: 2026-01-01, ttl_days: 21, by: ci, ref: {good_ref} }}\n",
            "error",
            True,
        ),
        (
            "DECAYING SOON: warns with a countdown",
            f"ledger:\n  pentest:       {{ date: 2026-07-26, ttl_days: 21, by: ci, ref: {good_ref} }}\n",
            "warn",
            True,
        ),
        (
            "PROSE-VS-THING: a commented-out example must never be read as an attestation",
            "# ledger:\n#   pentest: { date: 2026-12-01, ttl_days: 0, by: nobody, ref: nothing }\n",
            "clean",
            False,
        ),
        (
            "BLOCK STYLE: valid YAML the collector's parser never ingests",
            "ledger:\n  pentest:\n    date: 2026-08-01\n    ttl_days: 365\n    by: ci\n"
            f"    ref: {good_ref}\n",
            "error",
            False,
        ),
    ]

    failures = 0
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        # The sandbox needs the artefacts the "true entry" cases cite.
        (tmp / "openbank-ledger-service").mkdir()
        (tmp / "openbank-consent-service").mkdir()
        (tmp / "docs/runbooks").mkdir(parents=True)
        (tmp / "docs/runbooks/0003-postgresql-16-to-18-major-upgrade.md").write_text("x")
        (tmp / "docs/bcp").mkdir(parents=True)
        (tmp / "docs/bcp/dr-test-log.md").write_text(
            "# DR Test Log\n\n## 2026-06-30 — table-top\n- **Type**: table-top\n")
        rel = "att.yaml"

        for name, body, expect, expect_one in cases:
            (tmp / rel).write_text("# a header comment\n\n" + body, encoding="utf-8")
            errors, warnings, n = check(tmp, rel, _TODAY, stale_fail_days=stale_fail_days)
            got = "error" if errors else ("warn" if warnings else "clean")
            want_n = 1 if expect_one else 0
            if n != want_n:
                print(f"::error::self-test '{name}': parsed {n} attestation(s), expected {want_n}")
                failures += 1
                continue
            if got != expect:
                print(
                    f"::error::self-test '{name}': expected {expect}, got {got} "
                    f"-- errors={errors} warnings={warnings}"
                )
                failures += 1
            else:
                print(f"  ok  [{got:5}] {name}")

        # Scope guard: an empty file must report zero, and zero must be visible.
        (tmp / rel).write_text("# nothing attested\n", encoding="utf-8")
        errors, warnings, n = check(tmp, rel, _TODAY)
        if errors or warnings or n != 0:
            print("::error::self-test: an empty file should be clean with a count of 0")
            failures += 1
        else:
            print("  ok  [clean] an empty file is clean and reports 0 attestations")

        # Scope guard: a missing file is an error, never a silent green.
        errors, warnings, n = check(tmp, "does-not-exist.yaml", _TODAY)
        if not errors:
            print("::error::self-test: a missing attestations file was not reported")
            failures += 1
        else:
            print("  ok  [error] a missing attestations file is reported")

    if failures:
        print(f"::error::self-test: {failures} case(s) failed")
        return 1
    print(f"self-test: all {len(cases) + 2} cases passed (both directions)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--self-test", action="store_true", help="run the built-in cases")
    ap.add_argument("--today", help="override today's date (YYYY-MM-DD), for testing")
    ap.add_argument(
        "--warn-within-days",
        type=int,
        default=DEFAULT_WARN_WITHIN_DAYS,
        help="warn this many days before an attestation decays",
    )
    ap.add_argument(
        "--stale-fail-days",
        type=int,
        default=DEFAULT_STALE_FAIL_DAYS,
        help="an attestation expired longer than this is an ERROR, not a warning",
    )
    args = ap.parse_args()

    if args.self_test:
        return _self_test(stale_fail_days=args.stale_fail_days)

    today = dt.date.fromisoformat(args.today) if args.today else dt.date.today()
    errors, warnings, n = check(
        REPO, FILE_REL, today, args.warn_within_days, args.stale_fail_days
    )

    # Always print the count. A green claiming 0 attestations means the file was never read.
    print(f"readiness-attestations: checked {n} attestation(s) in {FILE_REL} (as of {today})")

    for w in warnings:
        print(f"::warning::{w}")
    for e in errors:
        print(f"::error::{e}")

    if errors:
        print(f"::error::{len(errors)} attestation problem(s) in {FILE_REL}")
        return 1
    if warnings:
        print(
            f"readiness-attestations: format OK; {len(warnings)} freshness warning(s) "
            f"(decay is advisory -- the collector already stops counting an expired entry)"
        )
    else:
        print("readiness-attestations: every attestation is well-formed and fresh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
