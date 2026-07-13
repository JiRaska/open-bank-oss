// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.openbank.document.application.port.out.SignerVerificationPort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * [SignerVerificationPort] adapter over `openbank-sca-service` (ADR-0021, ADR-0162 D4).
 *
 * `sca-service`'s API (see its `openapi.yaml`) has no single "verify this approval" endpoint
 * shaped exactly for a third-party caller; the closest clean, real integration point is
 * `GET /api/v1/sca/challenges/{id}`, which returns the challenge's owning `partyId` and its
 * `status`. This adapter treats [evidenceRef] as that challenge id: a signer's decision is
 * considered SCA-verified only if the referenced challenge belongs to [partyRef] and has reached
 * sca-service's real terminal-success status, `COMPLETED` (`ScaChallenge.ScaStatus` — there is no
 * `VERIFIED` value; `isCompleted()`/`complete()` are keyed on `COMPLETED`).
 *
 * A verified challenge is then spent via `consume` (RTS Art. 5 single-use) so the same
 * [evidenceRef] cannot be replayed for a second decision — sca-service returns 409 CONFLICT
 * (`ScaChallengeAlreadyConsumedException`) on a second attempt, which this adapter treats the same
 * as any other client error: verification fails.
 *
 * This is a real call to sca-service, not a stub — but it does encode an assumption
 * (evidenceRef == an SCA challenge id) that a future, purpose-built "verify approval" endpoint on
 * sca-service could make explicit instead of inferred here.
 */
@ApplicationScoped
class ScaVerificationAdapter(@RestClient private val client: ScaChallengeClient) : SignerVerificationPort {

    override suspend fun verify(partyRef: String, evidenceRef: String): Boolean {
        if (evidenceRef.isBlank()) return false
        val challengeId = runCatching { UUID.fromString(evidenceRef) }.getOrNull() ?: return false
        val partyId = runCatching { UUID.fromString(partyRef) }.getOrNull() ?: return false
        return try {
            val challenge = client.getChallenge(challengeId).awaitSuspending()
            if (challenge.status != COMPLETED_STATUS || challenge.partyId != partyId) {
                return false
            }
            client.consume(challengeId, ScaConsumeClientRequest(partyId = partyId)).awaitSuspending()
            true
        } catch (e: WebApplicationException) {
            // sca-service returns 403/404 for a challenge that doesn't exist or isn't visible to
            // this caller, and 409 if `consume` is called on an already-spent challenge (a replay
            // attempt) — either way verification cannot succeed. A non-4xx failure (network, 5xx)
            // is a different failure mode and intentionally NOT caught here, so it propagates.
            if (isClientError(e)) false else throw e
        }
    }

    private fun isClientError(e: WebApplicationException): Boolean {
        val status = e.response?.status ?: return false
        return status in HTTP_CLIENT_ERROR_RANGE
    }

    private companion object {
        const val COMPLETED_STATUS = "COMPLETED"
        val HTTP_CLIENT_ERROR_RANGE = 400..499
    }
}
