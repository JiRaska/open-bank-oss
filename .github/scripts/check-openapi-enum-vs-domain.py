#!/usr/bin/env python3
"""openapi-enum-domain-drift gate — does a spec enum still match the Kotlin enum behind it?

The axis nothing else watches. `oasdiff` (the api-contract gate, ADR-0048) compares a spec to
its own PREVIOUS version, never to the implementation, so a spec enum that was hand-written once
and never re-checked drifts silently and forever. openbank-kyc-service published
`[IDENTITY, SANCTIONS, PEP, ADVERSE_MEDIA, SOURCE_OF_FUNDS]` against a domain
`CheckType { IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING, ADVERSE_MEDIA }` from the day
the file was written: three names misspelled, one real check unpublished, and one value
(`SOURCE_OF_FUNDS`) that has never existed in the code at all (#5895). Everything was green the
whole time, because nothing compared the two documents.

## How a spec enum is paired with a Kotlin enum

There is no declaration linking them, so pairing is by VALUE OVERLAP, not by name — a spec enum
is rarely named after the class, and matching on names would find almost nothing:

  * collect every `enum:` value-set in the service's openapi.yaml,
  * collect every `enum class X { A, B, C }` value-set in that service's src/main,
  * pair a spec set with a Kotlin set when they share at least MIN_OVERLAP of the smaller set,
  * report a pairing whose sets are NOT equal.

Overlap, not equality, is what makes the check able to fire: an enum that agrees perfectly pairs
and passes, and one that has drifted still overlaps enough to be recognised as the same concept.
An unpaired spec enum is NOT a finding — plenty are free-form vocabularies with no Kotlin type
(sort orders, filter keywords), and reporting those would bury the real ones.

## What this gate CANNOT see (measured 2026-09-01, #5962)

Two blind spots, both structural. Neither produces a finding, so the gate's silence about them
is not evidence — they are recorded here so the next reader does not mistake a green run for a
census.

1. **Pairing is thresholded, so the WORST drift is the most invisible.** The more a spec enum has
   drifted from its Kotlin enum, the less it overlaps, and below MIN_OVERLAP it is not reported
   as drift — it is simply not paired, and an unpaired spec enum is not a finding. Measured by
   re-running this gate with MIN_OVERLAP lowered to 0.10 and changing nothing else: the fleet goes
   from `17 paired enum(s) drift, all 17 baselined; no new drift` to **six additional pairings**,
   none of which any run of this gate has ever mentioned — `openbank-account-service` AccountType
   (spec advertises ESCROW and LOAN, which have never existed, and omits all five GL_*/NOSTRO
   values), `openbank-dispute-service` DisputeType (three invented names against five real ones —
   exactly the #5895 shape this gate was built for), `openbank-campaign-service` StepResolution,
   `openbank-lending-service` CollateralStatus, `openbank-sepa-instant` SctInstStatus and
   `openbank-swift-service` SwiftStatus. Raising the threshold is NOT the fix: the last two of
   those six are MIS-pairings against the shared four-eyes vocabulary, see 2.

2. **(FIXED, #7984) `kotlin_enums()` used to walk only the SERVICE's own src/main**, so a spec
   enum backed by a shared `openbank-libs-*` enum could never pair, however far it drifted — 21
   spec enums were in that state (the seven `[PENDING, APPROVED, REJECTED, EXECUTED]` four-eyes
   status enums backed by libs-domain's `ApprovalStatus` the clearest). The scan now merges
   `libs_enums()` under every service's own enums (service-local wins a name collision), which
   surfaced one REAL drift invisible until then (lending's origination-state list carrying three
   invented values) and three coincidental customer-edge mis-pairings, all baselined with
   reasons. What remains true: **pairing is by value overlap, so a large shared enum with a
   coincidental 2-value overlap can still mis-pair** — name-based pairing (spec schema name ↔
   enum class name) is the better shape and is deliberately NOT done here; it needs the spec
   extractor to keep names, a bigger change than the blind-spot fix.

## Ratchet, not a wall

Existing drift is baselined in BASELINE below with the issue that owns it; a NEW drift fails.
A baseline entry that no longer drifts also fails, so the list cannot outlive its justification
(the KNOWN_UNCOVERED shape used by check-pact-provider-replay.py).

Run `--self-test` to falsify it: it feeds the matcher the cases it MUST flag and the ones it
MUST NOT, so the gate is not merely unfalsified-green.

Usage:
    check-openapi-enum-vs-domain.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

# Share this fraction of the LARGER of the two sets, and at least MIN_SHARED values, to be
# considered the same vocabulary. Two calibration points, both pinned by the self-test:
#   * 0.4 rather than 0.5, because the #5895 drift the gate exists for shares exactly 2 of 5
#     values (IDENTITY, ADVERSE_MEDIA) — at 0.5 the gate is green about its own motivating
#     defect. Raising it back turns that case red rather than turning the gate quietly blind.
#   * the LARGER set as denominator rather than the smaller, because the smaller one lets a
#     long unrelated vocabulary that happens to contain two familiar names (2 of 8) pair with a
#     5-value domain enum and be reported as drift.
MIN_OVERLAP = 0.4
MIN_SHARED = 2

# Spec-vs-domain drift that exists today, each with the issue that owns it.
# Format: "<service>:<sorted spec values>" -> reason
BASELINE: dict[str, str] = {
    "openbank-account-service:APPROVED,CANCELLED,PENDING,REJECTED":
        "#5962 — WithdrawalProposalStatus: undeclared EXPIRED",
    # NOT drift — a DELIBERATE SUBSET, kept baselined with the reason corrected (#5962). The
    # values are the `channel` of the app-interaction attribution response
    # (GET /api/v1/campaigns/interactions/{interactionRef}/attribution), which resolves ONLY an
    # attributable app placement. `PanacheSendLogRepository.attributionForAppInteraction` queries
    #     "id = ?1 and partyId = ?2 and channel in (?3, ?4) and outcome = ?5"
    # with Channel.PUSH and Channel.BANNER, so EMAIL is unreturnable by construction and
    # publishing it would advertise a value the endpoint cannot produce. Independent witness, not
    # the same code re-read: the two DB CHECK constraints disagree ON PURPOSE — V11 constrains the
    # send log to ('EMAIL','PUSH','BANNER') while V12's engagement projection constrains
    # `channel` to ('PUSH','BANNER'), the narrower set this enum matches exactly. The gate pairs a
    # placement-scoped enum with the full Channel enum and cannot see the restriction.
    "openbank-campaign-service:BANNER,PUSH":
        "#5962 — attribution response `channel`: NOT drift. A deliberate subset of Channel; the "
        "attribution query filters `channel in (PUSH, BANNER)`, so EMAIL is unreturnable.",
    "openbank-campaign-service:DRY_RUN,SENT,SUPPRESSED_CAP,SUPPRESSED_CONSENT,SUPPRESSED_QUIET_HOURS":
        "#5962 — SendOutcome: undeclared CONVERTED/FAILED/SKIPPED_CONDITION/SUPPRESSED_LIST",
    "openbank-copilot-service:CARD_FREEZE,DISPUTE,PAYMENT":
        "#5962 — ActionKind: undeclared FX_CONVERSION",
    # MIS-PAIRINGS, surfaced when the scan began including openbank-libs-* (#7984): three
    # customer-edge spec enums clear the threshold against shared libs enums on 2-3 coincidental
    # values. FAILED/PENDING (a screening verdict vs OutboxStatus), APPROVED/REJECTED (a task
    # lifecycle vs ApprovalStatus) and PENDING/SENT/FAILED (a payment status vs OutboxStatus) are
    # overlaps of vocabulary, not identity — the same shape as the pid-service entries below.
    "openbank-customer-edge:FAILED,MANUAL_REVIEW,PASSED,PENDING":
        "#7984 — mis-pairing: screening verdict shares FAILED/PENDING with libs OutboxStatus.",
    "openbank-customer-edge:APPROVED,EXPIRED,IN_PROGRESS,NOT_STARTED,REJECTED":
        "#7984 — mis-pairing: task lifecycle shares APPROVED/REJECTED with libs ApprovalStatus.",
    "openbank-customer-edge:ACKNOWLEDGED,COMPLETED,FAILED,PENDING,REJECTED,SENT,VALIDATED":
        "#7984 — mis-pairing: payment status shares FAILED/PENDING/SENT with libs OutboxStatus.",
    # NOT drift — a DELIBERATE SUBSET, kept baselined with the reason corrected (#5962). The
    # values are `RecordDecisionRequest.decision`, and a signer decides SIGNED or DECLINED;
    # PENDING is the state a signer starts in, never one they can submit.
    # `SignatureCeremony.recordDecision` enforces exactly that:
    #     require(decision == SignerStatus.SIGNED || decision == SignerStatus.DECLINED)
    # so publishing PENDING would advertise a value the domain rejects by construction. The
    # gate pairs a request enum with the full lifecycle enum and cannot see the restriction.
    "openbank-document-service:DECLINED,SIGNED":
        "#5962 — RecordDecisionRequest.decision: NOT drift. A deliberate subset of SignerStatus; "
        "recordDecision `require`s SIGNED or DECLINED, so PENDING is unsubmittable by design.",
    # NOT drift — a DELIBERATE SUBSET, kept baselined with the reason corrected (#5962). The
    # values are the `{to}` path parameter of POST /accounting-days/{businessDate}/transitions/{to},
    # and OPEN is not a reachable transition TARGET: a day is created in OPEN by a different
    # endpoint, and `AccountingDay.canTransitionTo` is
    #     next.ordinal == ordinal + 1
    # which OPEN (ordinal 0) can never satisfy — there is deliberately no reopen (ADR-0207 D2/D3;
    # a day corrected after cutoff is corrected forward). Publishing OPEN here would advertise a
    # transition that always answers 409, so the reason as first written asked for a regression.
    # The response schemas that DO carry a whole-lifecycle status already publish all four.
    "openbank-ledger-service:CUTOFF,LOCKED,TIED_OUT":
        "#5962 — transitionAccountingDay `{to}`: NOT drift. A deliberate subset of "
        "AccountingDayStatus; OPEN is unreachable as a transition target (no reopen, ADR-0207).",
    # REMOVED 2026-09-03 (#7984): `openbank-lending-service:APPROVED,EXECUTED,PENDING,REJECTED` —
    # with the libs scan merged in, that quartet EXACTLY matches libs-domain's ApprovalStatus
    # (the ADR-0155 four-eyes vocabulary it was always meant to publish), so it no longer drifts.
    # The stale-entry check below enforces the removal.
    "openbank-lending-service:APPROVED,EXECUTED,PROPOSED,REJECTED":
        "Compliance pack ProposalState, not CollateralStatus; value-overlap pairing is ambiguous.",
    # Surfaced by the libs scan (#7984): the spec's origination-state list is exactly
    # libs-domain's OriginationState PLUS three invented values (APPROVED/PROPOSED/REJECTED) —
    # the #5895 shape, invisible until the shared enum could pair. Lending is money-path, so the
    # spec correction (dropping the three phantom values) goes through its own reviewed change.
    "openbank-lending-service:APPROVED,ASSESSMENT,AWAITING_SIGNATURE,DECISION_PENDING,DECLINED,DISBURSED,DOCS_REQUIRED,DRAFT,EXPIRED,FOUR_EYES,KYC_PENDING,OFFERED,PROPOSED,READY_TO_DISBURSE,REFLECTION_PERIOD,REJECTED,SIGNED,SUBMITTED,WITHDRAWN":
        "#7984 — OriginationState: spec advertises APPROVED/PROPOSED/REJECTED, which have never "
        "existed in the code; real drift, needs a reviewed lending spec fix (money-path).",
    # Also deliberate: the enum is right to flag (INDIVIDUAL has never existed; the DB CHECK is
    # ('NATURAL_PERSON','LEGAL_ENTITY','SOLE_TRADER')), but it sits inside `CreatePartyRequest`,
    # whose declared properties — legalName, tradingName, taxId, dateOfBirth, nationality —
    # match none of the Kotlin DTO's (givenName, familyName, birthdate, nationalities,
    # verificationSource, bankIdSub, birthNumberRaw, initialRole, onboardingChannel). Same
    # reason as above: fix the schema, then the enum.
    "openbank-pid-service:INDIVIDUAL,LEGAL_ENTITY,SOLE_TRADER":
        "#5962 — CreatePartyRequest.partyType: spec-only INDIVIDUAL, inside a request schema "
        "whose properties do not match the DTO at all; needs a schema fix first.",
    # This one is a MIS-PAIRING, kept baselined deliberately. The values are
    # `UpdateKycRequest.kycStatus`, and pid's `UpdateKycRequest` has no `kycStatus` property at
    # all — it is (kycLevel: KycLevel, amlRiskScore: AmlRiskScore, pepFlag, sanctionsFlag). With
    # no real counterpart to pair with, the matcher settled on the openid4vp
    # `PresentationExchangeStore.Status { PENDING, COMPLETED, EXPIRED }` on the strength of two
    # coincidental values. The defect is a whole fictional request schema, not an enum drift, so
    # reconciling the enum alone would polish a document that still describes nothing.
    "openbank-pid-service:EXPIRED,PENDING,REJECTED,VERIFIED":
        "#5962 — UpdateKycRequest.kycStatus: the property does not exist; needs a schema fix, "
        "not an enum fix. The `Status` pairing is coincidental.",
    "openbank-statement-service:RECONCILIATION,UNKNOWN,UPSTREAM":
        "#5962 — CloseFailureReason: undeclared NOT_VIABLE",
}

SPEC_ENUM_INLINE = re.compile(r"enum:\s*\[([^\]]*)\]")
SPEC_ENUM_BLOCK = re.compile(r"enum:[ \t]*\n((?:[ \t]*-[ \t]*\S+[ \t]*\n)+)")
KOTLIN_ENUM = re.compile(r"enum\s+class\s+(\w+)\s*(?::[^{]*)?\{([^}]*)\}")
BLOCK_ITEM = re.compile(r"^[ \t]*-[ \t]*[\"\']?([A-Za-z0-9_]+)[\"\']?[ \t]*$", re.M)
# A constant is the leading identifier of a member; the rest of a member may be a constructor
# call (`ACTIVE("active")`) or an override block, and must not be mined for tokens. A member may
# also carry its own annotation (`@Deprecated("...") LEGACY,`) before the identifier — skipped
# here, not matched as part of it, run AFTER string literals are blanked so an annotation's
# `(...)` argument list can't itself hide a stray `)` that would break the skip.
MEMBER = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*([A-Z][A-Z0-9_]*)\s*(?:\(|$)")
LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
VALUE = re.compile(r"[A-Z][A-Z0-9_]*")


def _spec_values(raw: str) -> frozenset[str]:
    """Values of an inline YAML flow sequence: `enum: [A, B, C]`, quotes optional."""
    return frozenset(
        v.strip().strip("\"'")
        for v in raw.split(",")
        if VALUE.fullmatch(v.strip().strip("\"'"))
    )


def _kotlin_values(body: str) -> frozenset[str]:
    """Constants of a Kotlin enum body.

    Comments are stripped FIRST. Without that the extractor mines ordinary KDoc prose for
    capitalised tokens and invents constants — a first cut of this gate reported
    `spec omits ['A', 'ADR', 'D1', 'NOT', 'OUTCOME']` across a dozen services, all of it words
    out of doc comments. A drift gate whose findings are mostly noise gets baselined wholesale
    and then protects nothing, so this is load-bearing, not tidiness.
    Only the part before a `;` is constants; anything after it is ordinary class body.

    String literals are blanked before that `;` split, too — a `@Deprecated("...; ...")`
    message containing a semicolon otherwise truncates the constant list right there, silently
    dropping every member after it. That is exactly how this gate went blind to
    `SettlementStatus.LEDGER_REVERSED` (issue #6208): the deprecation message read "Never
    produced; the stub...", the real `;` inside it looked like the constants/methods boundary,
    and the enum's actual last member never got extracted — reported as "the code does not
    have" a value the code plainly declares.
    """
    body = STRING_LITERAL.sub('""', body)
    body = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", body))
    constants = body.split(";", 1)[0]
    out = set()
    for member in constants.split(","):
        m = MEMBER.match(member)
        if m:
            out.add(m.group(1))
    return frozenset(out)


def spec_enums(text: str) -> set[frozenset[str]]:
    out: set[frozenset[str]] = set()
    for m in SPEC_ENUM_INLINE.finditer(text):
        vals = _spec_values(m.group(1))
        if len(vals) >= 2:
            out.add(vals)
    for m in SPEC_ENUM_BLOCK.finditer(text):
        vals = frozenset(v for v in BLOCK_ITEM.findall(m.group(1)) if VALUE.fullmatch(v))
        if len(vals) >= 2:
            out.add(vals)
    return out


def kotlin_enums(root: Path) -> dict[str, frozenset[str]]:
    out: dict[str, frozenset[str]] = {}
    for kt in sorted(root.rglob("*.kt")):
        for m in KOTLIN_ENUM.finditer(kt.read_text(encoding="utf-8", errors="replace")):
            vals = _kotlin_values(m.group(2))
            if len(vals) >= 2:
                out[m.group(1)] = vals
    return out


def best_match(
    spec: frozenset[str], domain: dict[str, frozenset[str]]
) -> tuple[str, frozenset[str]] | None:
    """The Kotlin enum sharing the most values with `spec`, if it clears MIN_OVERLAP."""
    best: tuple[str, frozenset[str]] | None = None
    best_n = 0
    best_size = 0
    for name, vals in domain.items():
        # An exact vocabulary is authoritative. Without this fast path, declaration order can
        # make a shorter enum (for example PocketStatus) tie with a longer superset
        # (AccountStatus) and be reported as drift even though its real domain enum matches.
        if vals == spec:
            return name, vals
        n = len(spec & vals)
        # Tie-break on the CLOSER size, not insertion order (#7984): with the libs scan merged
        # in, a spec subset like {DECLINED, SIGNED} overlaps its real enum (SignerStatus, 2/3)
        # exactly as much as a large unrelated one (OriginationState, 2/16). Taking the first
        # in iteration order let the 16-value enum win the tie and then fail the threshold,
        # un-pairing a deliberate-subset baseline entry. The nearer-sized enum is the better
        # candidate for "the same vocabulary".
        size = max(len(spec), len(vals))
        if (n, -size) > (best_n, -best_size):
            best, best_n, best_size = (name, vals), n, size
    if best is None or best_n < MIN_SHARED or best_n < MIN_OVERLAP * max(len(spec), len(best[1])):
        return None
    return best


def libs_enums(repo: Path) -> dict[str, frozenset[str]]:
    """Enums declared in the shared `openbank-libs-*` modules.

    #7984: `kotlin_enums()` used to walk only the service's OWN src/main, so a spec enum backed
    by a shared libs enum (e.g. libs-domain's `ApprovalStatus`, behind seven services' four-eyes
    status enums) could never pair, however far it drifted — one value added to a shared enum
    silently drifted every consuming spec at once with no signal. The domain side of the
    comparison is shared, so the scan root must be too.
    """
    out: dict[str, frozenset[str]] = {}
    for libs_src in sorted(repo.glob("openbank-libs-*/src/main/kotlin")):
        out.update(kotlin_enums(libs_src))
    return out


def scan(repo: Path) -> tuple[list[tuple[str, str, frozenset[str], str, frozenset[str]]], int]:
    """Findings, and the number of spec-enum/Kotlin-enum PAIRINGS examined.

    The subject count is pairings, not specs: a glob that still finds 50 specs while the Kotlin
    source root has moved would pair nothing and report every one of them clean.
    """
    findings = []
    pairings = 0
    libs = libs_enums(repo)
    for spec in sorted(repo.glob("openbank-*/src/main/resources/openapi.yaml")):
        service = spec.parts[len(repo.parts)]
        src = repo / service / "src" / "main" / "kotlin"
        if not src.is_dir():
            continue
        # Service-local enums WIN on a name collision with libs: the service's own declaration
        # is what its handlers actually reference; the libs entry under the same name is what
        # other services see.
        domain = {**libs, **kotlin_enums(src)}
        if not domain:
            continue
        for values in spec_enums(spec.read_text(encoding="utf-8", errors="replace")):
            match = best_match(values, domain)
            if match is None:
                continue
            pairings += 1
            if match[1] == values:
                continue
            findings.append((service, str(spec.relative_to(repo)), values, match[0], match[1]))
    return findings, pairings


def key(service: str, values: frozenset[str]) -> str:
    return f"{service}:{','.join(sorted(values))}"


def self_test() -> int:
    """Feed the matcher what it MUST flag and what it MUST NOT. A gate that has only ever
    passed is unfalsified — every case below is the negative one for some part of the logic."""
    domain = {
        "CheckType": frozenset({"IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING", "ADVERSE_MEDIA"}),
        "CaseStatus": frozenset({"OPEN", "UNDER_REVIEW", "APPROVED", "REJECTED"}),
        # Inserted BEFORE LifecycleStatus so the tie-break case below is lost if insertion
        # order decides: a 16-value enum sharing exactly two values with it (the #7984 shape —
        # a libs enum tying a service-local one on overlap count).
        "HugeUnrelatedStatus": frozenset({"ACTIVE", "DORMANT"} | {f"S{i}" for i in range(14)}),
        "LifecycleStatus": frozenset({"ACTIVE", "DORMANT", "FROZEN", "CLOSED"}),
        "PocketStatus": frozenset({"ACTIVE", "FROZEN", "CLOSED"}),
    }
    cases: list[tuple[str, frozenset[str], bool]] = [
        # (name, spec values, must be reported as drift)
        ("the real #5895 drift is flagged",
         frozenset({"IDENTITY", "SANCTIONS", "PEP", "ADVERSE_MEDIA", "SOURCE_OF_FUNDS"}), True),
        ("an exactly-matching enum is NOT flagged", domain["CheckType"], False),
        ("a single phantom added value is flagged",
         domain["CheckType"] | {"SOURCE_OF_FUNDS"}, True),
        ("a single omitted value is flagged", domain["CheckType"] - {"ADDRESS"}, True),
        ("an unrelated free-form vocabulary is NOT flagged (no pairing at all)",
         frozenset({"ASC", "DESC"}), False),
        ("a vocabulary overlapping by ONE value out of many does not pair",
         frozenset({"IDENTITY", "AAA", "BBB", "CCC", "DDD", "EEE"}), False),
        ("two shared values out of eight is coincidence, not the same vocabulary",
         frozenset({"IDENTITY", "ADDRESS", "AAA", "BBB", "CCC", "DDD", "EEE", "FFF"}), False),
        ("a different domain enum in the same service pairs with ITS OWN match",
         domain["CaseStatus"], False),
        ("an exact enum wins over an earlier overlapping superset",
         domain["PocketStatus"], False),
        ("drift in the second enum is still found",
         frozenset({"OPEN", "UNDER_REVIEW", "APPROVED", "DECLINED"}), True),
        # #7984: a subset tying two enums on overlap COUNT must pair the nearer-sized one —
        # {ACTIVE, DORMANT} ties LifecycleStatus (2/4) and HugeUnrelatedStatus (2/16), and the
        # huge one is inserted FIRST, so insertion order alone would pair it and then fail the
        # threshold, un-pairing a real subset. Flagged=True because the subset genuinely drifts
        # from LifecycleStatus (omits FROZEN/CLOSED); the point is it pairs AT ALL.
        ("a subset tying on count pairs the nearer-sized enum, not the first one",
         frozenset({"ACTIVE", "DORMANT"}), True),
    ]
    failures = 0
    for name, values, must_flag in cases:
        match = best_match(values, domain)
        flagged = match is not None and match[1] != values
        if flagged != must_flag:
            print(f"  FAIL  {name}: expected flagged={must_flag}, got {flagged} (match={match})")
            failures += 1
        else:
            print(f"  ok    {name}")

    # The extractors, against the shapes that made a first cut of this gate report noise.
    parser_cases: list[tuple[str, frozenset[str], frozenset[str]]] = [
        ("a plain Kotlin enum body", _kotlin_values("IDENTITY, ADDRESS, PEP_SCREENING"),
         frozenset({"IDENTITY", "ADDRESS", "PEP_SCREENING"})),
        ("KDoc prose in the body is not a constant",
         _kotlin_values("/** A hit MUST NOT auto-reject (ADR-0116 D1). */\n    PASSED,\n    FAILED"),
         frozenset({"PASSED", "FAILED"})),
        ("a line comment in the body is not a constant",
         _kotlin_values("ACTIVE, // NOT the same as OPEN\n    CLOSED"),
         frozenset({"ACTIVE", "CLOSED"})),
        ("constructor arguments are not constants",
         _kotlin_values('SEPA("sepa"), SWIFT("swift")'), frozenset({"SEPA", "SWIFT"})),
        ("members after the `;` are not constants",
         _kotlin_values("OPEN, CLOSED;\n    fun isOPEN() = this == OPEN"),
         frozenset({"OPEN", "CLOSED"})),
        ("a `;` inside a string literal (e.g. a @Deprecated message) does not truncate the "
         "constant list — issue #6208",
         _kotlin_values('OPEN,\n    @Deprecated("Never produced; see OPEN instead")\n    CLOSED,'),
         frozenset({"OPEN", "CLOSED"})),
        ("lowercase tokens are not constants", _kotlin_values("identity, address"), frozenset()),
        ("an inline spec enum", _spec_values("IDENTITY, ADDRESS"),
         frozenset({"IDENTITY", "ADDRESS"})),
        ("a quoted inline spec enum", _spec_values("'OPEN', \"CLOSED\""),
         frozenset({"OPEN", "CLOSED"})),
    ]
    for name, got, want in parser_cases:
        if got != want:
            print(f"  FAIL  {name}: got {sorted(got)}, want {sorted(want)}")
            failures += 1
        else:
            print(f"  ok    {name}")

    block = spec_enums("        enum:\n          - OPEN\n          - CLOSED\n")
    if block != {frozenset({"OPEN", "CLOSED"})}:
        print(f"  FAIL  a block-style spec enum: {block}")
        failures += 1
    else:
        print("  ok    a block-style spec enum")

    print("self-test: " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--repo", default=".")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    repo = Path(args.repo).resolve()
    level = "error" if args.enforce else "warning"
    findings, pairings = scan(repo)
    gatelib.subjects(pairings, "spec-enum/domain-enum pairing(s)")
    seen = set()
    new = 0

    for service, rel, values, kt_name, kt_values in findings:
        k = key(service, values)
        seen.add(k)
        if k in BASELINE:
            continue
        new += 1
        missing = sorted(kt_values - values)
        phantom = sorted(values - kt_values)
        print(
            f"::{level} file={rel}::openapi enum drifts from the domain enum "
            f"`{kt_name}` it serves: spec omits {missing or 'nothing'}, "
            f"spec advertises values the code does not have: {phantom or 'none'}. "
            f"Reconcile the two, or (if the spec is right) rename the Kotlin enum."
        )

    stale = sorted(set(BASELINE) - seen)
    for k in stale:
        print(
            f"::{level}::openapi-enum drift baseline entry `{k}` no longer drifts — "
            f"drop it from BASELINE in {Path(__file__).name}."
        )

    if not new and not stale:
        print(
            f"openapi-enum-domain-drift: {len(findings)} paired enum(s) drift, "
            f"all {len(BASELINE)} baselined; no new drift."
        )
        return 0
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
