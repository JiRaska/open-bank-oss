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
 * proxy, so calling [namesWithResilience] on `this` would bypass all three annotations. Mirrors
 * [SanctionsScreeningAdapter].
 */
@ApplicationScoped
class PartyNameAdapter(@RestClient private val client: PartyServiceClient) : PartyNamePort {

    @Inject
    lateinit var self: PartyNameAdapter

    // Deliberately broad, as in SanctionsScreeningAdapter: the caller must not have to enumerate
    // which failure types mean "no name".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun screeningNames(partyId: UUID): List<String> {
        val names = try {
            self.namesWithResilience(partyId)
        } catch (ex: Exception) {
            throw PartyNameUnavailableException(partyId, ex)
        }
        if (names.isEmpty()) throw PartyNameUnavailableException(partyId)
        return names
    }

    /**
     * Every non-blank, distinct name on the party record, `legalName` first (for a natural person
     * that field is the full name), then `tradingName`. BOTH, never one or the other: a listed
     * trading name behind a clean legal name must reach the screen. Empty when the party has
     * neither, or is not held at all.
     */
    @CircuitBreaker(
        requestVolumeThreshold = CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = CB_FAILURE_RATIO,
        delay = CB_DELAY_MS,
        successThreshold = CB_SUCCESS_THRESHOLD,
    )
    @Retry(
        maxRetries = RETRY_MAX_RETRIES,
        delay = RETRY_DELAY_MS,
        jitter = RETRY_JITTER_MS,
        retryOn = [Exception::class],
    )
    @Timeout(TIMEOUT_MS)
    open suspend fun namesWithResilience(partyId: UUID): List<String> {
        val party = try {
            client.getParty(partyId.toString()).awaitSuspending()
        } catch (ex: WebApplicationException) {
            // Recovered INSIDE the guarded method: a 404 is an answer, not a fault, and must not be
            // retried or counted towards opening the breaker for every other conversion.
            if (ex.response?.status == HTTP_NOT_FOUND) return emptyList()
            throw ex
        }
        return listOfNotNull(party.legalName, party.tradingName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404

        // Resilience tuning mirrors SanctionsScreeningAdapter (ADR-0032 §D).
        const val CB_REQUEST_VOLUME_THRESHOLD = 4
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 10_000L
        const val CB_SUCCESS_THRESHOLD = 2
        const val RETRY_MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 300L
        const val RETRY_JITTER_MS = 150L
        const val TIMEOUT_MS = 5_000L
    }
}
