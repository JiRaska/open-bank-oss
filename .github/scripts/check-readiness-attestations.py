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
import importlib.util
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

# --------------------------------------------------------------------------- R8: fuzz floor
# A `pentest` attested by an AUTOMATED fuzz lane is only as good as what the lane exercised,
# and a green job says nothing about that (#5769).
#
# Measured on run 32103867665 (all 26 fuzzed services, the lane's own numbers):
#
#   consent      13 selected / 13 total, 10 auth-only ->  3 exercised — and it found two 5xx
#   billing       5 selected /  5 total,  4 auth-only ->  1 exercised
#   settlement    1 selected /  1 total,  1 auth-only ->  0 exercised
#   the other 23                                      ->  0 exercised
#
# Every one of those jobs is the same shade of green. Twenty-four of the twenty-six proved
# that the service boots and that auth is uniformly enforced — worth knowing, not a pentest.
#
# WHY A COUNT OF EXERCISED OPERATIONS, AND NOT A RATIO
#   A ratio cannot see this at all: settlement is 1 selected / 1 total = 100% of its surface,
#   and so is consent at 13/13. The lane's v1 scope is the UNAUTHENTICATED surface, so the
#   only number that separates "adversarially tested" from "bounced off 401" is how many
#   operations answered something other than an auth error:
#
#       exercised = selected - auth_errors
#
# WHY 3
#   Not a ratio, not 1. `exercised >= 1` admits billing and any settlement-shaped service —
#   a single route is not a surface, and one operation cannot exercise routing, parameter
#   conversion and the exception mapper across differently-shaped endpoints. 3 is the
#   smallest count this repo has evidence for: consent sits exactly there and is the only
#   service the lane has ever produced a genuine finding from. It is deliberately a FLOOR
#   under today's fleet rather than an aspiration — the intended direction is upward, once
#   authenticated fuzzing (the v2 follow-up in api-fuzz.yml's header) makes a real surface
#   reachable. Raising it is a one-line change here; the fleet picture is in fuzz-coverage.yaml.
#
# WHAT THIS DOES NOT CLAIM
#   It cannot prove the fuzzing was good, only that something beyond the auth layer was
#   reached. And it applies to the FUZZ lane only: ledger's pentest is attested by
#   ci-zap-baseline, a different tool with a different scope, and is untouched by R8.
FUZZ_COVERAGE_REL = "openbank-libs/governance/fuzz-coverage.yaml"

# R8's SCOPE, derived rather than restated. fuzz-coverage.yaml used to enumerate its own
# services by hand while api-fuzz.yml derived the identical set inline -- and a gate whose
# scope is a hand-kept list of the thing it checks reads as PASSING when the list is short,
# never as UNCHECKED (the `pact-drift-check.yml` lesson, #5849). Both sides now call
# .github/scripts/derive-fuzz-scope.py, and the reconciliation below fails in BOTH
# directions: a fuzzed service with no coverage entry, and a coverage entry for a service
# the lane no longer fuzzes.
def _load_derive():
    spec = importlib.util.spec_from_file_location(
        "derive_fuzz_scope", pathlib.Path(__file__).resolve().parent / "derive-fuzz-scope.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# `by:` values that mean "an api-fuzz.yml schemathesis job". Anything else -- a human, an
# external firm, a different tool -- is out of R8's scope and judged by the other rules.
FUZZ_LANES = {"ci-schemathesis"}

MIN_EXERCISED_OPS = 3

COVERAGE_FIELDS = ("date", "run", "selected", "total", "auth_errors", "state")

# How stale a coverage measurement may be before the floor is being checked against
# archaeology. The lane runs twice a week; 90 days is ~26 missed runs.
DEFAULT_COVERAGE_MAX_AGE_DAYS = 90

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


def module_dir(repo: pathlib.Path, svc: str) -> pathlib.Path | None:
    """Resolve a SHORT service key to its module directory, or None.

    Two shapes exist: `openbank-consent-service` and, for a handful of money-path modules,
    `openbank-sepa-payment` with no suffix (#2364). Attestations only work for the former
    (the collector scores no row for the latter), but the fuzz lane covers both, so the
    coverage file must be able to name them.
    """
    for cand in (repo / f"openbank-{svc}-service", repo / f"openbank-{svc}"):
        if cand.is_dir():
            return cand
    return None


def parse_coverage(text: str) -> list[dict]:
    """One record per `  coverage: { ... }` line under a service heading.

    Same line grammar as parse() above, and for the same reason: a block-style entry that
    reads like coverage to a human but is invisible to the parser is the failure mode this
    whole gate exists to end.
    """
    out: list[dict] = []
    cur_svc: str | None = None
    for lineno, raw in enumerate(text.split("\n"), start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if indent == 0 and line.endswith(":"):
            cur_svc = line[:-1]
        elif indent == 2 and line.startswith("coverage:") and "{" in line:
            fields = dict(FIELD_RE.findall(line))
            fields.pop("coverage", None)
            out.append({"lineno": lineno, "service": cur_svc, "fields": fields})
    return out


def check_coverage(
    repo: pathlib.Path, file_rel: str, today: dt.date, max_age: int
) -> tuple[dict[str, dict], list[str], list[str]]:
    """Validate fuzz-coverage.yaml and return {service: record} for R8.

    A record only enters the returned map if it is WELL-FORMED. A malformed line therefore
    cannot buy an attestation -- it is an error here AND an absent coverage record there.
    """
    path = repo / file_rel
    errors: list[str] = []
    warnings: list[str] = []
    if not path.is_file():
        return ({}, [f"{file_rel}: not found -- R8 cannot check any fuzz-lane pentest"], [])

    by_svc: dict[str, dict] = {}
    for rec in parse_coverage(path.read_text(encoding="utf-8")):
        where = f"{file_rel}:{rec['lineno']}"
        svc, f = rec["service"], rec["fields"]
        if not svc:
            errors.append(f"{where}: coverage entry has no service heading above it")
            continue
        if module_dir(repo, svc) is None:
            errors.append(
                f"{where}: `{svc}` resolves to neither openbank-{svc}-service nor "
                f"openbank-{svc} -- coverage for a module that does not exist"
            )
            continue
        missing = [x for x in COVERAGE_FIELDS if x not in f]
        if missing:
            errors.append(
                f"{where}: `{svc}` coverage is missing {', '.join(missing)} "
                f"(needs {', '.join(COVERAGE_FIELDS)})"
            )
            continue
        if not DATE_RE.match(f["date"]):
            errors.append(f"{where}: `{svc}` coverage date {f['date']!r} is not YYYY-MM-DD")
            continue
        try:
            date = dt.date.fromisoformat(f["date"])
        except ValueError:
            errors.append(f"{where}: `{svc}` coverage date {f['date']!r} is not a real date")
            continue
        if date > today:
            errors.append(f"{where}: `{svc}` coverage is dated {date} -- in the future")
            continue
        try:
            selected = int(f["selected"])
            total = int(f["total"])
            auth = int(f["auth_errors"])
        except ValueError:
            errors.append(
                f"{where}: `{svc}` selected/total/auth_errors must be integers -- got "
                f"{f['selected']!r}/{f['total']!r}/{f['auth_errors']!r}"
            )
            continue
        if not f["run"].startswith(("http://", "https://")):
            errors.append(
                f"{where}: `{svc}` run {f['run']!r} is not a run URL -- these numbers are the "
                f"lane's own output and must point at the job that printed them"
            )
            continue
        if min(selected, total, auth) < 0 or selected > total or auth > selected:
            errors.append(
                f"{where}: `{svc}` numbers are not internally consistent "
                f"(selected={selected}, total={total}, auth_errors={auth}) -- schemathesis "
                f"cannot report more auth errors than operations it selected"
            )
            continue
        exercised = selected - auth
        derived = "fuzzed" if exercised >= MIN_EXERCISED_OPS else "auth-only-surface"
        if f["state"] != derived:
            errors.append(
                f"{where}: `{svc}` says state: {f['state']} but {selected} selected minus "
                f"{auth} auth-only = {exercised} exercised, which is `{derived}` "
                f"(floor {MIN_EXERCISED_OPS}) -- the state is derived from the numbers, "
                f"it is not a field to assert"
            )
            continue
        if svc in by_svc:
            errors.append(f"{where}: `{svc}` has a second coverage entry; keep exactly one")
            continue
        age = (today - date).days
        if age > max_age:
            warnings.append(
                f"{where}: `{svc}` coverage was measured {age} day(s) ago ({date}) -- older "
                f"than {max_age}; re-run api-fuzz.yml and paste the current numbers"
            )
        by_svc[svc] = {
            "lineno": rec["lineno"],
            "date": date,
            "age": age,
            "selected": selected,
            "total": total,
            "auth_errors": auth,
            "exercised": exercised,
            "state": derived,
            "run": f["run"],
        }
    return (by_svc, errors, warnings)


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


def coverage_service_keys(text: str) -> list[str]:
    """Every top-level service heading in fuzz-coverage.yaml, well-formed entry or not.

    Deliberately NOT the well-formed map from check_coverage(): a malformed entry is already
    reported on its own terms, and counting it as "missing" here too would report one defect
    twice while telling the reader to add a line that is already there.
    """
    out: list[str] = []
    for raw in text.split("\n"):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw[:1] not in (" ", "\t") and raw.strip().endswith(":"):
            out.append(raw.strip()[:-1])
    return out


def check_coverage_scope(
    coverage_text: str, derived: set[str], file_rel: str = FUZZ_COVERAGE_REL
) -> list[str]:
    """Reconcile fuzz-coverage.yaml's service set against the lane's DERIVED scope.

    Both directions are errors, and that is the whole point:

      MISSING -- the lane fuzzes the service and nothing records what it exercised. This is
        the direction that used to be invisible: the coverage gate only ever validated the
        entries that were present, so deleting one made the gate quieter, not redder.
      STALE -- an entry for a service the lane no longer fuzzes. Its numbers can never be
        refreshed, so it decays into a permanent claim about a job that stopped running.
    """
    present = coverage_service_keys(coverage_text)
    errors: list[str] = []

    dupes = sorted({k for k in present if present.count(k) > 1})
    for k in dupes:
        errors.append(f"{file_rel}: `{k}` appears as a heading more than once; keep exactly one")

    missing = sorted(derived - set(present))
    stale = sorted(set(present) - derived)

    for svc in missing:
        errors.append(
            f"{file_rel}: `{svc}` is in the api-fuzz scope (derive-fuzz-scope.py) but has no "
            f"coverage entry -- the lane fuzzes it and nothing records what it exercised. "
            f"Run api-fuzz.yml and paste the block it prints for `{svc}`"
        )
    for svc in stale:
        errors.append(
            f"{file_rel}: `{svc}` has a coverage entry but is NOT in the api-fuzz scope "
            f"(derive-fuzz-scope.py) -- the lane does not fuzz it, so these numbers can never "
            f"be refreshed. Remove the entry, or restore the service to the scope"
        )
    return errors


def check(
    repo: pathlib.Path,
    file_rel: str,
    today: dt.date,
    warn_within: int = DEFAULT_WARN_WITHIN_DAYS,
    stale_fail_days: int = DEFAULT_STALE_FAIL_DAYS,
    coverage_rel: str = FUZZ_COVERAGE_REL,
    coverage_max_age: int = DEFAULT_COVERAGE_MAX_AGE_DAYS,
) -> tuple[list[str], list[str], int]:
    """Return (errors, warnings, n_attestations)."""
    path = repo / file_rel
    if not path.is_file():
        return ([f"{file_rel}: not found"], [], 0)

    text = path.read_text(encoding="utf-8")
    records = parse(text)
    errors: list[str] = []
    warnings: list[str] = []

    # R8's evidence base. Validated up front so a malformed coverage line is reported on its
    # own terms AND cannot silently satisfy an attestation below.
    coverage, cov_errors, cov_warnings = check_coverage(
        repo, coverage_rel, today, coverage_max_age
    )
    errors.extend(cov_errors)
    warnings.extend(cov_warnings)

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

        # R8 -- a pentest attested by an automated FUZZ lane must clear the exercised-
        # operations floor. A green api-fuzz job is not interchangeable across services
        # (#5769); see the MIN_EXERCISED_OPS block at the top for the measurement and the
        # reasoning behind the number.
        if key == "pentest" and f["by"] in FUZZ_LANES:
            cov = coverage.get(svc)
            if cov is None:
                errors.append(
                    f"{where}: `{svc}.pentest` is attested by the fuzz lane ({f['by']}) but "
                    f"{coverage_rel} records no coverage for `{svc}` -- a green job alone "
                    f"does not say what it exercised. Run api-fuzz.yml and paste the block "
                    f"it prints; a service whose unauthenticated surface is empty belongs "
                    f"there as `auth-only-surface`, not here"
                )
                continue
            if cov["exercised"] < MIN_EXERCISED_OPS:
                errors.append(
                    f"{where}: `{svc}.pentest` cannot be attested off {f['by']}: the lane "
                    f"selected {cov['selected']}/{cov['total']} operation(s) and "
                    f"{cov['auth_errors']} of them returned only authentication errors, so "
                    f"{cov['exercised']} operation(s) were actually exercised (floor "
                    f"{MIN_EXERCISED_OPS}, {coverage_rel}:{cov['lineno']}). That green "
                    f"establishes the service boots and enforces auth uniformly -- both "
                    f"worth knowing, neither a pentest. Delete this entry; the service is "
                    f"already recorded as `auth-only-surface`, which is the honest state and "
                    f"the argument for authenticated fuzzing (api-fuzz.yml, v2 follow-up)"
                )
                continue
            if cov["age"] > coverage_max_age:
                errors.append(
                    f"{where}: `{svc}.pentest` clears the floor only against a coverage "
                    f"measurement from {cov['date']} ({cov['age']} days old, max "
                    f"{coverage_max_age}) -- re-measure before re-attesting"
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
    run_url = "https://github.com/JiRaska/open-bank-oss/actions/runs/32103867665"

    def cov(svc: str, sel: int, tot: int, auth: int, state: str, date: str = "2026-08-01") -> str:
        return (
            f"{svc}:\n  coverage: {{ date: {date}, run: {run_url}, selected: {sel}, "
            f"total: {tot}, auth_errors: {auth}, state: {state} }}\n"
        )

    # The consent numbers below are the real ones from run 32103867665.
    default_cov = cov("consent", 13, 13, 10, "fuzzed")

    # (name, body, expect, expect_one_attestation, coverage-file body)
    # A 5th element overrides the coverage fixture for that case; most cases do not care.
    cases: list[tuple] = [
        # (name, body, expect: 'error' | 'warn' | 'clean')
        (
            "TRUE ENTRY must not be flagged",
            f"ledger:\n  pentest:       {{ date: 2026-08-01, ttl_days: 365, by: ext, ref: {good_ref} }}\n",
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
        # --- R8: the fuzz-coverage floor (#5769) ---------------------------------
        # Both directions on the two ends of the REAL measured range. If either of these
        # stops discriminating, the floor has become decoration.
        (
            "R8 SETTLEMENT-SHAPED: 1 op selected, all of it auth-only -> must NOT mint",
            f"settlement:\n  pentest: {{ date: 2026-08-01, ttl_days: 21, by: ci-schemathesis, ref: {good_ref} }}\n",
            "error",
            True,
            cov("settlement", 1, 1, 1, "auth-only-surface"),
        ),
        (
            "R8 CONSENT-SHAPED: 13/13 with 10 auth-only = 3 exercised -> still mints",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 365, by: ci-schemathesis, ref: {good_ref} }}\n",
            "clean",
            True,
            default_cov,
        ),
        (
            # The floor's own boundary. 2 must fail and 3 must pass, or the number in
            # MIN_EXERCISED_OPS is not the thing being enforced.
            "R8 BOUNDARY: 2 exercised is below the floor",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 21, by: ci-schemathesis, ref: {good_ref} }}\n",
            "error",
            True,
            cov("consent", 13, 13, 11, "auth-only-surface"),
        ),
        (
            "R8 BOUNDARY: exactly 3 exercised clears it",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 365, by: ci-schemathesis, ref: {good_ref} }}\n",
            "clean",
            True,
            cov("consent", 3, 20, 0, "fuzzed"),
        ),
        (
            "R8: a fuzz-lane pentest with no coverage record at all is a bare green",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 21, by: ci-schemathesis, ref: {good_ref} }}\n",
            "error",
            True,
            "# nothing measured\n",
        ),
        (
            # R8 is scoped to the fuzz lane. ledger's pentest comes from ci-zap-baseline,
            # a different tool with a different scope, and must not be caught by it.
            "R8 SCOPE: a non-fuzz lane is judged by the other rules, not the floor",
            f"ledger:\n  pentest: {{ date: 2026-08-01, ttl_days: 365, by: ci-zap-baseline, ref: {good_ref} }}\n",
            "clean",
            True,
            default_cov,
        ),
        (
            "R8: coverage measured too long ago is archaeology, not a floor",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 21, by: ci-schemathesis, ref: {good_ref} }}\n",
            "error",
            True,
            cov("consent", 13, 13, 10, "fuzzed", date="2026-01-01"),
        ),
        (
            "COVERAGE: a state that disagrees with its own numbers",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 21, by: ci-schemathesis, ref: {good_ref} }}\n",
            "error",
            True,
            cov("settlement", 1, 1, 1, "fuzzed"),
        ),
        (
            "COVERAGE: more auth errors than operations selected is not a possible reading",
            "# no attestations\n",
            "error",
            False,
            cov("consent", 3, 13, 9, "auth-only-surface"),
        ),
        (
            "COVERAGE: block style is valid YAML the parser never ingests -> no record",
            f"consent:\n  pentest: {{ date: 2026-08-01, ttl_days: 21, by: ci-schemathesis, ref: {good_ref} }}\n",
            "error",
            True,
            "consent:\n  coverage:\n    selected: 13\n    total: 13\n    auth_errors: 10\n",
        ),
        (
            "COVERAGE: a run URL is required -- these numbers are a lane's output",
            "# no attestations\n",
            "error",
            False,
            "consent:\n  coverage: { date: 2026-08-01, run: we-ran-it, selected: 13, "
            "total: 13, auth_errors: 10, state: fuzzed }\n",
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
        (tmp / "openbank-settlement-service").mkdir()
        rel = "att.yaml"
        cov_rel = "cov.yaml"
        (tmp / cov_rel).write_text("# coverage fixture\n", encoding="utf-8")

        for case in cases:
            name, body, expect, expect_one = case[:4]
            cov_body = case[4] if len(case) > 4 else default_cov
            (tmp / rel).write_text("# a header comment\n\n" + body, encoding="utf-8")
            (tmp / cov_rel).write_text("# coverage fixture\n\n" + cov_body, encoding="utf-8")
            errors, warnings, n = check(
                tmp, rel, _TODAY, stale_fail_days=stale_fail_days, coverage_rel=cov_rel
            )
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

        # Scope guard: the EXEMPTION MECHANISM, now that EXERCISE_REF_DEBT is empty.
        #
        # #5673 emptied the list: the drill it covered really happened (#2495) and its record
        # now lives in docs/bcp/dr-test-log.md, so nothing is exempt any more. That removed the
        # gate's only live exercise of the exemption path -- and an unexercised escape hatch is
        # exactly the kind of code that is discovered to be broken by the next person who needs
        # it. So the case no longer reads the real list; it injects a debt entry, asserts the
        # SAME body flips from error to clean, and asserts the stale-declaration direction. Both
        # halves must move, or the exemption is not what makes the difference.
        debt_body = "ledger:\n  restore_drill: { date: 2026-07-26, ttl_days: 180, by: jiri, ref: runbook-0003 }\n"
        (tmp / rel).write_text(debt_body, encoding="utf-8")
        # R8 reads fuzz-coverage.yaml and reports a hard error when the file is absent, which in a
        # fixture directory it always is. Without this the case below asserts `if errors:` against an
        # error about a MISSING FILE rather than about the exemption it is testing -- it would fail
        # for a reason that has nothing to do with EXERCISE_REF_DEBT. An empty coverage file is
        # correct here: this fixture has no services, so the derived scope is empty too and the two
        # reconcile.
        coverage_fixture = tmp / FUZZ_COVERAGE_REL
        coverage_fixture.parent.mkdir(parents=True, exist_ok=True)
        coverage_fixture.write_text("", encoding="utf-8")

        errors, _, _ = check(tmp, rel, _TODAY)
        if not errors:
            print("::error::self-test: a runbook ref was clean with EXERCISE_REF_DEBT empty")
            failures += 1
        else:
            print("  ok  [error] with the debt list EMPTY, a runbook ref for a drill fails")

        _saved = dict(EXERCISE_REF_DEBT)
        try:
            EXERCISE_REF_DEBT["ledger.restore_drill"] = "self-test injected exemption"
            errors, _, _ = check(tmp, rel, _TODAY)
            if errors:
                print(
                    "::error::self-test: EXERCISE_REF_DEBT no longer exempts a baselined entry "
                    f"-- errors={errors}"
                )
                failures += 1
            else:
                print("  ok  [clean] an INJECTED debt entry exempts that same runbook ref")

            # ...and the other direction: exempt something that no longer cites a runbook and
            # the stale-declaration error must fire, so the list cannot rot into a permanent
            # exemption once the data is fixed.
            # This one must be written at FILE_REL: the stale-declaration check is scoped
            # `if file_rel == FILE_REL`, so against the self-test's own "att.yaml" it silently
            # does nothing -- which is why this direction had never actually been exercised.
            real = tmp / FILE_REL
            real.parent.mkdir(parents=True, exist_ok=True)
            real.write_text(
                "ledger:\n  restore_drill: { date: 2026-06-30, ttl_days: 180, by: jiri, "
                "ref: docs/bcp/dr-test-log.md }\n",
                encoding="utf-8",
            )
            (tmp / "docs" / "bcp").mkdir(parents=True, exist_ok=True)
            (tmp / "docs" / "bcp" / "dr-test-log.md").write_text(
                "## 2026-06-30 — entry\n", encoding="utf-8"
            )
            errors, _, _ = check(tmp, FILE_REL, _TODAY)
            if not any("no longer cites a runbook" in e for e in errors):
                print(
                    "::error::self-test: a STALE EXERCISE_REF_DEBT declaration was not reported "
                    f"-- errors={errors}"
                )
                failures += 1
            else:
                print("  ok  [error] a debt entry whose attestation healed is reported as stale")
        finally:
            EXERCISE_REF_DEBT.clear()
            EXERCISE_REF_DEBT.update(_saved)

        # Scope guard: an empty file must report zero, and zero must be visible.
        (tmp / rel).write_text("# nothing attested\n", encoding="utf-8")
        (tmp / cov_rel).write_text(default_cov, encoding="utf-8")
        errors, warnings, n = check(tmp, rel, _TODAY, coverage_rel=cov_rel)
        if errors or warnings or n != 0:
            print("::error::self-test: an empty file should be clean with a count of 0")
            failures += 1
        else:
            print("  ok  [clean] an empty file is clean and reports 0 attestations")

        # Scope guard: a missing file is an error, never a silent green.
        errors, warnings, n = check(tmp, "does-not-exist.yaml", _TODAY, coverage_rel=cov_rel)
        if not errors:
            print("::error::self-test: a missing attestations file was not reported")
            failures += 1
        else:
            print("  ok  [error] a missing attestations file is reported")

        # --- the coverage SCOPE reconciliation, falsified in both directions (#5849) -----
        # These do not go through check(): the derived scope is an input here, so the cases
        # can state the exact drift they mean instead of building a fake service tree.
        two = cov("consent", 13, 13, 10, "fuzzed") + cov("settlement", 1, 1, 1, "auth-only-surface")
        scope_cases = [
            ("scope: file matches the derived scope exactly", two,
             {"consent", "settlement"}, None),
            ("scope: a fuzzed service with NO coverage entry is an error", two,
             {"consent", "settlement", "ledger"}, "ledger"),
            ("scope: a coverage entry for a service NOT fuzzed is an error", two,
             {"consent"}, "settlement"),
            ("scope: an empty coverage file against a non-empty scope is an error", "",
             {"consent"}, "consent"),
        ]
        for name, body, derived_set, must_name in scope_cases:
            errs = check_coverage_scope(body, derived_set, "cov.yaml")
            if must_name is None:
                if errs:
                    print(f"::error::self-test '{name}': expected clean, got {errs}")
                    failures += 1
                else:
                    print(f"  ok  [clean] {name}")
            elif not errs:
                print(f"::error::self-test '{name}': expected an error, got none")
                failures += 1
            elif not any(f"`{must_name}`" in e for e in errs):
                print(f"::error::self-test '{name}': error did not NAME {must_name}: {errs}")
                failures += 1
            else:
                print(f"  ok  [error] {name}")

    if failures:
        print(f"::error::self-test: {failures} case(s) failed")
        return 1
    print(f"self-test: all {len(cases) + 5 + len(scope_cases)} cases passed (both directions)")
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
        "--coverage-max-age-days",
        type=int,
        default=DEFAULT_COVERAGE_MAX_AGE_DAYS,
        help="a fuzz-coverage measurement older than this can no longer carry R8's floor",
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
        REPO,
        FILE_REL,
        today,
        args.warn_within_days,
        args.stale_fail_days,
        coverage_max_age=args.coverage_max_age_days,
    )

    # The fuzz picture, printed on every run. It is deliberately ONE line plus a named list
    # rather than a warning per service: 25 warnings every CI run is the alert-fatigue trade
    # this repo has already paid for, and a gap nobody reads is the gap this gate is about.
    coverage, _, _ = check_coverage(REPO, FUZZ_COVERAGE_REL, today, args.coverage_max_age_days)

    # The scope reconciliation. Derived, never restated -- and it runs on every invocation,
    # so a service added to or dropped from the lane is red here in the same commit.
    cov_path = REPO / FUZZ_COVERAGE_REL
    derived = _load_derive().short_scope(REPO)
    scope_errors = check_coverage_scope(
        cov_path.read_text(encoding="utf-8") if cov_path.is_file() else "", derived
    )
    errors.extend(scope_errors)
    print(
        f"api-fuzz scope: {len(derived)} service(s) derived from "
        f"derive-fuzz-scope.py; {FUZZ_COVERAGE_REL} records "
        f"{len(coverage_service_keys(cov_path.read_text(encoding='utf-8'))) if cov_path.is_file() else 0}"
    )

    if coverage:
        above = sorted(k for k, v in coverage.items() if v["exercised"] >= MIN_EXERCISED_OPS)
        below = sorted(set(coverage) - set(above))
        print(
            f"api-fuzz coverage: {len(above)}/{len(coverage)} service(s) clear the "
            f"exercised-operations floor ({MIN_EXERCISED_OPS}) and may carry a "
            f"`pentest` attestation from the fuzz lane: {', '.join(above) or '(none)'}"
        )
        if below:
            print(
                f"api-fuzz coverage: {len(below)} service(s) have an EMPTY unauthenticated "
                f"surface at the lane's v1 scope -- their green proves boot + uniform auth "
                f"enforcement and nothing more, so `pentest` is not attestable for them: "
                f"{', '.join(below)}"
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
