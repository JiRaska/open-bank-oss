// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.PartyNamePort
import com.openbank.fx.application.port.out.PartyNameUnavailableException
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Resilient, fail-closed adapter over [PartyServiceClient]. Transport faults are retried and
 * circuit-broken like the other inter-service adapters; once they are exhausted (or the breaker is
 * open) the failure is mapped to [PartyNameUnavailableException]. So is every answer that yields
 * nothing to screen — a party the bank does not hold (404) and a party with no name — because the
 * caller's question is "what do I screen", and "nothing" must never become "clear".
 *
 * The `self` injection is load-bearing: fault-tolerance interceptors only fire through the CDI
 * proxy, so calling [nameWithResilience] on `this` would bypass all three annotations. Mirrors
 * [SanctionsScreeningAdapter].
 */
@ApplicationScoped
class PartyNameAdapter(@RestClient private val client: PartyServiceClient) : PartyNamePort {

    @Inject
    lateinit var self: PartyNameAdapter

    // Deliberately broad, as in SanctionsScreeningAdapter: the caller must not have to enumerate
    // which failure types mean "no name".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun screeningName(partyId: UUID): String {
        val name = try {
            self.nameWithResilience(partyId)
        } catch (ex: Exception) {
            throw PartyNameUnavailableException(partyId, ex)
        }
        return name ?: throw PartyNameUnavailableException(partyId)
    }

    /**
     * `legalName` is the authoritative name and the one sanctions lists are matched against;
     * `tradingName` is used only where a party carries no legal name at all. Null when the party
     * has neither.
     */
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(5_000)
    open suspend fun nameWithResilience(partyId: UUID): String? {
        val party = try {
            client.getParty(partyId.toString()).awaitSuspending()
        } catch (ex: WebApplicationException) {
            // Recovered INSIDE the guarded method: a 404 is an answer, not a fault, and must not be
            // retried or counted towards opening the breaker for every other conversion.
            if (ex.response?.status == HTTP_NOT_FOUND) return null
            throw ex
        }
        return party.legalName?.trim()?.takeIf { it.isNotEmpty() }
            ?: party.tradingName?.trim()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
