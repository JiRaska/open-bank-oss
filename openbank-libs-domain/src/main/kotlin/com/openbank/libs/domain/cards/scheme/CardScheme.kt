// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.cards.scheme

/**
 * The card networks this platform can bind an adapter for (ADR-0283 D1).
 *
 * Deliberately a separate type from card-issuance's `CardNetwork`, which records **which brand a
 * card carries**. This one records **which adapter a capability call goes to**. They coincide today
 * and will not always: a bank can hold a Visa BIN and still call Mastercard's merchant-location
 * service, and a co-badged card carries two brands with one processing network.
 *
 * `SIMULATOR` is a first-class value, not a test double smuggled into production code. It is the
 * binding this repository actually ships (see `card-capabilities.yaml: bindings`), and naming it
 * here is what keeps "we simulate this" distinguishable from "we integrated this" at every call
 * site — the same reason a skipped delivery gets its own outcome value rather than sharing one
 * with success.
 */
enum class CardScheme {
    VISA,
    MASTERCARD,
    SIMULATOR,
}

/**
 * Why a scheme call did not produce an answer.
 *
 * There is no `UNKNOWN`: every value here is something a caller can act on differently. A caller
 * that cannot tell "this network does not offer the capability" from "the network was down" will
 * retry the first for ever and give up on the second.
 */
enum class SchemeFailure {
    /** The binding is configured off, or no adapter is bound for this scheme and capability. */
    NOT_BOUND,

    /** The network answered, and the answer was "no such record". */
    NOT_FOUND,

    /** The network could not be reached, or answered too slowly. */
    UNAVAILABLE,

    /** The network rejected the credentials. Distinct from UNAVAILABLE: retrying will not fix it. */
    UNAUTHENTICATED,

    /** The network answered with something this adapter cannot map. A defect, not a condition. */
    MALFORMED,
}

/**
 * The result of a capability call.
 *
 * A sealed result rather than an exception or a nullable: a scheme call fails in ways a caller must
 * branch on, and an exception carries that badly (every caller writes its own catch, and the ones
 * that forget turn a "not offered" into a 500). Modelled on the card money path's own
 * `PresentmentOutcome`.
 */
sealed interface SchemeResult<out T> {
    data class Answered<T>(val value: T, val scheme: CardScheme) : SchemeResult<T>

    data class Unanswered(val failure: SchemeFailure, val scheme: CardScheme, val detail: String? = null) :
        SchemeResult<Nothing>

    /** The value if the call was answered, otherwise null — for the callers that genuinely do not branch. */
    fun valueOrNull(): T? = (this as? Answered)?.value
}
