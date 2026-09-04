#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# A service that ships an outbox must write to it (issue #4007).
#
# THE DEFECT THIS CATCHES
# Four services ship the whole transactional-outbox apparatus — a `*OutboxDispatcher`, a backlog
# gauge, a Flyway migration for the table, `openbank.outbox.dispatch-enabled: true` — and construct
# no `OutboxMessage` anywhere in `src/main`. The dispatcher polls a table nothing writes to, forever.
#
# It is invisible from every angle normally checked:
#   * `dispatch-enabled: true` is set, so the usual "the switch was never flipped" check passes
#   * the dispatcher runs, finds nothing, and logs nothing
#   * the backlog gauge reads 0 — indistinguishable from a healthy drained queue
#   * the events still arrive downstream, because those services publish through a SECOND, direct,
#     non-transactional `@Channel` emitter — so no consumer notices
#   * a unit test that mocks the repository cannot tell which publisher a use case called
#
# What is lost is the guarantee the outbox exists for: that the state change and the event commit
# together. A direct emitter cannot do that. `PARTY_MERGED` and `AccountStatusChanged` are exactly
# the events where that divergence is expensive.
#
# WHY THIS PREDICATE AND NOT `persistInTransaction` CALLERS
# #4007 was written from a count of `persistInTransaction` call sites, and that is the wrong
# question — it finds the plumbing, not the write. Measured both ways on the same tree, they
# disagree on four services in both directions: `interest` and `sepa-instant` have no
# `persistInTransaction` caller yet DO construct an `OutboxMessage`; `balance` and `kyc` have the
# repository method and construct nothing. Constructing the message IS the write, so that is what
# this checks.
#
# COMMENTS AND STRINGS ARE STRIPPED FIRST
# This repo has been burnt in both directions — a guard that flagged the comment explaining the bug
# it exists to catch (#2450), and a test that matched the five-line comment above the very line it
# asserted on, so deleting the line left it green (#3072). A KDoc saying "constructs an
# OutboxMessage(...)" must not count as a writer. Kotlin block comments NEST, so the stripper is a
# depth counter rather than a regex; string literals go first so a `"/*"` inside one cannot open a
# comment.
#
# RATCHET, NOT A BIG BANG
# The four below are real and each needs a per-service decision (wire it, or delete the apparatus)
# that is not this gate's to make. They are baselined against #4007 so the gate can be ENFORCED
# today: a sixth service cannot be added quietly. A baseline entry that becomes covered is reported
# too, so the list keeps meaning something rather than silently becoming permanent.
#
# The list started at eight. Four have left it, and only three of those were fixes: `party`
# (#4158), `kyc` (#4378) and `tpp-registry` (#4007) were wired onto their outbox, while `billing`
# was never a violation at all — see its note in BASELINE. Worth separating, because a shrinking
# baseline reads as progress and one of these four was a measurement error, not a repair.

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib

# Baselined violations. Each is a service that ships an outbox nothing writes to, measured
# 2026-08-07. Removing one from this list is the definition of done for that service.
BASELINE = {
    # openbank-audit-service is no longer a violation: the dead outbox apparatus was deleted
    # entirely (#5126) rather than wired, since no consumer need for an outbound audit-service
    # event was ever identified. The service no longer ships a dispatcher at all.
    # openbank-balance-service was wired instead of deleted (#8510): the write side now exists —
    # HoldRepository.saveWithEvent/releaseWithEvent, BalanceMovementPortImpl and
    # LedgerProjectionPortImpl write the outbox row in the SAME transaction as the state change,
    # the direct KafkaBalanceEventPublisher is retired, and OutboxBalanceEventPublisher covers the
    # announcement-only value-date roll. Its baseline reason was the #4007 mis-bin: the count had
    # included the port DECLARATION of persistInTransaction, not a call. Proven by
    # BalanceOutboxWriteIT — a real-DB IT, because a mocked repository cannot tell whether an
    # outbox row was written.
    # openbank-billing-service was never a violation (#4007): it writes billing.fee.post-intent.v1
    # from BillingAssessmentRepositoryImpl by constructing BillingOutboxEntity() inside the same
    # sf.withTransaction as the assessment, which is a correct atomic outbox write this gate could
    # not see until it learned the entity idiom. Its baseline reason — "2 DEAD rows from a writer
    # no longer present" — was wrong in both halves: the writer is present, and the 2 DEAD rows
    # (attempt_count 10, billing_outbox, sandbox) are a DISPATCH failure, which is a different
    # defect and is not this gate's subject.
    # openbank-kyc-service was wired instead of deleted (#4007): the case lifecycle events now go
    # through kyc_outbox in the state-change transaction and the direct KycEventPublisher is gone.
    # Removing an entry from this list is the definition of done for that service.
    # openbank-party-service was wired instead of deleted (#4007): the lifecycle events now go
    # through party_outbox in the state-change transaction and the direct KafkaPartyEventPublisher
    # is gone. Removing an entry from this list is the definition of done for that service.
    "openbank-pid-service": "#4007 — dispatcher + gauge, no OutboxMessage construction",
    "openbank-psd2-service": "#4007 — dispatcher + gauge, no OutboxMessage construction",
    # openbank-tpp-registry-service was wired instead of deleted (#4007): TPP_REGISTERED and
    # TPP_BLACKLISTED now go through tpp_outbox in the same transaction as the tpp_entries row
    # (TppRepositoryImpl.save/update, which take the event as a REQUIRED parameter so there is no
    # eventless overload to bypass). Wired rather than deleted because every other end of the arrow
    # already existed and only the write did not: the KafkaTopic, the write ACL, the
    # event-contract baseline entry and the gitops headers all assert a producer for
    # openbank.tpp.registry.event. Proven by TppOutboxWriteIT — a real-DB IT, because a mocked
    # repository cannot tell whether an outbox row was written.
    # Removing an entry from this list is the definition of done for that service.
}

DISPATCHER_RE = re.compile(r"class\s+\w*OutboxDispatcher\b")
DECLARATION_RE = re.compile(r"(data\s+)?class\s+OutboxMessage\s*\(")
# A row built as a JPA ENTITY is the third write idiom in this tree, and matching only the domain
# type and raw SQL was blind to it. openbank-billing-service writes `billing.fee.post-intent.v1`
# from BillingAssessmentRepositoryImpl by constructing `BillingOutboxEntity().apply { … }` inside
# the same `sf.withTransaction` as the assessment — a correct, atomic outbox write — and this gate
# reported it as writerless and baselined it as "2 DEAD rows from a writer no longer present".
# The writer was never removed; the rows are DEAD because dispatch failed ten times, which is a
# different defect entirely. A false negative here reads as a clean finding, which is exactly how
# a wrong entry survived four re-measurements of #4007.
ENTITY_CONSTRUCTION_RE = re.compile(r"\b\w*OutboxEntity\s*\(\s*\)")
# ...but NOT in the outbox repository's own adapter. Every service that ships the apparatus has an
# `*OutboxRepositoryImpl` whose `OutboxMessage.toEntity()` maps an ALREADY-constructed message on
# the DRAIN side. That mapper is plumbing, in the same category as the data-class declaration
# above: counting it would mark all 34 dispatcher-shipping services as writers and silently retire
# the gate — the same failure the SQL predicate's `\w*outbox\w*` narrowness exists to avoid.
# Verified against the tree: for audit, balance, pid and psd2 the ONLY `*OutboxEntity()`
# construction is that mapper, so all four stay violations.
OUTBOX_ADAPTER_RE = re.compile(r"Outbox\w*RepositoryImpl\.kt$")


def constructs_outbox_entity(body: str) -> bool:
    """True if `body` CONSTRUCTS an outbox entity, as opposed to declaring one.

    A Kotlin supertype call is spelled exactly like a constructor call, and every one of these
    entities is declared `class XOutboxEntity : PanacheOutboxEntity()`. Matching the bare pattern
    therefore found a "writer" in all five genuinely writerless services — in the entity's own
    declaration file — and would have retired the gate while reporting six fixes. Measured, not
    reasoned: the first version of this predicate did exactly that.

    So a match counts only when the character before it is not `:` or `,`, the two positions a
    supertype can occupy. That is narrower than skipping the declaration FILE, which would blind
    the gate to a service that declares its entity and writes it in the same file.
    """
    for hit in ENTITY_CONSTRUCTION_RE.finditer(body):
        before = body[: hit.start()].rstrip()
        if before and before[-1] in ":,":
            continue
        return True
    return False
# A row written by direct SQL is just as much a write as one built through the domain type, and
# some services cannot use the domain type at all: a Temporal activity thread carries no Vert.x
# context, so reactive Panache throws HR000068 there and the service inserts through plain JDBC
# instead (case-coordinator-agent's CaseActivitiesImpl.emitProposal, agent-service's
# JdbcAgentProposalRepository). Matching only `OutboxMessage(` called those services writerless
# and was wrong about them.
#
# The table reference is matched in the three forms Postgres accepts, not just the bare one:
# `case_outbox`, schema-qualified `cc.case_outbox`, and quoted `"case_outbox"` (or backticked, for
# a copy-pasted MySQL-ism). Neither of the extra two appears in the tree today and schema-qualified
# INSERT is not an idiom here at all — this is latent, and it is written down because the failure
# is a FALSE NEGATIVE: a service that does write its outbox gets reported as writerless, which
# reads as a clean finding rather than as a gap in the pattern (#4240).
#
# The `\w*outbox\w*` on the table NAME is what keeps this narrow. Widening to any INSERT would
# mark all the baselined services as fixed and silently retire the gate, so the negatives in the
# self-test are the cases that matter here, not the positives.
#
# The trailing `(?!\w*\s*\.)` is not decoration and was measured, not reasoned: because the schema
# group is OPTIONAL, `INSERT INTO outbox_schema.events` otherwise backtracks into matching
# `outbox_schema` as the TABLE and reports a writer for an insert into `events`. A false positive
# here is the opposite failure — it would let a genuinely writerless service off — so the lookahead
# says "this name is the table only if no dot follows it".
SQL_INSERT_RE = re.compile(
    r"INSERT\s+INTO\s+"
    r"(?:[\"`]?\w+[\"`]?\s*\.\s*)?"  # optional schema qualifier, quoted or bare
    r"[\"`]?\w*outbox\w*[\"`]?"
    r"(?!\w*\s*\.)",  # ...and it must not itself be a qualifier
    re.IGNORECASE,
)


def strip_comments_and_strings(src: str) -> str:
    """Remove string literals, line comments and NESTED block comments.

    Strings first: a `"/*"` inside a literal must not open a comment. Kotlin's block comments nest,
    so `/* /* */ */` closes once — a non-greedy regex would close at the first `*/` and leave the
    tail of a KDoc as live code.
    """
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    src = re.sub(r"//[^\n]*", "", src)
    out: list[str] = []
    depth = i = 0
    while i < len(src):
        if src.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if src.startswith("*/", i) and depth:
            depth -= 1
            i += 2
            continue
        if not depth:
            out.append(src[i])
        i += 1
    return "".join(out)


def strip_comments_only(src: str) -> str:
    """Remove line and NESTED block comments, keeping string literals intact.

    The SQL predicate needs this: an INSERT lives inside a string literal, which
    strip_comments_and_strings() blanks. Prose describing an insert must still not count, so the
    comments still go.

    STRING-AWARE, and it has to be. Its sibling above states the invariant this function must also
    honour — "a `\"/*\"` inside a literal must not open a comment" — and gets it for free by
    blanking literals first. This one cannot blank them (the SQL *is* a literal), so it has to
    track them, which makes a regex the wrong tool: comment and string openers alias each other.
    The first version walked `/*` blind to literals, so `val a = "/*"` opened a comment that
    swallowed every following INSERT and reported a real writer as writerless — measured, and the
    one case in the self-test below that separates the two implementations (#4240). Raw strings are
    consumed first: otherwise the opening `\"\"` of a `\"\"\"…\"\"\"` reads as an empty literal.

    No service in the tree trips this today — the fleet verdict is byte-identical before and after
    (33 dispatchers, 8 baselined, 0 new, 0 stale). This is a latent-defect fix, so the self-test is
    the only thing that can hold it: there is no failing service to point at.
    """
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    while i < n:
        if depth:
            if src.startswith("/*", i):
                depth += 1
                i += 2
            elif src.startswith("*/", i):
                depth -= 1
                i += 2
            else:
                i += 1
            continue
        if src.startswith("/*", i):
            depth = 1
            i += 2
            continue
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
            continue
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            end = n if end == -1 else end + 3
            out.append(src[i:end])
            i = end
            continue
        if src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def classify_service(files: dict[str, str]) -> tuple[bool, bool]:
    """(ships_a_dispatcher, constructs_a_message) for one service's RAW `src/main` sources.

    Stripping happens HERE, not in the caller. The first version of this took pre-stripped input and
    the self-test — which passes raw source, as any honest test of "does a comment count" must —
    failed all four comment cases. A seam where the caller is trusted to sanitise is a seam where one
    forgetful caller silently counts prose as code, which is the exact failure this gate exists to
    prevent.
    """
    dispatcher = writes = False
    for path, raw in files.items():
        body = strip_comments_and_strings(raw)
        # SQL lives INSIDE a string literal, which the stripper blanks — so the SQL predicate reads
        # a comment-only strip. Prose about an insert still must not count, hence not the raw text.
        sql_body = strip_comments_only(raw)
        if DISPATCHER_RE.search(body):
            dispatcher = True
        if "OutboxMessage(" in body and not DECLARATION_RE.search(body):
            writes = True
        if SQL_INSERT_RE.search(sql_body):
            writes = True
        if not OUTBOX_ADAPTER_RE.search(path) and constructs_outbox_entity(body):
            writes = True
    return dispatcher, writes


def scan(root: pathlib.Path) -> tuple[dict[str, bool], int]:
    """(service -> constructs_a_message for every service that ships a dispatcher, services walked).

    The walked count is returned as well because the dispatcher-shipping set is a SUBSET: an
    empty result is what a renamed module prefix or a moved source root produces, and it prints
    identically to a fleet where nothing ships an outbox.
    """
    result: dict[str, bool] = {}
    walked = 0
    for svc in sorted(p for p in root.glob("openbank-*") if p.is_dir()):
        # openbank-libs-* ships the ABSTRACT dispatcher every service extends. It owns no table and
        # constructs no message by design, so including it is a permanent false positive.
        if svc.name.startswith("openbank-libs"):
            continue
        main = svc / "src" / "main" / "kotlin"
        if not main.is_dir():
            continue
        walked += 1
        files = {}
        for kt in main.rglob("*.kt"):
            try:
                files[str(kt)] = kt.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
        dispatcher, writes = classify_service(files)
        if dispatcher:
            result[svc.name] = writes
    return result, walked


def self_test() -> int:
    fails = 0

    def expect(name, got, want):
        nonlocal fails
        if got == want:
            print(f"  ok   {name}")
        else:
            print(f"  FAIL {name}: want {want}, got {got}")
            fails = 1

    disp = "class PartyOutboxDispatcher : AbstractOutboxDispatcher() { }"
    write = "val m = OutboxMessage(eventId = id, aggregateId = a, eventType = t, payload = p)"
    decl = "data class OutboxMessage(\n  val eventId: UUID,\n)"

    expect("dispatcher + a real construction is clean",
           classify_service({"a.kt": disp, "b.kt": write}), (True, True))
    expect("dispatcher with no construction is a violation",
           classify_service({"a.kt": disp}), (True, False))
    expect("no dispatcher is out of scope entirely",
           classify_service({"b.kt": write}), (False, True))
    # The declaration is not a write — otherwise libs-domain would count as its own writer.
    expect("the data class declaration alone is not a write",
           classify_service({"a.kt": disp, "d.kt": decl}), (True, False))

    # Code-about-code: prose naming the constructor must not count.
    kdoc = disp + "\n/** Builds an OutboxMessage(...) for each transition. */\n"
    expect("a KDoc mentioning OutboxMessage( is NOT a writer",
           classify_service({"a.kt": kdoc}), (True, False))
    line_comment = disp + "\n// val m = OutboxMessage(x)\n"
    expect("a commented-out construction is NOT a writer",
           classify_service({"a.kt": line_comment}), (True, False))
    nested = disp + "\n/* outer /* inner */ still a comment: OutboxMessage(x) */\n"
    expect("a NESTED block comment stays a comment to its real end",
           classify_service({"a.kt": nested}), (True, False))
    in_string = disp + '\nval s = "OutboxMessage(fake)"\n'
    expect("the constructor named inside a string literal is NOT a writer",
           classify_service({"a.kt": in_string}), (True, False))
    # A direct SQL insert IS a write. case-coordinator-agent writes this way on purpose: a Temporal
    # activity thread has no Vert.x context, so reactive Panache throws HR000068 and it inserts
    # through plain JDBC. Matching only `OutboxMessage(` called it writerless and was wrong.
    sql = disp + '''
    conn.prepareStatement("""
        INSERT INTO case_outbox (event_id, aggregate_id, event_type, payload, status)
        VALUES (?, ?, ?, ?, ?)
    """)
'''
    expect("a direct SQL INSERT into the outbox table IS a writer",
           classify_service({"a.kt": sql}), (True, True))

    # ── The JPA-entity write idiom (#4007) ────────────────────────────────────────────────────
    # billing writes its outbox by constructing the ENTITY, not the domain type and not raw SQL.
    # The negatives below are the ones that matter: they are what stops this predicate from
    # marking every dispatcher-shipping service as a writer and retiring the gate.
    entity = disp + "\nval e = BillingOutboxEntity().apply { eventType = t }\n"
    expect("constructing the outbox ENTITY outside the adapter IS a writer",
           classify_service({"a.kt": disp, "BillingAssessmentRepositoryImpl.kt": entity}),
           (True, True))
    # The drain-side mapper every service has. If this counted, all 34 would be "writers".
    mapper = "private fun OutboxMessage.toEntity() = PidOutboxEntity().also { }"
    expect("the adapter's own toEntity() mapper is NOT a writer",
           classify_service({"a.kt": disp, "PidOutboxRepositoryImpl.kt": mapper}), (True, False))
    # ...and the exclusion is by FILE, so the same mapper text elsewhere still counts — otherwise
    # a service could dodge the gate by naming a file conveniently.
    expect("the same construction in a non-adapter file still counts",
           classify_service({"a.kt": disp, "PartyService.kt": mapper}), (True, True))
    # Code-about-code applies here too, via the same stripper.
    entity_kdoc = disp + "\n/** Builds a PidOutboxEntity() per transition. */\n"
    expect("a KDoc mentioning the entity constructor is NOT a writer",
           classify_service({"a.kt": entity_kdoc}), (True, False))
    entity_string = disp + '\nval s = "PidOutboxEntity()"\n'
    expect("the entity constructor inside a string literal is NOT a writer",
           classify_service({"a.kt": entity_string}), (True, False))
    # A non-outbox entity must not match — the `\w*Outbox` stem is what keeps this narrow.
    other_entity = disp + "\nval e = AssessedFeeEntity().apply { }\n"
    expect("constructing a NON-outbox entity is NOT a writer",
           classify_service({"a.kt": other_entity}), (True, False))
    # THE case that caught the first version of this predicate. A Kotlin supertype call is spelled
    # identically to a constructor call, and every outbox entity in the tree is declared this way,
    # so without the `:`-position guard all five writerless services reported as fixed.
    supertype = disp + "\nclass PidOutboxEntity : PanacheOutboxEntity() { var id: UUID? = null }\n"
    expect("the entity DECLARATION's supertype call is NOT a writer",
           classify_service({"a.kt": disp, "PidOutboxEntity.kt": supertype}), (True, False))
    multi = disp + "\nclass PidOutboxEntity : Base, PanacheOutboxEntity() { }\n"
    expect("a supertype call after a comma is NOT a writer",
           classify_service({"a.kt": disp, "PidOutboxEntity.kt": multi}), (True, False))
    # ...and a declaration file that ALSO constructs one really is a writer — which is why the
    # guard is by position and not by filename.
    decl_and_write = supertype + "\nval e = PidOutboxEntity().apply { }\n"
    expect("a declaration file that also constructs one IS a writer",
           classify_service({"a.kt": disp, "PidOutboxEntity.kt": decl_and_write}), (True, True))

    # ── Qualified and quoted table references (#4240 follow-up) ────────────────────────────────
    # Latent: neither form appears in the tree today, and schema-qualified INSERT is not an idiom
    # here at all. Covered because the failure direction is a FALSE NEGATIVE — a service that does
    # write its outbox reported as writerless, which reads as a clean finding.
    schema_qualified = disp + '\nval s = """INSERT INTO cc.case_outbox (id) VALUES (?)"""\n'
    expect("a schema-qualified INSERT IS a writer",
           classify_service({"a.kt": schema_qualified}), (True, True))
    quoted = disp + '\nval s = """INSERT INTO "case_outbox" (id) VALUES (?)"""\n'
    expect("a quoted table name IS a writer",
           classify_service({"a.kt": quoted}), (True, True))
    both = disp + '\nval s = """INSERT INTO "cc"."case_outbox" (id) VALUES (?)"""\n'
    expect("quoted schema AND quoted table IS a writer",
           classify_service({"a.kt": both}), (True, True))

    # The negative that pays for the optional schema group. Because that group is OPTIONAL, the
    # pattern otherwise backtracks into reading `outbox_schema` as the TABLE and calls an insert
    # into `events` a writer — which would let a genuinely writerless service off. Measured
    # failing without the trailing lookahead.
    schema_named_outbox = disp + '\nval s = """INSERT INTO outbox_schema.events (id) VALUES (?)"""\n'
    expect("a SCHEMA named *outbox* with a non-outbox table is NOT a writer",
           classify_service({"a.kt": schema_named_outbox}), (True, False))
    qualified_other = disp + '\nval s = """INSERT INTO cc.party_events (id) VALUES (?)"""\n'
    expect("a schema-qualified NON-outbox table is NOT a writer",
           classify_service({"a.kt": qualified_other}), (True, False))
    # …but prose describing one is not — the same code-about-code rule as the constructor case.
    sql_prose = disp + "\n// we should INSERT INTO case_outbox here one day\n"
    expect("a comment describing an INSERT is NOT a writer",
           classify_service({"a.kt": sql_prose}), (True, False))
    sql_kdoc = disp + "\n/** Historically this did INSERT INTO case_outbox directly. */\n"
    expect("a KDoc describing an INSERT is NOT a writer",
           classify_service({"a.kt": sql_kdoc}), (True, False))

    # The SQL strip must honour the same `"/*"` invariant its sibling states. Exactly ONE of the
    # three below was measured failing against the regex-plus-blind-walk version this replaced —
    # the first. The other two pass either way and are regression tests, not evidence of a defect;
    # saying so here because a comment claiming three failures where one was measured is the kind
    # of unverified evidence this file's own header exists to warn about.
    #
    # A false negative is the dangerous direction for all three: the gate reports a service that
    # DOES write its outbox as writerless, which reads as a clean finding rather than a broken
    # probe — the shape that put this gate on main's critical path in the first place (#4240).
    slash_star_literal = disp + '\nval a = "/*"\nval s = """INSERT INTO case_outbox (id)"""\n'
    expect('a "/*" inside a literal must not open a comment and swallow the INSERT',
           classify_service({"a.kt": slash_star_literal}), (True, True))
    # Regression: `//` stripped to end-of-line must not reach inside a literal and take a
    # following INSERT with it.
    url_in_sql = (
        disp + '\nval s = """\n-- see http://wiki/outbox\nINSERT INTO case_outbox (id)\n"""\n'
    )
    expect("a // inside the SQL literal does not hide the INSERT",
           classify_service({"a.kt": url_in_sql}), (True, True))
    # Regression: a quote inside a comment must not open a string and swallow the SQL after it.
    raw_after_comment = (
        disp + '\n/* the "outbox" table */\nval s = """INSERT INTO case_outbox"""\n'
    )
    expect("a quote inside a comment does not swallow the following SQL",
           classify_service({"a.kt": raw_after_comment}), (True, True))

    # …and the stripper must not swallow real code that merely follows a comment.
    after = disp + "\n/* note */\n" + write
    expect("a real construction AFTER a comment still counts",
           classify_service({"a.kt": after}), (True, True))

    if fails:
        print("check-outbox-has-writer: self-test FAIL")
        return 1
    print("check-outbox-has-writer: self-test PASS")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    found, walked = scan(pathlib.Path(args.root))
    gatelib.subjects(walked, "service Kotlin main source trees walked")
    violations = sorted(svc for svc, writes in found.items() if not writes)
    new = [v for v in violations if v not in BASELINE]
    stale = sorted(b for b in BASELINE if b not in violations)

    for svc in new:
        print(
            f"::error::{svc}: ships an *OutboxDispatcher but constructs no OutboxMessage in "
            f"src/main. The dispatcher will poll a table nothing writes to — its backlog gauge "
            f"reads 0, which is indistinguishable from a drained queue. Either wire the write side "
            f"or delete the apparatus (#4007)."
        )
    for svc in stale:
        print(
            f"::error::{svc}: baselined in this script as having no outbox writer, but it now has "
            f"one. Remove it from BASELINE so the list keeps meaning something."
        )

    print(
        f"check-outbox-has-writer: {len(found)} service(s) ship a dispatcher; "
        f"{len(violations)} without a writer ({len(BASELINE)} baselined, {len(new)} new, "
        f"{len(stale)} stale baseline entr{'y' if len(stale) == 1 else 'ies'})."
    )
    return 1 if (new or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
