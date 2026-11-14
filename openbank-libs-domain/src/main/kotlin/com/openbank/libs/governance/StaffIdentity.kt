// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.governance

/**
 * Who is acting on a four-eyes decision — the NATURAL PERSON, not merely the login account.
 *
 * Four-eyes is a rule about people: "one person cannot do this alone". Comparing login accounts
 * enforces something weaker — one person holding two staff accounts passes it. [personId] is the
 * stable identifier of the person behind [account] (the IdP `person_id` claim; every account of
 * one person carries the same value), and it is what [Proposal.approveBy] compares.
 *
 * Built by the runtime from a VERIFIED token (`com.openbank.libs.security.StaffIdentities`); this
 * type is framework-free (ADR-0122) so the state machine can be tested without a token.
 *
 * @property account the login account (principal name) — kept for the audit trail.
 * @property personId the person identifier, or `null` when the token carried none. A `null` (or
 *   blank) person id can neither propose nor decide a person-bound proposal: the check fails CLOSED.
 * @property strongAuth whether the session was authenticated with a second factor (token `amr`).
 *   Required to approve or reject; not required to propose or withdraw.
 */
data class StaffIdentity(val account: String, val personId: String?, val strongAuth: Boolean) {
    /** The person id when present and non-blank, else `null`. */
    val person: String?
        get() = personId?.trim()?.takeIf { it.isNotEmpty() }

    override fun toString(): String = "StaffIdentity(account=$account, personId=$person, strongAuth=$strongAuth)"
}
