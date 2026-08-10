// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.synthetic

/**
 * The synthetic taint (ADR-0252 phase 1, issue #4348).
 *
 * ONE flag, carried end to end, marking activity produced by a bank-owned synthetic customer
 * rather than a real one. It is the primitive the whole synthetic-customer fleet rests on, and
 * it is deliberately tiny: a header name, a baggage key, and a parse with one strongly-argued
 * default.
 *
 * ## Why a taint at all
 *
 * A synthetic customer that does not touch the real code path proves nothing about the real
 * code path. So the canary's payment IS a real payment: it hits the ledger, the screening
 * engine and the event streams. That is the point, and it is also the risk — those movements
 * must never reach a regulatory aggregate. The taint is what lets both be true at once.
 *
 * ## The asymmetry, which is easy to get backwards
 *
 * Two rules, and they point in opposite directions:
 *
 *  - **Regulatory aggregates and analytics baselines must EXCLUDE tainted activity.** FINREP,
 *    COREP, AnaCredit, statistical returns, AML baseline scoring. A synthetic movement in a
 *    regulatory return is a misstatement.
 *  - **Control paths must INCLUDE it, always.** Sanctions screening, Verification of Payee,
 *    SCA, limits, fraud scoring. The canary exists to prove those controls are alive; a
 *    control that skips synthetic traffic is a control the canary cannot test, and excluding
 *    synthetic payments from screening would manufacture a screening blind spot — a worse
 *    defect than the one the fleet is built to catch.
 *
 * There is no `mayBypassControl` helper here on purpose. A call site that wants to skip work
 * for synthetic traffic is asking the wrong question, and the absence of an API to answer it
 * is the cheapest possible way to say so.
 *
 * ## Fail-to-REAL, never fail-to-synthetic
 *
 * [isTainted] treats anything that is not an exact, case-insensitive `true` as REAL — absent,
 * empty, malformed, `1`, `yes`, all of it. The two failure directions are not symmetric:
 *
 *  - real activity misread as synthetic ⇒ it is dropped from FINREP/COREP/AnaCredit and from
 *    the AML baseline. A regulatory return that silently omits real customer money.
 *  - synthetic activity misread as real ⇒ a canary's own movements land in those aggregates.
 *    Wrong, visible, and bounded by how much the canaries do — which the platform sets.
 *
 * The first is unbounded and silent, so the default leans away from it. `1`/`yes`/`on` are
 * rejected rather than accepted for the same reason: a permissive parser turns any stray value
 * in a header into a suppression.
 */
object SyntheticTaint {

    /**
     * Kafka record header. Lower-case and hyphenated to match the fleet's other transport
     * headers, and NOT a payload field: a taint that lives in the body can only be read by a
     * consumer that already deserialises that body, while every consumer, bridge and dead-letter
     * tool can read a header.
     */
    const val KAFKA_HEADER: String = "x-openbank-synthetic"

    /**
     * OpenTelemetry baggage key. Dotted, per OTel convention, and distinct from [KAFKA_HEADER]
     * on purpose — they travel different rails and a shared constant would imply one hop
     * carries the other.
     */
    const val BAGGAGE_KEY: String = "openbank.synthetic"

    /** The only value that means "synthetic". See the fail-to-real note in the class KDoc. */
    const val TRUE_VALUE: String = "true"

    /**
     * True only for an exact, case-insensitive [TRUE_VALUE], after trimming surrounding
     * whitespace. Everything else — null, blank, `1`, `yes`, `TRUE!`, a stray byte — is REAL.
     */
    fun isTainted(value: String?): Boolean = value?.trim()?.equals(TRUE_VALUE, ignoreCase = true) == true

    /**
     * True when [headers] carries the taint. The lookup is case-insensitive because header
     * casing survives no transport reliably: Kafka preserves it, HTTP/2 lower-cases it, and a
     * bridge between them may do either. A case-sensitive lookup here would make the taint
     * silently disappear at exactly one hop, which is the failure this whole ADR is about.
     */
    fun isTainted(headers: Map<String, String?>): Boolean =
        headers.entries.firstOrNull { it.key.equals(KAFKA_HEADER, ignoreCase = true) }
            ?.value
            ?.let(::isTainted)
            ?: false

    /** The header value to stamp. Only ever [TRUE_VALUE] — an untainted record carries no header. */
    fun headerValue(): String = TRUE_VALUE

    /**
     * Whether a regulatory aggregate, statistical return or analytics baseline may count this
     * record. Named for the decision rather than the flag so a reader of the call site sees the
     * rule, not a boolean whose polarity they have to reconstruct.
     */
    fun admittedToRegulatoryAggregate(tainted: Boolean): Boolean = !tainted

    /** [admittedToRegulatoryAggregate] straight from a header map, for a consumer that has one. */
    fun admittedToRegulatoryAggregate(headers: Map<String, String?>): Boolean = !isTainted(headers)
}
