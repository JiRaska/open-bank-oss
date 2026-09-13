#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A threat model is a set of CLAIMS about running code. Nothing checked whether any is true.

WHY THIS EXISTS
---------------
Proven live, not hypothetical (#6037 / PR #6048, `openbank-settlement-service`):

  * Risk **R2** was "Compensation claimed to have run when it did not", and its mitigation
    credited the status transitions written by the compensation activities themselves. Those
    activities logged a stub line and moved no money. The document asserted the risk was
    mitigated by the very code that embodied it — a claim structurally unable to be false.
  * Risk **T1** named an idempotency key built from identifiers that appear in no source file
    in this repo, and which would change on every workflow run even if they did.

Two earlier instances of the same class are recorded in the documents themselves: settlement S2
credited "service mesh mTLS" for the life of the service while no mesh has ever been deployed
here (#3921), and `openbank-vop-service` documented lookup controls whose two hops dialled ports
that do not exist (#3966).

`check-threat-models.py` proves a model EXISTS. `check-threat-model-diff.py` proves it is
TOUCHED when a trust boundary moves. Neither reads a single claim, so a model can be present,
current, freshly edited — and false. This gate reads the claims.

WHAT IT CHECKS (the mechanically detectable subset — deliberately narrow)
------------------------------------------------------------------------
  PHANTOM    a mitigation cites a code symbol (CamelCase type, source path, `data.x.y` policy
             rule, or a lowerCamelCase identifier) that appears in NO tracked backend source.
             The control named does not exist. (settlement S1 cites `OpaActivityInterceptor`;
             T1 cites `workflowRunId` / `activityId`.)
  STUB       a mitigation cites a symbol whose own DECLARATION opens with a stub marker
             (`stub:`, `TODO`, `FIXME`, `not implemented`) — the control exists as a name only.
  SELF-REF   the threat is precisely "the record says it happened / it reported success" and
             the mitigation's only named mechanism IS record-keeping (a status transition, an
             audit entry, an execution history). The mitigation cannot distinguish the good
             case from the threat. (settlement R2.)

WHAT IT DOES NOT CHECK, AND WHY THAT IS SAID OUT LOUD
-----------------------------------------------------
It cannot decide whether a control that exists does what the sentence says. Settlement T1's key
is inoperative for a reason no parser can see: it is not stable across retries. And it is blind
to the largest class by far — a mitigation naming no mechanism at all is unfalsifiable by
construction, so this gate COUNTS those and prints the count, but does not fail on them: failing
would make "delete the sentence" the cheapest fix, which is the wrong incentive. The 2026-08-20
census over the 23 `money_path_services` models found roughly two thirds of all mitigation
claims in that state. Reducing it is editorial work, not a gate.

WIDENED 2026-09-03 — THE GATE WAS GREEN ABOUT DEFECTS INSIDE THE FILES IT READ
------------------------------------------------------------------------------
A sweep found 19 false claims; four of them sat in files this gate opens, and it exited 0.
Three independent causes, each measured before it was changed:

  1. REGION. The two parsers read STRIDE mitigation cells and nothing else. A claim in a table was
     checked; the same claim two lines below it in a sentence was not. All eight of the sweep's
     in-scope findings lived in §3 Authn/Authz prose, §5 residual-risk bullets, a `Rollback:` line
     or a §7 change-log entry. `parse_prose` now reads the whole document.
  2. SUBJECTS. The subject set was `money_path_services`, so 22 of the 45 models in
     `docs/threat-models/` were never opened — and the unread half held four of the findings.
     Subjects are now derived from the DIRECTORY. Money-path membership is reported, not gating.
  3. RESOLUTION. `resolve()` was a substring search over every line, comments included. So
     `BalanceResourceSecurityTest` "existed" because `BalanceResource.kt`'s KDoc repeated the
     document's own false claim, and `SecurityContractTest` "existed" as a suffix of nine
     per-service classes. Resolution is now word-boundary, over non-comment lines only.

WHAT WAS DELIBERATELY LEFT ALONE
--------------------------------
  * REGULATORY APTNESS. Whether a control satisfies PSD2 Art. 5(3) is a legal question, not a tree
    lookup. A gate pretending to check it is worse than one that does not.
  * ADEQUACY. Whether a control that exists does what the sentence says. Unchanged from above.
  * mTLS-ON-A-LINK. 28 of the 45 models credit `mTLS`, and no gitops hop uses TLS at all (243
    plaintext V9.1 entries in `.github/asvs-l3-baseline.txt`; the mesh is unshipped). A rule here
    would fire on 62% of the corpus at once — a census, not a check, and one that would be
    baselined into meaninglessness on contact. The claim is real and belongs to the ASVS gate that
    already tracks the plaintext hops, not to this one.
  * UNCITED PROSE. Still counted, never failed. Widening the REGION cannot change this: a sentence
    naming no mechanism produces no citation, so the 2068 unfalsifiable claims stay a printed
    number. Failing them would make "delete the sentence" the cheapest fix.

SCOPE IS DERIVED, NEVER HAND-KEPT
---------------------------------
Subjects = every `docs/threat-models/*.md`, and the claims
inside are enumerated by THREE independent parsers whose UNION is checked — a table parser keyed
on a Mitigation/Control column header, and a section parser that reads STRIDE-titled sections
with no header knowledge at all. They disagree by design: the fleet's models are not one format
(2026-08-20: 296 claims by the table parser, 315 by the section parser, 390 in the union, and
two services are invisible to the table parser entirely). A single parser here would be a probe,
not a census — this repo has been burned by exactly that (grepping `src/test` for the word
"contract" scored comment lines as contract tests, #2291).

Exclusions live in ALLOWED_UNRESOLVED and must carry a reason. A stale entry fails in BOTH
directions: an exclusion for a claim that no longer exists is as much a defect as a missing one,
because a gate whose scope is a hand-kept list reads as PASSING when the list is short.

Usage:  check-threat-model-claims.py [--enforce] [--report] [--root <dir>]
        check-threat-model-claims.py --self-test
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import subprocess

REPO = pathlib.Path(__file__).resolve().parents[2]

# ---------------------------------------------------------------- exclusions
# key: "<service>|<risk id>|<symbol or SELF-REF>"  ->  reason a reviewer accepted.
# Empty is the honest state: every finding this gate reports today is a real defect being
# fixed in the same PR, not baselined debt.
ALLOWED_UNRESOLVED: dict[str, str] = {
    # Widening the gate on 2026-09-03 (subject set 23 -> 45 models; claim regions STRIDE-only ->
    # whole document; resolution substring -> word-boundary on non-comment lines) surfaced 46
    # citations that name something this tree does not contain. Every one was checked by hand
    # against the tree before it was written down, and each carries the reason a reviewer accepted.
    #
    # Nothing here is silenced by SERVICE or by FILE — always by (service, section, symbol), so a
    # model stays fully in scope for every other claim it makes.
    #
    # THE LIST FAILS IN BOTH DIRECTIONS. An entry whose claim no longer exists matches nothing, and
    # the run goes red until the line is deleted. That is not decoration: the two pre-existing
    # settlement entries were keyed `T1`, and widening the parser moved those same claims into the
    # sections they are actually written in — the old keys went stale immediately and had to be
    # rewritten, which is the check doing its job on its own author.
    #
    # Eight entries name an OPEN PR. Those are the sweep's own corrections; they retire themselves
    # the day each PR lands, and re-editing the same rows here would conflict with a live branch.
    'onboarding-service|5. AML / sanctions override — special controls|kyc.check.override':
        'an ADR-0068 planned control; absent from `rules.yaml` and from every resource',
    'onboarding-service|5. AML / sanctions override — special controls|confirmedBy':
        'an ADR-0068 planned field; present in no entity, DTO or migration',
    'openbank-account-service|6. Change log|openAccountIdempotencyKey':
        'no such identifier anywhere in the tree',
    'openbank-clearing-service|6. Change log|ClearingResourceSecurityTest':
        'the change-log entry records the correction itself — it names the wrong old class in '
        'order to say it was wrong. §3 no longer cites it (#8409)',
    'openbank-consent-service|6. Change log|ConsentEventPublisher':
        'named in ADR-0126 and the ktlint baseline only; no such class in Kotlin',
    'openbank-consent-service|6. Change log|KafkaConsentEventPublisher':
        'named in ADR-0126 and the ktlint baseline only; no such class in Kotlin',
    'openbank-domestic-payment|6. Change log|applyFraudGate':
        'retired by #4221 layer 3; `DomesticPaymentService.kt:125` says so in a comment',
    'openbank-domestic-payment|6. Change log|attemptSettlement':
        'retired by #4221 layer 3; `DomesticPaymentService.kt:125` says so in a comment',
    'openbank-domestic-payment|6. Change log|fraudEnforcementEnabled':
        'retired by #4221 layer 3; `FraudEnforcementFlagRetiredTest` locks its absence',
    'openbank-domestic-payment|6. Change log|openbank.domestic.fraud.enforcement-enabled':
        'removed by #4221; `application.yaml:174` records the removal in a comment',
    'openbank-fraud-service|6. Change log|fraudServiceUrl':
        'occurs only inside a comment in `fraud_rest_ext.rego`; not a policy input',
    'openbank-ledger-service|8. Change log|ledgerServiceUrl':
        'occurs only inside a comment in `ledger_rest_ext.rego`; not a policy input',
    'openbank-lending-service|3. Controls in place (this slice)|lending.origination.worker.enabled':
        'renamed to the `openbank.` convention; `rules.yaml:1544` records the rename',
    'openbank-mcp-service|0. Phase posture — read this before anything below|HTTPRoute':
        'no HTTPRoute manifest exists in this tree; ingress is expressed otherwise',
    'openbank-mcp-service|8. Change log|StubProposalPort':
        'open PR #8419 — replaced by `UnwiredProposalPort`; only past-tense KDoc remains',
    'openbank-sanctions-service|T3|deactivateByListType':
        'previous repository contract, replaced by `upsertAll`; only the port KDoc names it',
    'openbank-sanctions-service|3. STRIDE analysis|deactivateByListType':
        'previous repository contract, replaced by `upsertAll`; only the port KDoc names it',
    'openbank-security-scanner|1. Scope & purpose|openbank.security.scan.event':
        'topic named in CHANGELOGs only; no producer, consumer, contract or KafkaTopic CR',
    'openbank-sepa-instant|6. Change log|SctInstOutboxBacklogGaugeTest':
        'test for the outbox dropped by `V4__drop_sct_inst_outbox.sql` (#5126); no such class',
    'openbank-sepa-instant|6. Change log|KafkaSctInstOutboxEventPublisher':
        'dropped by `V4__drop_sct_inst_outbox.sql` (#5126); only the migration comment names it',
    'openbank-sepa-instant|6. Change log|SctInstOutboxDispatcher':
        'dropped by `V4__drop_sct_inst_outbox.sql` (#5126); only the migration comment names it',
    'openbank-sepa-instant|6. Change log|SctInstOutboxPort':
        'dropped by `V4__drop_sct_inst_outbox.sql` (#5126); only the migration comment names it',
    'openbank-sepa-payment|**T**ampering|XMLInputFactory':
        'names the StAX API; `Pacs004Reader` IS XXE-hardened, via `DocumentBuilderFactory` (`disallow-doctype-decl`, external entities off). Control real, API name wrong',
    'openbank-sepa-payment|5a. Return path (pacs.004) — STRIDE supplement|XMLInputFactory':
        'names the StAX API; `Pacs004Reader` IS XXE-hardened, via `DocumentBuilderFactory` (`disallow-doctype-decl`, external entities off). Control real, API name wrong',
    'openbank-sepa-payment|6. Change log|settleProcessingPayment':
        'no such function in the tree',
    'openbank-settlement-service|T1|workflowRunId':
        'settlement idempotency-key defect #6037, reported from the T1 table row (the original entry)',
    'openbank-settlement-service|T1|activityId':
        'settlement idempotency-key defect #6037, reported from the T1 table row (the original entry)',
    'openbank-settlement-service|T — Tampering|activityId':
        'settlement idempotency-key defect #6037; the widened parsers report the same claim from the section and the change log as well as the T1 row',
    'openbank-settlement-service|T — Tampering|workflowRunId':
        'settlement idempotency-key defect #6037; the widened parsers report the same claim from the section and the change log as well as the T1 row',
    'openbank-settlement-service|Residual risks|settlement_rest_ext.rego':
        "file does not exist; settlement's ext policy is embedded in its bundle generator",
    'openbank-settlement-service|Residual risks|OpaActivityInterceptor':
        '#6055 — a control that was never built; deliberately left failing by the gate that found it',
    'openbank-settlement-service|Residual risks|settlement_activity.rego':
        "file does not exist; settlement's ext policy is embedded in its bundle generator",
    'openbank-settlement-service|Residual risks|legacySettle':
        'settlement #6037 family; no such function in the tree',
    'openbank-settlement-service|Change log|activityId':
        'settlement idempotency-key defect #6037; the widened parsers report the same claim from the section and the change log as well as the T1 row',
    'openbank-settlement-service|Change log|workflowRunId':
        'settlement idempotency-key defect #6037; the widened parsers report the same claim from the section and the change log as well as the T1 row',
    'openbank-swift-service|6. Change log|swift_rest_ext.rego':
        "file does not exist; swift's ext policy is embedded in its bundle generator",
    'openbank-transaction-service|5. Residual risks / assumptions|PaymentSagaOrchestrator':
        'retired for Temporal (ADR-0120 Phase 5); survives only in KDoc that says it was removed',
    'openbank-transaction-service|6. Change log|PaymentSagaOrchestrator':
        'retired for Temporal (ADR-0120 Phase 5); survives only in KDoc that says it was removed',
    'openbank-transaction-service|6. Change log|TransactionResourceMergeSweepTest':
        'no class of that name; the merge-sweep tests are named otherwise',
    'qrlesspay|8. Rollout gates (what must be true before code ships)|ProximityBeacon.android.kt':
        'a file in the separate `openbank-app` repository, not in this tree',
    'rum-ingest-gateway|**I1**|beforeSend':
        'a RUM SDK callback living in admin-ui / the mobile app, both outside the backend corpus by design',
    'rum-ingest-gateway|3. STRIDE Analysis|beforeSend':
        'a RUM SDK callback living in admin-ui / the mobile app, both outside the backend corpus by design',
}

# ---------------------------------------------------------------- parsers
DASH = re.compile(r"^:?-{2,}:?$")
STRIDE_H = re.compile(
    r"^#{1,4}\s*.*(STRIDE|Spoofing|Tampering|Repudiation|Information disclosure|"
    r"Denial of service|Elevation of privilege|Money-path specific)",
    re.IGNORECASE,
)
HEADERISH = ("mitigation", "control", "threat", "stride", "id", "#", "vector", "risk", "scenario")


def _cells(line: str) -> list[str] | None:
    s = line.strip()
    if not s.startswith("|"):
        return None
    c = [x.strip() for x in s.strip("|").split("|")]
    if c and all(DASH.fullmatch(x) for x in c if x):
        return None  # separator row
    return c


def parse_table(text: str) -> list[tuple[str, str, str]]:
    """METHOD 1 — locate the Mitigation/Control COLUMN from a header row."""
    rows: list[tuple[str, str, str]] = []
    mit = thr = None
    for line in text.splitlines():
        c = _cells(line)
        if c is None:
            if not line.strip().startswith("|"):
                mit = thr = None
            continue
        low = [x.lower() for x in c]
        if any(x.startswith(("mitigation", "control")) for x in low):
            mit = next(i for i, x in enumerate(low) if x.startswith(("mitigation", "control")))
            thr = next((i for i, x in enumerate(low) if x.startswith(("threat", "risk", "scenario"))), None)
            continue
        if mit is not None and len(c) > mit and c[mit]:
            rows.append((c[0], c[thr] if thr is not None and len(c) > thr else "", c[mit]))
    return rows


def parse_sections(text: str) -> list[tuple[str, str, str]]:
    """METHOD 2 — inside STRIDE-titled sections, every table row and bullet is a claim unit.

    Knows nothing about column headers, so it sees the models the table parser cannot (billing
    and vop carry no Mitigation column at all) and misses ones it can.
    """
    rows: list[tuple[str, str, str]] = []
    inside = False
    for line in text.splitlines():
        if line.startswith("#"):
            inside = bool(STRIDE_H.match(line))
            continue
        if not inside:
            continue
        c = _cells(line)
        if c is not None:
            low = [x.lower() for x in c]
            if any(x.startswith(HEADERISH) for x in low) and all(len(x) < 30 for x in c):
                continue
            if len(c) >= 2:
                rows.append((c[0], c[1] if len(c) > 2 else "", c[-1]))
            continue
        s = line.strip()
        if re.match(r"^(?:[-*]|\d+\.)\s+\S", s):
            rows.append(("bullet", s, s))
    return rows


HEADING = re.compile(r"^(#{1,4})\s+(.*?)\s*$")


def parse_prose(text: str) -> list[tuple[str, str, str]]:
    """METHOD 3 — every bullet, paragraph and table row in the WHOLE document.

    The two parsers above read STRIDE mitigation cells and nothing else, so a claim written as a
    sentence two lines below the table it belongs to was unread. That is not a hypothetical gap;
    every one of the 2026-09-03 sweep's findings sat in a region neither parser reaches:

      * `§3 Authn / Authz` prose — balance and clearing crediting regression guards
        (`BalanceResourceSecurityTest`, `ClearingResourceSecurityTest`) that do not exist.
      * `§5 Residual risks` — security-scanner crediting a fleet-wide `SecurityContractTest`;
        tpp-registry describing a role cache, and the knob that tunes it, that were never built.
      * `§7 Change log` — fx crediting `FxConversionService`, mcp asserting `StubProposalPort` was
        "left untouched" when it had been replaced, sepa-payment documenting the rollback flag
        `openbank.sepa.returns.enabled` as "off by default" for an endpoint that reads no config.
      * `§5a Rollback:` lines and DFD notes — document-service naming a Kafka topic
        (`openbank.billing.billing.event`) that no consumer, producer, contract or CR carries.

    A change-log entry is a claim like any other: it is the thing a later reader relies on when
    deciding whether a path has been revisited. It is checked on exactly the same terms — a
    citation that names something the tree does not contain — and the disclaimer escape hatch
    below is what a correction uses to retire its own finding.

    The unfalsifiable majority is untouched by this. A sentence naming no mechanism produces no
    citation, so widening the REGION cannot convert a counted non-claim into a finding; it can
    only find claims that already name something checkable. That is the property that makes this
    safe to run over prose at all (2026-08-20 census: 273 of ~410 claims name no mechanism).
    """
    rows: list[tuple[str, str, str]] = []
    head = "preamble"
    para: list[str] = []

    def flush() -> None:
        if para:
            joined = " ".join(para).strip()
            if joined:
                rows.append((head, joined, joined))
            para.clear()

    for line in text.splitlines():
        m = HEADING.match(line)
        if m:
            flush()
            head = m.group(2).strip().strip("#").strip() or "untitled"
            continue
        s_ = line.strip()
        if not s_:
            flush()
            continue
        c = _cells(line)
        if c is not None:
            flush()
            joined = " ".join(x for x in c if x).strip()
            if joined:
                rows.append((head, joined, joined))
            continue
        if re.match(r"^(?:[-*+]|\d+\.)\s+\S", s_):
            flush()
        para.append(s_)
    flush()
    return rows


def claims(text: str) -> list[tuple[str, str, str, str]]:
    """UNION of all three parsers, deduplicated on (id, mitigation).

    The fourth element is the PARSER that produced the unit. It exists so the SELF-REF rule can
    stay bound to structured STRIDE rows, where "threat" and "mitigation" are genuinely different
    cells. Over free prose the two are the same sentence, so the rule would be comparing a
    paragraph with itself and would fire on any paragraph that both describes a record-keeping
    threat and mentions a status column — a shape that is ordinary narration, not a defect.
    """
    seen: set[tuple[str, str]] = set()
    out: list[tuple[str, str, str, str]] = []
    for kind, rows in (("table", parse_table(text)),
                       ("section", parse_sections(text)),
                       ("prose", parse_prose(text))):
        for rid, thr, mitig in rows:
            k = (rid, mitig)
            if k in seen:
                continue
            seen.add(k)
            out.append((rid, thr, mitig, kind))
    return out


# ---------------------------------------------------------------- citations
BACKTICK = re.compile(r"`([^`\n]{2,160})`")
# CamelCase type name: at least two humps AND at least one lowercase letter, so a SCREAMING_CASE
# status constant (`REVERSED`) is prose, not a citation.
CAMEL = re.compile(r"^(?=.*[a-z])[A-Z][A-Za-z0-9]*(?:[A-Z][A-Za-z0-9]*)+$")
SRCPATH = re.compile(r"^[\w./-]+\.(?:kt|java|py|rego|sql|ts|tsx|sh)$")
POLICYRULE = re.compile(r"^data\.[a-z][\w.]+$")
LOWERCAMEL = re.compile(r"^[a-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+$")
# A dotted lowercase key: a config property, a Kafka topic, a package. Three or more segments,
# so a bare `example.com` or a version string is not one. This shape is what carried three of the
# 2026-09-03 sweep's findings (`openbank.sepa.returns.enabled`, `openbank.billing.billing.event`,
# `openbank.tpp.cache-ttl-seconds`) and the old extractor saw none of them: a dotted key is not
# CamelCase, not a source path, and not `data.`-prefixed, so it fell through every branch.
CONFIGKEY = re.compile(r"^[a-z][a-z0-9]*(?:[.][a-z0-9]+(?:-[a-z0-9]+)*){2,}$")
# ...but a DNS name and a reverse-DNS coordinate have the same shape as a config key and are NOT
# things this tree contains. Measured on the first widened run: `sanctionslistservice.ofac.treas.gov`
# (an upstream OFAC host), `com.microsoft.onnxruntime` (a Maven coordinate) and
# `tech.openbank.app.payment.nearpay` (a package in the separate openbank-app repo) were all
# reported as phantom controls. None is a claim about this repository, and a gate that reports
# three of those for every real finding is one people learn to silence.
NOT_A_CONFIG_KEY = re.compile(
    r"^(?:com|org|io|net|tech|dev|edu|gov)\.|"
    r"\.(?:com|org|net|gov|edu|io|eu|cz|dev|ai|co|uk|int)$"
)

# Words that look like lowerCamelCase but are English or product prose, not identifiers.
PROSE_LOWERCAMEL = {"noData", "closeMatch", "openBank", "javaScript", "iBan"}


def citations(text: str) -> list[str]:
    out: list[str] = []
    for raw in BACKTICK.findall(text):
        for tok in re.split(r"[\s,;/+]+", raw.strip()):
            t = tok.split("(")[0].split("#")[0].split("::")[0].strip(" .`'\"")
            if not t or t in PROSE_LOWERCAMEL:
                continue
            # A QUALIFIED reference — `FxConversionService.scoreFraudShadow()` — matched no shape
            # at all and was dropped silently: it is not CamelCase (it has a dot), not a source
            # path, not `data.`-prefixed, and not a lowercase config key. That is how fx-service
            # credited its fail-open shadow-scoring wrapper to a class present in no Kotlin source
            # for two and a half months. Take the owning TYPE, which is the checkable half; the
            # member after the dot is not, because a method can be declared on a supertype.
            if "." in t and not SRCPATH.match(t):
                head = t.split(".", 1)[0]
                if CAMEL.match(head):
                    out.append(head)
                    continue
            config_key = CONFIGKEY.match(t) and not NOT_A_CONFIG_KEY.search(t)
            structural = (CAMEL.match(t) or SRCPATH.match(t) or POLICYRULE.match(t)
                          or config_key)
            # A lowerCamelCase identifier needs a length floor: short ones are ordinary English
            # in backticks far more often than they are symbols.
            identifier = LOWERCAMEL.match(t) and len(t) >= 8
            if structural or identifier:
                out.append(t)
    return sorted(set(out))


# ---------------------------------------------------------------- corpus
DOCISH = (".md", ".txt", ".adoc")
CODEISH = (".kt", ".java", ".py", ".rego", ".sql", ".ts", ".tsx", ".sh", ".yaml", ".yml",
           ".json", ".gradle", ".kts", ".xml", ".properties", ".tf", ".conf")

STUB_WINDOW = 8  # lines of a declaration's body inspected for a stub marker
STUB_MARK = re.compile(r"\bstub\b\s*:|\bTODO\b|\bFIXME\b|not implemented|NotImplemented",
                       re.IGNORECASE)


def is_deployed(rel: str) -> bool:
    """Is this file a DEPLOYED artifact — the thing a runtime mitigation must live in?

    Backend `src/main`, a policy, or an infra manifest. Used to ANCHOR the stub check; it does
    not decide existence.
    """
    if rel.startswith("openbank-admin-ui/"):
        return False
    return ("/src/main/" in rel) or rel.endswith(".rego") or rel.startswith("openbank-infra/")


def is_backend(rel: str) -> bool:
    """Corpus for EXISTENCE. Deployed source plus `src/test`, minus the admin UI.

    Two boundaries, each measured against a real claim on the 2026-08-20 corpus:

      * `src/test` counts. Several mitigations legitimately cite the test that proves them
        (sanctions D2/C5, transaction Tampering). Excluding tests reported three existing test
        classes as phantom controls — a gate that cries wolf on correct documentation is one
        people learn to silence.
      * the admin UI does not. Settlement S1 credits `OpaActivityInterceptor`; its only
        occurrence outside its own threat model is a label rendered on an admin-UI page. A name
        in a UI string is not a control, and a corpus that counts it reports the control as
        present — the exact failure this gate exists to catch.
    """
    if rel.startswith("openbank-admin-ui/"):
        return False
    # A Gradle build file is tracked backend source that no `/src/` path matches, and real
    # mitigations cite it: ledger's §5 names pitest's `targetClasses`, which lives only in
    # `openbank-account-service/build.gradle.kts`. Leaving build files out reported it as a
    # phantom control on the first widened run.
    # `openbank-libs/governance/` is where authz actions, four-eyes actions and gate ids are
    # declared. A mitigation citing one of those names it correctly; without this the gate would
    # report the authoritative rule file's own vocabulary as absent.
    return (is_deployed(rel) or "/src/test/" in rel
            or rel.endswith((".gradle.kts", ".gradle"))
            or rel.startswith("openbank-libs/governance/"))


class Corpus:
    """Every tracked backend file read ONCE.

    Deliberately not `git grep` per citation: that is one subprocess per symbol and the gate
    stops finishing inside its CI budget — which is the failure mode where somebody quietly
    narrows the scope until it is fast, and a narrowed scope reads as PASSING.
    """

    def __init__(self, root: pathlib.Path):
        self.root = root
        r = subprocess.run(["git", "-C", str(root), "ls-files"],
                           capture_output=True, text=True, check=False)
        self.files = r.stdout.split()
        self.names = {pathlib.Path(f).name for f in self.files}
        self.paths = set(self.files)
        self.blobs: dict[str, str] = {}
        self.code: dict[str, str] = {}
        self._memo: dict[str, bool] = {}
        for f in self.files:
            if f.endswith(DOCISH) or not f.endswith(CODEISH) or not is_backend(f):
                continue
            try:
                self.blobs[f] = (root / f).read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
        # Stub detection anchors on DEPLOYED source only: a stubbed test helper is not the
        # defect this gate is about.
        self.main = {f: b for f, b in self.blobs.items() if is_deployed(f) and "/src/main/" in f}
        # A COMMENT-ONLY view is not evidence a thing exists. Measured 2026-09-03: the sole
        # occurrence of `BalanceResourceSecurityTest` anywhere in the tree is the KDoc line
        # `* locked by BalanceResourceSecurityTest.` in `BalanceResource.kt`, and the sole
        # occurrence of `ClearingResourceSecurityTest` is the identical KDoc line in
        # `ClearingResource.kt`. Both classes are absent; both threat models credited them; and
        # the old substring resolve() answered TRUE for both, because the document's own false
        # claim was echoed in a comment next to the code. Prose cannot witness prose.
        self.config_keys: set[str] = set()
        for f, b in self.blobs.items():
            if f.endswith((".yaml", ".yml", ".properties")):
                self.config_keys |= self.yaml_paths(b)
        for f, b in self.blobs.items():
            self.code[f] = "\n".join(
                ln for ln in b.splitlines()
                if not ln.lstrip().startswith(("//", "*", "/*", "#", "<!--", "--"))
            )

    @staticmethod
    def yaml_paths(blob: str) -> set[str]:
        """Flatten a YAML file's mapping keys to dotted paths, by INDENT — no parser.

        A Quarkus property is written nested (`openbank:` / `  sepa:` / `    enabled:`), so the
        dotted form a threat model quotes appears nowhere in the file as a literal string. Without
        this, every cited config key resolved to nothing and the widened gate would have reported
        each of them as a phantom control — the systematic false-positive class that makes a gate
        worth less than nothing. Caught by this gate's own self-test, not by the fleet run.

        Deliberately indent-based rather than `yaml.safe_load`: half these files are Kustomize or
        Helm templates carrying `{{ }}` and `${}` placeholders that no YAML parser will load, and a
        parser that throws on them would silently shrink the corpus back down.
        """
        out: set[str] = set()
        stack: list[tuple[int, str]] = []
        for raw in blob.splitlines():
            m = re.match(r"^(\s*)([A-Za-z_][\w.-]*)\s*:(\s|$)", raw)
            if not m:
                continue
            indent, key = len(m.group(1)), m.group(2)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, key))
            out.add(".".join(k for _, k in stack))
        return out

    @staticmethod
    def _word(sym: str) -> re.Pattern:
        return re.compile(r"(?<![A-Za-z0-9_])" + re.escape(sym) + r"(?![A-Za-z0-9_])")

    def resolve(self, sym: str) -> bool:
        """True iff the symbol occurs as REAL CONTENT in at least one tracked backend file.

        Two boundaries, each of which the pre-2026-09-03 substring form got wrong on a live claim:

          * WORD BOUNDARY, not substring. `SecurityContractTest` — credited by
            `openbank-security-scanner.md` as the fleet-wide invariant that every JAX-RS endpoint
            is annotated — is declared nowhere, but it is a suffix of nine per-service classes
            (`BalanceSecurityContractTest`, ...), so `needle in blob` resolved it and the gate
            agreed that a fleet-wide control existed. It covers 8 of the 61 modules that expose a
            resource.
          * CODE LINES, not comments. See the `self.code` note above: a citation whose only
            occurrence is a KDoc repeating the same claim is not evidence of anything.

        A source PATH is exempt from both: it is matched against the file list, not file content.
        """
        if sym in self._memo:
            return self._memo[sym]
        if SRCPATH.match(sym):
            hit = (sym in self.paths or any(f.endswith("/" + sym) for f in self.files)
                   or pathlib.Path(sym).name in self.names)
            self._memo[sym] = hit
            return hit
        # A dotted config key resolves either as a literal (a Kotlin `@ConfigProperty(name=...)`,
        # an `override.properties` line, an env-var mapping) or as a nested YAML path.
        if (CONFIGKEY.match(sym) and not POLICYRULE.match(sym)
                and any(sym.endswith(k) or k.endswith(sym) for k in self.config_keys)):
            self._memo[sym] = True
            return True
        needle = sym.rsplit(".", 1)[-1] if POLICYRULE.match(sym) else sym
        pat = self._word(needle)
        hit = any(pat.search(b) for b in self.code.values() if needle in b)
        self._memo[sym] = hit
        return hit

    def stub_site(self, sym: str) -> str | None:
        """A cited symbol whose own DECLARATION opens with a stub marker.

        Anchored on the declaration, not on any mention: `paymentId` appears in hundreds of
        lines fleet-wide and one of them, in an unrelated service, carries a TODO — matching
        that reports a phantom defect and teaches people the gate is noise.
        """
        if not (CAMEL.match(sym) or LOWERCAMEL.match(sym)):
            return None
        decl = re.compile(r"\b(?:class|object|interface|fun|val|var)\s+" + re.escape(sym) + r"\b")
        for f, b in self.main.items():
            if sym not in b:
                continue
            lines = b.splitlines()
            for n, line in enumerate(lines):
                if not decl.search(line):
                    continue
                if STUB_MARK.search("\n".join(lines[n:n + STUB_WINDOW])):
                    return f"{f}:{n + 1}"
        return None


# ---------------------------------------------------------------- self-reference
# The threat is "the record says it happened".
RECORD_THREAT = re.compile(
    r"claimed to have (?:run|happened|completed)|reports? success|reported success|"
    r"(?:did|does) not (?:actually|really)|without (?:actually|really)|"
    r"appears? (?:to have )?(?:completed|succeeded)|believed to have",
    re.IGNORECASE,
)
# Mechanisms that are themselves record-keeping — they cannot witness their own truthfulness.
RECORD_MECH = re.compile(
    r"\b(?:status|state)\b[^.;]{0,40}\b(?:update[sd]?|transition[sd]?|set to|records?|column)|"
    r"update[sd]? (?:the )?status|audit (?:trail|log|entr)|activity history|"
    r"workflow history|execution (?:trace|history)|records? every attempt",
    re.IGNORECASE,
)
# An independent mechanism rescues the row — something that could disagree with the record.
REAL_MECH = re.compile(
    r"reconcil|counter-?entr|opposite (?:movement|entry)|compare[sd]? against|"
    r"asserts? the|integration test|queries|verif|independent",
    re.IGNORECASE,
)


# ---------------------------------------------------------------- disclaimers
# A cell that explicitly says the named thing is NOT there is narrating a correction, not
# claiming a control. Its citations are quoted evidence and must not re-trigger the gate.
#
# This is deliberately an escape hatch, and deliberately a costly one: the only way to silence
# a finding without writing the code is to state in the threat model that the control does not
# exist — which is precisely the outcome this gate wants. Contrast a hand-kept exclusion list,
# where silence costs nothing and is invisible from the document.
DISCLAIMED = re.compile(
    r"does not exist|do(?:es)? not exist|exists? in no |present in no |is not implemented|"
    r"never (?:committed|existed|has)|no such |was never (?:built|wired|written)|"
    r"this control does not",
    re.IGNORECASE,
)


def is_disclaimed(mitigation: str) -> bool:
    return bool(DISCLAIMED.search(mitigation))


def self_referential(threat: str, mitigation: str) -> bool:
    if not RECORD_THREAT.search(threat):
        return False
    if REAL_MECH.search(mitigation):
        return False
    return bool(RECORD_MECH.search(mitigation))


# ---------------------------------------------------------------- driver
def subjects_all(root: pathlib.Path) -> list[str]:
    """Every threat model in the tree, derived from the DIRECTORY — never a hand-kept list.

    The subject set was `rules.yaml: money_path_services`, so 23 of the 45 documents in
    `docs/threat-models/` were never opened. That is not a tail: the unscanned half held four of
    the eight findings the 2026-09-03 sweep produced (`document-service`, `openbank-mcp-service`,
    `openbank-security-scanner`, `openbank-tpp-registry-service`), and a document being outside
    the money path is a statement about blast radius, not about whether its claims are true.

    Money-path membership is still read, but only to say so in the report — it no longer decides
    what gets read, which is the property that made the old scope a probe rather than a census.
    """
    d = root / "docs" / "threat-models"
    return sorted(f.stem for f in d.glob("*.md")) if d.is_dir() else []


def money_path(root: pathlib.Path) -> list[str]:
    """Derived from rules.yaml via the coverage gate's own parser — one definition, not two."""
    p = root / "openbank-infra" / "scripts" / "check-threat-models.py"
    spec = importlib.util.spec_from_file_location("cm", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.money_path_services(root / "openbank-libs" / "governance" / "rules.yaml")


def audit(root: pathlib.Path):
    services = subjects_all(root)
    mp = set(money_path(root))
    corpus = Corpus(root)
    findings: list[tuple[str, str, str, str]] = []
    used: set[str] = set()
    subjects = n_claims = n_uncited = n_disclaimed = 0
    for svc in services:
        path = root / "docs" / "threat-models" / f"{svc}.md"
        if not path.exists():
            continue  # the coverage gate's job, not this one
        subjects += 1
        for rid, threat, mitig, kind in claims(path.read_text(encoding="utf-8")):
            n_claims += 1
            if is_disclaimed(mitig):
                n_disclaimed += 1
                continue
            cites = citations(mitig)
            if not cites:
                n_uncited += 1
            for sym in cites:
                key = f"{svc}|{rid}|{sym}"
                if not corpus.resolve(sym):
                    if key in ALLOWED_UNRESOLVED:
                        used.add(key)
                        continue
                    findings.append(("PHANTOM", svc, rid,
                                     f"cites `{sym}` — present in no tracked backend source"))
                    continue
                site = corpus.stub_site(sym)
                if site:
                    if key in ALLOWED_UNRESOLVED:
                        used.add(key)
                        continue
                    findings.append(("STUB", svc, rid,
                                     f"cites `{sym}`, whose implementation is a stub ({site})"))
            if kind != "prose" and self_referential(threat, mitig):
                key = f"{svc}|{rid}|SELF-REF"
                if key in ALLOWED_UNRESOLVED:
                    used.add(key)
                    continue
                findings.append((
                    "SELF-REF", svc, rid,
                    ("threat is 'the record claims it happened'; "
                     "mitigation names only record-keeping"),
                ))
    stale = sorted(set(ALLOWED_UNRESOLVED) - used)
    return services, mp, subjects, n_claims, n_uncited, n_disclaimed, findings, stale


def self_test() -> int:
    """Falsify the gate: it must FAIL on the pre-#6048 settlement text and PASS once corrected.

    A gate is proven by what it PREVENTS. Each fixture is the real defect, verbatim in shape,
    paired with the corrected text — so a rule that stops discriminating fails here rather than
    silently going green on the fleet.
    """
    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want!r}, got {got!r}")

    # --- SELF-REF: settlement R2 as it stood before #6048, then as corrected.
    r2_threat = "Compensation claimed to have run when it did not"
    r2_bad = ("Temporal activity history records every attempt; compensation activities update "
              "status to `REVERSED`/`CREDITED_REVERSED`/`LEDGER_REVERSED` atomically")
    r2_good = ("Compensation activities issue the opposite movement to balance-service and are "
               "asserted by a real-DB integration test that queries for the counter-entry")
    case("pre-#6048 R2 is self-referential", self_referential(r2_threat, r2_bad), True)
    case("corrected R2 is not", self_referential(r2_threat, r2_good), False)
    case("record-keeping for an ORDINARY threat is fine",
         self_referential("Settlement DB record modified out-of-band", r2_bad), False)
    case("a non-record mitigation for a record threat is fine",
         self_referential(r2_threat, "mTLS between the worker and the server"), False)

    # --- PHANTOM: the extractor must SEE the symbols the real defects named.
    t1 = "`referenceId = workflowRunId + activityId` stored before side-effect"
    got = citations(t1)
    for want in ("referenceId", "workflowRunId", "activityId"):
        if want not in got:
            fails.append(f"T1 citation extraction missed {want}: got {got}")
    s1 = "OPA activity interceptor (`OpaActivityInterceptor`) rejects tasks not matching policy"
    case("S1's phantom class is extracted", "OpaActivityInterceptor" in citations(s1), True)
    case("a rego rule reference is extracted",
         "data.openbank.settlement.activity.allow"
         in citations("`data.openbank.settlement.activity.allow`"), True)
    case("a SCREAMING_CASE status constant is prose, not a citation",
         citations("`REVERSED`/`LEDGER_REVERSED`"), [])
    case("short lowercase words are not citations", citations("`the`, `and`"), [])

    # --- corpus scope: the rule that decides settlement S1.
    case("admin-ui source is not a deployed control",
         is_deployed("openbank-admin-ui/src/app/temporal/page.tsx"), False)
    case("a test is not a deployed control", is_deployed("openbank-x/src/test/kotlin/Foo.kt"), False)
    # ...but a test DOES prove the cited name exists. Without this, three real test classes were
    # reported as phantom controls on the 2026-08-20 corpus run.
    case("a test still counts for existence", is_backend("openbank-x/src/test/kotlin/Foo.kt"), True)
    case("admin-ui counts for neither",
         is_backend("openbank-admin-ui/src/app/temporal/page.tsx"), False)
    case("service src/main is deployed", is_deployed("openbank-x/src/main/kotlin/Foo.kt"), True)
    case("a policy is deployed",
         is_deployed("openbank-infra/gitops/components/x/x_rest_ext.rego"), True)

    # --- disclaimers. A correction has to NAME the phantom it is correcting, so without this
    # rule the fix for a finding re-triggers the same finding forever.
    case("a cell disclaiming its own control is not a claim",
         is_disclaimed("**Corrected** — this control does not exist; `AuditService` is present "
                       "in no source file in this repository"), True)
    case("an ordinary mitigation is not disclaimed",
         is_disclaimed("`QsealVerifier` verifies the detached signature"), False)
    case("a negation about the THREAT is not a disclaimer of the control",
         is_disclaimed("the attacker does not hold a valid token"), False)

    # --- STUB marker.
    case("a stub log line is a stub marker",
         bool(STUB_MARK.search('"… (compensation — stub: wire reversal)"')), True)
    case("ordinary prose is not", bool(STUB_MARK.search("issues the opposite movement")), False)

    # --- parsers: both must find claims, and the UNION must be at least as large as either.
    doc = ("## Threat enumeration (STRIDE)\n\n### T — Tampering\n\n"
           "| ID | Threat | Mitigation |\n|----|--------|------------|\n"
           f"| T1 | Replayed debit activity credits twice | {t1} |\n"
           f"| R2 | {r2_threat} | {r2_bad} |\n")
    tbl, sec, uni = parse_table(doc), parse_sections(doc), claims(doc)
    case("table parser finds both rows", len(tbl), 2)
    if len(sec) < 2:
        fails.append(f"section parser found {len(sec)} rows, expected >= 2")
    if len(uni) < max(len(tbl), len(sec)):
        fails.append("the union is smaller than a single parser — dedup is eating claims")
    # A model with NO Mitigation column must still be seen: this is the case that makes one
    # parser a probe rather than a census (billing, vop).
    headerless = ("### Spoofing\n\n| # | Item | Status |\n|---|---|---|\n"
                  "| 1 | `SomeFilter` bounds a caller | Shipped |\n")
    case("headerless model is invisible to the table parser", len(parse_table(headerless)), 0)
    if len(parse_sections(headerless)) < 1:
        fails.append("section parser missed a headerless model — the census collapsed to one method")

    # ---------------------------------------------------------------- the 2026-09-03 widening
    # Each block below is a claim that sat INSIDE a file this gate already opened and was reported
    # clean. They are the red cases the widening was built against: if a rule stops discriminating,
    # this fails here rather than going quietly green over the fleet again.

    # (1) REGION. Every one of these lived outside a STRIDE mitigation cell, so neither original
    #     parser ever saw it. The prose parser must, and the STRIDE parsers must still see theirs.
    s3 = ("## 3. Authn / Authz\n\n"
          "- Money-moving endpoints are role-gated; **no endpoint is `@PermitAll`** (locked by\n"
          "  `BalanceResourceSecurityTest`).\n")
    case("a §3 prose bullet is invisible to the table parser", len(parse_table(s3)), 0)
    case("a §3 prose bullet is invisible to the section parser", len(parse_sections(s3)), 0)
    case("the prose parser sees it",
         any("BalanceResourceSecurityTest" in citations(m) for _, _, m in parse_prose(s3)), True)
    chg = ("## 7. Change log\n\n"
           "- **2026-06-17** — `FxConversionService.scoreFraudShadow()` wraps the call.\n")
    case("a change-log entry is a claim",
         any("FxConversionService" in citations(m) for _, _, m in parse_prose(chg)), True)

    # (2) CITATION SHAPE. A dotted config key / topic name matched no shape at all.
    case("a config property is a citation",
         "openbank.sepa.returns.enabled" in citations("flag `openbank.sepa.returns.enabled` (off by default)"), True)
    case("a topic name is a citation",
         "openbank.billing.billing.event" in citations("(`openbank.billing.billing.event`)"), True)
    case("a hostname is NOT a citation",
         citations("`sanctionslistservice.ofac.treas.gov`"), [])
    case("a maven coordinate is NOT a citation", citations("`com.microsoft.onnxruntime`"), [])
    case("a qualified method reference yields its owning type",
         citations("`FxService.scoreFraudShadow()`"), ["FxService"])
    case("a source path is still a path, not a split type",
         citations("`ExceptionMappers.kt`"), ["ExceptionMappers.kt"])

    # (3) RESOLUTION. Both of these answered TRUE under the old substring-over-all-lines rule.
    class _FakeCorpus(Corpus):
        def __init__(self, blobs):  # test double: no git, no filesystem
            self.files, self.names, self.paths = [], set(), set()
            self.blobs, self._memo = blobs, {}
            self.main = {}
            self.config_keys = set()
            for _f, _b in blobs.items():
                if _f.endswith((".yaml", ".yml", ".properties")):
                    self.config_keys |= Corpus.yaml_paths(_b)
            self.code = {f: "\n".join(ln for ln in b.splitlines()
                                      if not ln.lstrip().startswith(("//", "*", "/*", "#", "<!--", "--")))
                         for f, b in blobs.items()}

    kdoc = _FakeCorpus({"openbank-x/src/main/kotlin/BalanceResource.kt":
                        "/**\n * locked by BalanceResourceSecurityTest.\n */\nclass BalanceResource"})
    case("a name that exists only in a COMMENT does not resolve",
         kdoc.resolve("BalanceResourceSecurityTest"), False)
    case("a name on a real code line does resolve", kdoc.resolve("BalanceResource"), True)
    sub = _FakeCorpus({"openbank-x/src/test/kotlin/B.kt": "class BalanceSecurityContractTest {"})
    case("a SUFFIX of a real class does not resolve", sub.resolve("SecurityContractTest"), False)
    case("the real class still resolves", sub.resolve("BalanceSecurityContractTest"), True)

    # (4) DISCRIMINATION. The widening must not simply fail everything: the CORRECTED form of each
    #     red case has to come back clean, or the gate is a blanket and not a check.
    good = _FakeCorpus({"openbank-x/src/test/kotlin/C.kt": "class ClearingSecurityContractTest {",
                        "openbank-x/src/main/resources/application.yaml":
                            "openbank:\n  sepa:\n    scheme-submission:\n      enabled: true\n"})
    case("the corrected guard name resolves", good.resolve("ClearingSecurityContractTest"), True)
    case("a real config key resolves", good.resolve("openbank.sepa.scheme-submission.enabled"), True)
    case("SELF-REF is not applied to prose",
         [k for _, _, _, k in claims("### Tampering\n\n- a bullet\n")] .count("prose") >= 1, True)

    # (5) SUBJECT SET. The floor exists to catch COLLAPSE — a renamed directory or a changed glob —
    #     so falsify it: point the deriver at a directory with no models and it must report none,
    #     rather than reporting clean about files it stopped reading.
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        empty = pathlib.Path(td)
        case("an empty tree yields no subjects", subjects_all(empty), [])
        (empty / "docs" / "threat-models").mkdir(parents=True)
        (empty / "docs" / "threat-models" / "a.md").write_text("x")
        (empty / "docs" / "threat-models" / "b.md").write_text("y")
        case("subjects are derived from the directory", subjects_all(empty), ["a", "b"])

    for f in fails:
        print(f"SELF-TEST FAIL: {f}")
    print(f"self-test: {'FAILED' if fails else 'ok'} ({len(fails)} failure(s))")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()

    root = pathlib.Path(a.root).resolve()
    services, mp, subjects, n_claims, n_uncited, n_disclaimed, findings, stale = audit(root)

    print(f"SUBJECTS={subjects}")
    print(f"threat-model claim audit: {subjects}/{len(services)} models "
          f"({len(mp)} of them money-path), "
          f"{n_claims} mitigation claims (union of three parsers), "
          f"{n_uncited} name no mechanism at all "
          f"(unfalsifiable as written — counted, not failed), "
          f"{n_disclaimed} explicitly disclaimed as absent")

    lvl = "error" if a.enforce else "warning"
    for kind, svc, rid, detail in findings:
        rel = f"docs/threat-models/{svc}.md"
        print(f"::{lvl} file={rel},title=threat-model claim ({kind})::{svc} {rid}: {detail}")
    for key in stale:
        print(f"::{lvl} title=stale exclusion::ALLOWED_UNRESOLVED['{key}'] matched nothing — "
              f"the claim it excused is gone; delete the entry")

    if a.report:
        return 0
    if findings or stale:
        print(f"FAIL: {len(findings)} false/self-referential claim(s), "
              f"{len(stale)} stale exclusion(s)")
        return 1 if a.enforce else 0
    print("OK: every mechanically checkable mitigation claim resolves to real, non-stub code")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
