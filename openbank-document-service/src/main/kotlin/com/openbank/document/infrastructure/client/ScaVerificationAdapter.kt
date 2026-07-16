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
 * Calls `POST /api/v1/sca/challenges/{id}/consume` directly — the same integration point
 * `customer-edge`'s payment `scaGate()` uses — rather than pre-checking the challenge's `status`
 * via `GET .../challenges/{id}` first. A decoupled (PUSH_NOTIFICATION/BIOMETRIC) challenge stays
 * `PENDING` in storage until something promotes it to `COMPLETED`; `ScaService.consume()` does
 * that promotion itself, lazily, the moment a signature-verified device decision exists
 * (`verifyDecoupled`, invoked from inside `consume()`). A plain `GET` never triggers that
 * promotion, so a status pre-check here would see `PENDING` even for a freshly-approved
 * challenge and always fail — this adapter had exactly that bug until it was caught wiring up
 * the app-side document-signing flow (ADR-0170); fixed by dropping the pre-check and letting
 * `consume()` do both the promotion and the RTS Art. 5 dynamic-linking match in one atomic call.
 *
 * `consume` is RTS Art. 5 single-use: sca-service returns 409 CONFLICT
 * (`ScaChallengeAlreadyConsumedException`) if [evidenceRef] is replayed for a second decision,
 * which this adapter treats the same as any other client error: verification fails. Ownership
 * (challenge belongs to [partyRef]) and the document/ceremony match are both enforced server-side
 * by `consume` itself (`ScaChallengePartyMismatchException` / `ScaDynamicLinkingMismatchException`,
 * both 4xx) — no separate check is needed here.
 */
@ApplicationScoped
class ScaVerificationAdapter(@RestClient private val client: ScaChallengeClient) : SignerVerificationPort {

    override suspend fun verify(
        partyRef: String,
        evidenceRef: String,
        documentSha256: String?,
        ceremonyId: String?,
    ): Boolean {
        if (evidenceRef.isBlank()) return false
        val challengeId = runCatching { UUID.fromString(evidenceRef) }.getOrNull() ?: return false
        val partyId = runCatching { UUID.fromString(partyRef) }.getOrNull() ?: return false
        return try {
            val request = ScaConsumeClientRequest(
                partyId = partyId,
                documentSha256 = documentSha256,
                ceremonyId = ceremonyId,
            )
            client.consume(challengeId, request).awaitSuspending()
            true
        } catch (e: WebApplicationException) {
            // sca-service returns 403/404 for a challenge that doesn't exist, isn't visible to
            // this caller, or belongs to another party; 409 if already consumed (replay); 409 if
            // the document/ceremony don't match what the device signed (dynamic-linking
            // mismatch) — either way verification cannot succeed. A non-4xx failure (network, 5xx)
            // is a different failure mode and intentionally NOT caught here, so it propagates.
            if (isClientError(e)) false else throw e
        }
    }

    private fun isClientError(e: WebApplicationException): Boolean {
        val status = e.response?.status ?: return false
        return status in HTTP_CLIENT_ERROR_RANGE
    }

    private companion object {
        val HTTP_CLIENT_ERROR_RANGE = 400..499
    }
}
