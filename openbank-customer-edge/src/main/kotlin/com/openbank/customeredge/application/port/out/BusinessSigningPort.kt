// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.application.port.out

import java.util.UUID

/** An upstream answer, kept as status + raw JSON so a 4xx from the owner passes through unaltered. */
data class SigningReply(val status: Int, val body: String) {
    val ok: Boolean get() = status in SUCCESS
    private companion object {
        val SUCCESS = 200..299
    }
}

/**
 * delegation-service's business-signing API (#10281): the signing policy, trusted payees and
 * approval requests of an entity. delegation-service is the source of truth for all of them; the
 * edge holds a payment upstream of the rail and releases it only against a single-use claim.
 *
 * Every call is made with the edge's service identity and the party it concerns; the human who
 * signs or rejects is always passed explicitly — the port never infers it.
 */
@Suppress("TooManyFunctions") // one method per internal route of the contract
interface BusinessSigningPort {
    fun evaluate(entity: UUID, body: String): SigningReply

    fun policy(entity: UUID): SigningReply

    fun changePolicy(entity: UUID, body: String): SigningReply

    fun trustedPayees(entity: UUID): SigningReply

    fun addTrustedPayee(entity: UUID, body: String): SigningReply

    fun removeTrustedPayee(entity: UUID, payeeId: String, initiator: UUID): SigningReply

    fun createApproval(entity: UUID, body: String): SigningReply

    fun approvals(entity: UUID, status: String?, signer: UUID?): SigningReply

    fun approval(entity: UUID, approvalId: UUID): SigningReply

    fun sign(entity: UUID, approvalId: UUID, signer: UUID, scaChallengeId: UUID): SigningReply

    fun reject(entity: UUID, approvalId: UUID, signer: UUID, reason: String?): SigningReply

    fun releaseClaim(entity: UUID, approvalId: UUID): SigningReply

    fun releaseResult(entity: UUID, approvalId: UUID, body: String): SigningReply

    fun pendingFor(human: UUID): SigningReply
}
