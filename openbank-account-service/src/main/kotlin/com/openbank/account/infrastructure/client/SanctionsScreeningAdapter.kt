// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.port.out.SanctionsScreenResult
import io.quarkus.logging.Log
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient

@ApplicationScoped
class SanctionsScreeningAdapter : AccountSanctionsScreeningPort {

    @Inject
    @RestClient
    lateinit var client: SanctionsServiceClient

    override suspend fun screen(name: String, idempotencyKey: String): SanctionsScreenResult = try {
        val response = client.screen(
            SanctionsScreenRequest(
                idempotencyKey = idempotencyKey,
                entityType = "INDIVIDUAL",
                name = name,
            ),
        ).awaitSuspending()
        SanctionsScreenResult(
            status = response.status ?: "CLEAR",
            matchScore = response.overallScore ?: 0.0,
            matchedName = response.matches.firstOrNull()?.matchedName,
        )
    } catch (e: WebApplicationException) {
        // Distinguish HTTP client errors (4xx) from server/network failures (5xx/timeout).
        val status = e.response?.status ?: 0
        if (status in 400..499) {
            // 4xx = sanctions service rejected our request (bad payload, auth issue etc.)
            // Still fail closed — a missing screening is a compliance risk — but log at ERROR.
            Log.errorf(
                "Sanctions service rejected request (HTTP %d) for key=%s: %s",
                status,
                idempotencyKey,
                e.message,
            )
        } else {
            Log.warnf("Sanctions service returned HTTP %d — failing closed for key=%s", status, idempotencyKey)
        }
        throw AccountScreeningUnavailableException(e)
    } catch (e: Exception) {
        // Network error, timeout, or other infrastructure failure — fail closed.
        Log.warnf("Sanctions screening unavailable (key=%s): %s", idempotencyKey, e.message)
        throw AccountScreeningUnavailableException(e)
    }
}
