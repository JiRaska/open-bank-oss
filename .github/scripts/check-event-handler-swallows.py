#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""No event handler may ACK work it did not do.

WHY THIS GATE EXISTS
    A `@Incoming` handler that returns normally ACKS its Kafka message; a `@Scheduled` method that
    catches its own failure completes its tick. In both cases there is no caller to see the error.
    So a handler that catches an exception, logs it and returns tells the platform the work is done
    — and an acked message that did nothing is indistinguishable from a successful one: not in
    consumer lag, not in the DLQ (which stays empty), not on any dashboard built on either. The only
    trace is an ERROR line, and nothing pages on ERROR lines.

    On 2026-08-19 kyc-db was unreachable for a few seconds. One PARTY_CREATED landed in that window,
    kyc-service logged `Failed to auto-open/screen KYC case` and acked. No KYC case, so the party
    stayed PENDING_KYC, its two accounts stayed PENDING_ACTIVATION, and the welcome bonus — which
    fires only on activation — never ran. A customer had accounts that did not work and no money in
    them (#5698). One confirmed instance — the nine other case-less sandbox parties turned out to be
    six that predate the auto-open consumer and three account-less e2e fixtures, so the "10 of 73"
    in the first report was wrong. The audit that followed found the same catch-and-ack shape in 15
    places across 10 services, including four GDPR Art. 17 erasure handlers that logged "anonymised"
    while the PII stayed put — that part is what makes this a gate rather than one bug fix.

    Hexagonal architecture is why this is a rule and not a review note: a dependency being down is a
    NORMAL event for an adapter, not an exception to be logged away. The port contract is "the work
    happens or somebody finds out".

WHAT IT FLAGS
    In a file containing @Incoming or @Scheduled: a `catch (e: Exception|Throwable)` whose body
    never throws, wrapping a try that performs a STATE CHANGE through a repository / use case /
    port / store / publisher.

WHAT IT DOES NOT FLAG, AND WHY
    - A catch that rethrows (directly, or via EventRetry.withRetry, which ends in a throw).
    - A parse-only catch — a malformed event is the genuine poison pill: replaying it fails
      identically forever, so acking it is correct. That case was never the problem; conflating it
      with a DB outage was.
    - A catch that RECORDS the failure durably (markFailed / markDead / recordFailure / reschedule):
      the outbox dispatcher's shape. The work stays processable and the attempt count is the signal.
    - A catch marked `best-effort` or `observed-by:` in a comment on or above it. Some side effects
      really are optional (a push when the money is already booked), and some failures are visible
      through something other than the DLQ (a workflow-liveness gauge that stops being refreshed).
      Both are legitimate; both must be STATED where a reviewer reads them, rather than left to a
      bare catch that looks identical to the defect.

USAGE
    check-event-handler-swallows.py [--enforce] [--self-test]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

HANDLER = re.compile(r"@(Incoming|Scheduled)\b")
CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(?:Exception|Throwable)\s*\)\s*\{")
# A state change reached through the hexagon's outbound side. Matched on the RECEIVER's name, which
# is the convention this codebase actually follows (…Repository, …UseCase, …Port, …Store, …Publisher).
# Transport-level receivers: `httpClient.send(...)` is a READ over HTTP, not a state change, and
# `client` has to stay in the receiver list because `transactionClient.initiateTransaction(...)` —
# an actual debit — is spelled that way. Naming is the only signal available here, so the narrow
# transport names are excluded by name rather than the whole `client` family being dropped.
TRANSPORT_RECEIVER = re.compile(r"\b(httpClient|webClient|restClient|httpclient|kafkaClient)\s*\.\s*$", re.IGNORECASE)

STATE_CALL = re.compile(
    # RECEIVER BLINDNESS (#5745 section C). The nine nouns above are a naming convention,
    # not a rule, and the fleet does not universally follow it. Every read-model and
    # sink handler spells its receiver outside the set — `projections.applyIfNewer`,
    # `sink.write`, `sendLog.applyDeliveryOutcome`, `projection.eraseParty` — so the
    # gate scanned 182 handler files, matched nothing, and printed "clean". A scope
    # that is a naming convention reads as PASSING when the convention is not
    # followed, never as UNCHECKED.
    r"\b\w*(?:repository|repo|usecase|port|store|dao|publisher|client|service|"
    r"projections|projection|sink|log|writer|sender|gateway|adapter)\s*\.\s*"
    r"(save|persist|insert|update|create|open|activate|grant|credit|debit|post|record|apply|"
    r"anonymize|anonymise|delete|upsert|merge|register|enrol|enroll|book|settle|publish|send|"
    r"notify|emit|write|mark|complete|cancel|approve|reject|erase|"
    # Money-movement verbs the alternation still lacked. `initiate` is why
    # `SddCollectionDebitConsumer`'s `transactionClient.initiateTransaction(...)` — a real
    # direct-debit debit — was not flagged: the receiver matched (which is exactly what
    # TRANSPORT_RECEIVER above keeps `client` in the list FOR), but the verb did not.
    r"initiate|execute|transfer|charge|capture|reverse|refund|submit|dispatch|trigger|"
    r"assign|revoke|close|freeze|release|disburse|repay|withdraw|deposit|"
    # Read-model / sink verbs. `taxFilingService.observe(...)` matched the RECEIVER
    # (…Service) and was still invisible, because the verb list was written from the
    # money path and a projection does not "save", it "observes" or "ingests".
    r"observe|ingest|index|flush|commit|advance|project)\w*\s*\(",
    re.IGNORECASE,
)
# Two ways to be legitimately silent, and both must be STATED where a reviewer reads them.
#   best-effort:  the event is complete without this side effect (a push when the money is booked).
#   observed-by:  the failure IS visible, just not through the DLQ — a liveness gauge that stops
#                 being refreshed, an attempt counter that stops advancing. Name the signal.
EXCUSED = re.compile(r"best-effort|observed-by:", re.IGNORECASE)
# A catch that RECORDS the failure into durable state is not swallowing it: the work stays
# processable and something downstream can see it. The outbox dispatcher is the canonical case —
# markFailed() burns an attempt and leaves the row for the next tick.
# The Kotlin idiom that is the same defect without the word `catch` in it:
#   runCatching { repo.save(x) }.getOrNull()          — failure becomes null, nobody is told
#   runCatching { … }.onFailure { log.warn(it) }      — failure becomes a log line
# A `try/catch` is at least visible to a reviewer scanning for error handling; this reads as
# ordinary Kotlin. Five such sites existed when this was added, and the first version of the gate
# could not see a single one of them.
RUN_CATCHING = re.compile(r"runCatching\s*\{")
RECOVERED = re.compile(r"^\s*\.\s*(getOrNull|getOrElse|getOrDefault|onFailure|fold)\b")
# .getOrThrow() / .getOrElse { throw … } are the SAFE terminators: the failure still surfaces.
RETHROWN_TERMINATOR = re.compile(r"getOrThrow|throw")

# A MANUAL NEGATIVE ACKNOWLEDGEMENT is the other correct ending, and it must be recognised
# here or widening STATE_CALL above turns correct code red. `message.nack(e)`
# (campaign's EnrolmentTriggerConsumer) and `settle(message, e)` (analytics-sink's
# AnalyticsConsumer) both leave the message unacked and the work recoverable — the exact
# outcome the gate exists to require. Neither goes through a repository, so neither matched
# the durable-state clause below.
HANDLED_IN_CATCH = re.compile(
    r"\b\w*(?:repository|repo|store)\s*\.\s*(markFailed|markDead|recordFailure|reschedule|release)\w*\s*\("
    r"|\bnack\s*\("
    r"|\bsettle\s*\(\s*\w*message",
    re.IGNORECASE,
)


# A call to a helper declared in the SAME file. The state change often lives one level down:
# `StandingOrderDueConsumer`'s try body calls `executeSepa(orderId, node)`, and only INSIDE that
# does `sepaClient.createPayment(...)` appear — which STATE_CALL would have matched had it been
# inline. Text-scanning the try body alone cannot see it, so a handler that delegates to a
# well-named domain helper (the tidier way to write one) is invisible to this gate.
LOCAL_CALL = re.compile(r"(?<![\w.])([a-z]\w*)\s*\(")


def _local_fun_bodies(text: str) -> dict:
    """Every `fun name(...)` declared in this file, by name — block- and expression-bodied."""
    bodies = {}
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", text):
        name = m.group(1)
        depth, i = 0, m.end() - 1
        while i < len(text):
            depth += (text[i] == "(") - (text[i] == ")")
            i += 1
            if depth == 0:
                break
        rest = text[i : i + 400]
        brace, eq = rest.find("{"), rest.find("=")
        if brace != -1 and (eq == -1 or brace < eq) and rest[:brace].count("\n") <= 2:
            bodies[name] = _block(text, i + brace)[0]
        elif eq != -1 and rest[:eq].count("\n") <= 2:
            nl = rest.find("\n", eq)
            bodies[name] = rest[eq : nl if nl != -1 else len(rest)]
    return bodies


def _state_change_in(body: str, funs=None, depth: int = 2, _seen=None):
    """First STATE_CALL in `body` that is not a transport-level read.

    With `funs` (same-file `fun` bodies) it also follows calls to those helpers, `depth` levels
    deep and cycle-safe. Depth 2 covers the real shape — handler -> executeSepa ->
    sepaClient.createPayment — without letting one generic helper name drag half a file into scope.
    """
    for m in STATE_CALL.finditer(body):
        if TRANSPORT_RECEIVER.search(body[: m.start() + len(m.group(0).split(".")[0]) + 1]):
            continue
        return m
    if funs and depth > 0:
        seen = _seen if _seen is not None else set()
        for call in LOCAL_CALL.findall(body):
            if call in seen or call not in funs:
                continue
            seen.add(call)
            deeper = _state_change_in(funs[call], funs, depth - 1, seen)
            if deeper:
                return deeper
    return None


def _block(text: str, open_brace_idx: int) -> tuple[str, int]:
    """Return the {...} body starting at open_brace_idx, and the index just past its close."""
    depth, i = 1, open_brace_idx + 1
    while i < len(text) and depth:
        depth += (text[i] == "{") - (text[i] == "}")
        i += 1
    return text[open_brace_idx + 1 : i - 1], i


def _preceding_comment_block(text: str, try_idx: int) -> str:
    """The contiguous run of comment lines directly above the try, and nothing else."""
    lines = text[:try_idx].split("\n")
    if lines and lines[-1].strip() == "":
        lines.pop()
    block = []
    for line in reversed(lines[:-1] if lines and lines[-1].lstrip().startswith("try") else lines):
        stripped = line.strip()
        if stripped.startswith(("//", "*", "/*")):
            block.append(stripped)
            continue
        break
    return "\n".join(block)


def _run_catching_findings(text: str, path: str, funs=None) -> list[tuple[str, int, str]]:
    """runCatching { <state change> } whose result is recovered rather than rethrown."""
    out = []
    for m in RUN_CATCHING.finditer(text):
        body, after = _block(text, m.end() - 1)
        state = _state_change_in(body, funs)
        if not state:
            continue
        # What happens to the Result decides it: .getOrThrow() or an else-branch that throws is
        # fine; .getOrNull() / .onFailure { log } is the swallow.
        tail = text[after : after + 300]
        if not RECOVERED.search(tail.lstrip("\n")) and not tail.lstrip().startswith("."):
            continue
        if RETHROWN_TERMINATOR.search(tail[:200]):
            continue
        context = text[max(0, m.start() - 1200) : m.start()] + tail[:200]
        if EXCUSED.search(context):
            continue
        out.append((path, text[: m.start()].count("\n") + 1, state.group(0).strip()))
    return out


def findings_for(text: str, path: str) -> list[tuple[str, int, str]]:
    if not HANDLER.search(text):
        return []
    funs = _local_fun_bodies(text)
    out = _run_catching_findings(text, path, funs)
    for m in re.finditer(r"\btry\s*\{", text):
        try_body, after = _block(text, m.end() - 1)
        tail = text[after : after + 400]
        if not CATCH.match(tail.lstrip()):
            continue
        catch_open = after + tail.index("{", tail.index("catch"))
        catch_body, _ = _block(text, catch_open)
        if "throw" in catch_body or HANDLED_IN_CATCH.search(catch_body):
            continue
        state = _state_change_in(try_body, funs)
        if not state:
            continue
        # The marker must justify THIS catch: it counts only inside the catch body, or in the
        # comment block IMMEDIATELY above the try — contiguous comment lines, no blank line and no
        # code between. Anything looser is how the gate first excused account-service's welcome
        # bonus: the word "Best-effort" appeared in a KDoc describing the *notification* three lines
        # further down, and the money-losing swallow above it inherited the excuse.
        if EXCUSED.search(catch_body) or EXCUSED.search(_preceding_comment_block(text, m.start())):
            continue
        out.append((path, text[: m.start()].count("\n") + 1, state.group(0).strip()))
    return out


def scan(root: Path) -> tuple[list[tuple[str, int, str]], int]:
    files = [p for p in root.rglob("*.kt") if "/build/" not in str(p) and "/src/main/" in str(p)]
    findings: list[tuple[str, int, str]] = []
    handlers = 0
    for p in files:
        text = p.read_text(encoding="utf-8", errors="replace")
        if not HANDLER.search(text):
            continue
        handlers += 1
        findings += findings_for(text, str(p.relative_to(root)))
    return findings, handlers


SELF_TEST_CASES = [
    (
        "swallowed state change is flagged",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            try {
                repo.save(entity)
            } catch (e: Exception) {
                log.error("nope", e)
            }
        }
        """,
        1,
    ),
    (
        "rethrowing catch is not flagged",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            try {
                repo.save(entity)
            } catch (e: Exception) {
                log.error("nope", e)
                throw e
            }
        }
        """,
        0,
    ),
    (
        "parse-only catch is not flagged (the real poison pill)",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            val node = try {
                objectMapper.readTree(p)
            } catch (e: Exception) {
                log.error("bad json", e)
                return
            }
        }
        """,
        0,
    ),
    (
        "a catch that records the failure durably is not swallowing it",
        """
        @Scheduled(every = "5s")
        suspend fun dispatch() {
            try {
                publisher.publish(entry)
                repository.markSent(entry.id)
            } catch (e: Exception) {
                repository.markFailed(entry.id, e.message)
            }
        }
        """,
        0,
    ),
    (
        "observed-by marker is honoured",
        """
        @Scheduled(cron = "0 30 3 * * ?")
        suspend fun sweep() {
            // observed-by: the workflow-liveness gauge stops being refreshed, alerting on staleness
            try {
                conversationStore.deleteExpired(now)
                liveness?.recordSuccess()
            } catch (e: Exception) {
                log.error("failed", e)
            }
        }
        """,
        0,
    ),
    (
        "best-effort marker is honoured",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            // best-effort: the money is already booked, this is only a push
            try {
                notificationPort.notify(partyId)
            } catch (e: Exception) {
                log.warn("no push", e)
            }
        }
        """,
        0,
    ),
    (
        "a scheduled job swallowing a write is flagged too",
        """
        @Scheduled(every = "5s")
        suspend fun sweep() {
            try {
                outboxRepository.markComplete(id)
            } catch (e: Exception) {
                log.error("nope", e)
            }
        }
        """,
        1,
    ),
    (
        "runCatching + getOrNull is the same swallow without the word catch",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            runCatching { repo.save(entity) }.getOrNull()
        }
        """,
        1,
    ),
    (
        "runCatching + onFailure that only logs is flagged",
        """
        @Scheduled(every = "5s")
        suspend fun sweep() {
            runCatching { outboxRepository.markComplete(id) }
                .onFailure { log.warn("failed", it) }
        }
        """,
        1,
    ),
    (
        "runCatching + getOrThrow still surfaces the failure",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            runCatching { repo.save(entity) }.getOrThrow()
        }
        """,
        0,
    ),
    (
        "runCatching around something that changes no state is not a finding",
        """
        @Incoming("x")
        suspend fun consume(p: String) {
            val id = runCatching { UUID.fromString(p) }.getOrNull() ?: return
        }
        """,
        0,
    ),
    (
        "a file with no handler annotation is out of scope",
        """
        class Plain {
            fun go() {
                try {
                    repo.save(entity)
                } catch (e: Exception) {
                    log.error("nope", e)
                }
            }
        }
        """,
        0,
    ),
    # --- shapes the naming-convention match could not see (money-path when found) ---
    (
        "a money verb outside the original list is still a state change (sdd: initiateTransaction)",
        """
        class C {
            @Incoming("x")
            suspend fun consume(p: String) {
                try {
                    transactionClient.initiateTransaction(request).awaitSuspending()
                } catch (e: Exception) {
                    log.error("swallowed", e)
                }
            }
        }
        """,
        1,
    ),
    (
        "a state change one private-helper deep is reached (standing-order: executeSepa)",
        """
        class C {
            @Incoming("x")
            suspend fun consume(p: String) {
                try {
                    executeSepa(orderId, node)
                } catch (e: Exception) {
                    log.error("swallowed", e)
                }
            }

            private suspend fun executeSepa(orderId: UUID, node: JsonNode) {
                sepaClient.createPayment(idempotencyKey, request).awaitSuspending()
            }
        }
        """,
        1,
    ),
    (
        "helper indirection does not invent findings when the helper changes nothing",
        """
        class C {
            @Incoming("x")
            suspend fun consume(p: String) {
                try {
                    formatForLog(node)
                } catch (e: Exception) {
                    log.error("fine", e)
                }
            }

            private fun formatForLog(node: JsonNode): String = node.toPrettyString()
        }
        """,
        0,
    ),
    (
        "a recursive helper terminates instead of hanging the scan",
        """
        class C {
            @Incoming("x")
            suspend fun consume(p: String) {
                try {
                    walk(node)
                } catch (e: Exception) {
                    log.error("fine", e)
                }
            }

            private fun walk(n: JsonNode) { walk(n) }
        }
        """,
        0,
    ),
    # ---- #5745 section C: receivers the nine-noun convention never covered ---------------
    # Each of these is a verbatim reduction of a real handler on main. All four `1` cases
    # return 0 findings on the unwidened gate — which is how 182 handler files scanned
    # "clean".
    (
        "a read-model projection is a state change (anacredit: projections.applyIfNewer)",
        """
        @Incoming("loan-stage")
        suspend fun consume(p: String) {
            try {
                projections.applyIfNewer(projection)
            } catch (e: Exception) {
                log.error("projection failed", e)
            }
        }
        """,
        1,
    ),
    (
        "a sink write is a state change (analytics-sink: sink.write)",
        """
        @Incoming("analytics")
        suspend fun consume(p: String) {
            try {
                sink.write(rows)
            } catch (e: Exception) {
                log.error("write failed", e)
            }
        }
        """,
        1,
    ),
    (
        "a delivery-log update is a state change (campaign: sendLog.applyDeliveryOutcome)",
        """
        @Incoming("notification-outcome")
        suspend fun consume(p: String) {
            try {
                sendLog.applyDeliveryOutcome(id, outcome)
            } catch (e: Exception) {
                log.error("outcome lost", e)
            }
        }
        """,
        1,
    ),
    (
        # The receiver ALREADY matched (…Service). Only the verb was missing, because the
        # alternation was written from the money path and a projection does not "save".
        "a domain-service verb outside the money-path vocabulary (tax: taxFilingService.observe)",
        """
        @Incoming("withholding-remitted")
        suspend fun consume(p: String) {
            try {
                taxFilingService.observe(event)
            } catch (e: Exception) {
                log.error("observation lost", e)
            }
        }
        """,
        1,
    ),
    # ---- the two controls that must stay GREEN, or the widening above is a false-positive
    # factory. Both are correct code on main today: the message stays unacked and the work
    # is recoverable, without any repository being involved.
    (
        "manual nack is not a swallow (campaign: message.nack(e))",
        """
        @Incoming("enrolment-trigger")
        suspend fun consume(message: Message<String>) {
            try {
                triggered.enrol(id)
            } catch (e: Exception) {
                log.error("enrol failed", e)
                message.nack(e)
            }
        }
        """,
        0,
    ),
    (
        "settle(message, e) is not a swallow (analytics-sink: AnalyticsConsumer)",
        """
        @Incoming("analytics")
        suspend fun consume(message: Message<String>) {
            try {
                sink.write(rows)
            } catch (e: Exception) {
                log.error("write failed", e)
                settle(message, e)
            }
        }
        """,
        0,
    ),
]


def self_test() -> int:
    bad = 0
    for name, src, want in SELF_TEST_CASES:
        got = len(findings_for(src, "fixture.kt"))
        if got != want:
            print(f"  BAD  {name}: want {want} finding(s), got {got}")
            bad += 1
        else:
            print(f"  ok   {name}")
    # And the corpus itself must be reachable: a scan that finds no handlers is a broken scan.
    _, handlers = scan(REPO)
    if handlers < 20:
        print(f"  BAD  corpus scan found only {handlers} handler file(s) — the walk is broken")
        bad += 1
    else:
        print(f"  ok   corpus scan reaches {handlers} handler files")
    if bad:
        print(f"[event-handler-swallows] SELF-TEST FAILED ({bad})")
        return 1
    print("[event-handler-swallows] self-test passed: flags the defect, spares the legitimate shapes.")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    findings, handlers = scan(REPO)
    print(f"SUBJECTS={handlers}  # files with @Incoming/@Scheduled handlers")
    for path, line, call in sorted(findings):
        print(
            f"::error file={path},line={line}::[event-handler-swallows] `{call}` runs inside a "
            f"catch that logs and returns — the message is ACKED and the work is lost with no "
            f"signal. Retry and rethrow (com.openbank.libs.messaging.EventRetry), or mark the "
            f"catch `best-effort` / `observed-by:` with the reason (#5698)."
        )
    if findings:
        print(f"[event-handler-swallows] {len(findings)} swallowed state change(s) in {handlers} handler files")
        return 1 if "--enforce" in sys.argv else 0
    print(f"[event-handler-swallows] clean: {handlers} handler files, no swallowed state changes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
