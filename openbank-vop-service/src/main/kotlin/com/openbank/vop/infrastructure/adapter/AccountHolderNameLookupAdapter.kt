// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.adapter

import com.openbank.vop.application.port.out.AccountHolderNameLookupPort
import com.openbank.vop.application.port.out.NameLookupUnavailableException
import com.openbank.vop.infrastructure.client.AccountServiceClient
import com.openbank.vop.infrastructure.client.PartyServiceClient
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resolves IBAN → account-holder name across two hops (ADR-0171 §4).
 *
 * Distinguishes two failure kinds, and the distinction is the point:
 * - **A 404 on either hop is not a failure** — it means we hold no name for this IBAN. It maps to
 *   `null`, which the use case turns into NO_DATA.
 * - **Anything else** (timeout, 5xx, circuit open) is a genuine lookup failure and throws
 *   [NameLookupUnavailableException], which the use case *also* turns into NO_DATA — but with a
 *   different reason and a warning log. VoP fails open (ADR-0171 §3); it never holds a payment
 *   the way the sanctions gate does, and it never silently reports MATCH.
 *
 * The `self`-injection is not a style quirk: SmallRye Fault Tolerance interceptors only fire
 * through the CDI proxy, so calling `lookupWithResilience(...)` directly on `this` would silently
 * bypass every `@Retry` / `@Timeout` / `@CircuitBreaker` on it. Mirrors
 * `SanctionsScreeningAdapter`.
 */
@ApplicationScoped
open class AccountHolderNameLookupAdapter(
    @RestClient private val accounts: AccountServiceClient,
    @RestClient private val parties: PartyServiceClient,
) : AccountHolderNameLookupPort {

    @Inject
    lateinit var self: AccountHolderNameLookupAdapter

    override fun lookupHolderName(iban: String): Uni<String?> = self.lookupWithResilience(iban)
        .onFailure().transform { failure ->
            if (failure is NameLookupUnavailableException) failure else NameLookupUnavailableException(failure)
        }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @Timeout(RESILIENCE_TIMEOUT_MS)
    open fun lookupWithResilience(iban: String): Uni<String?> = accounts.getAccountByIban(iban)
        .flatMap { account ->
            val partyId = account.partyId
            if (partyId.isNullOrBlank()) {
                // The account exists but carries no party link — no name is resolvable.
                Uni.createFrom().item(null as String?)
            } else {
                parties.getParty(partyId).map { party ->
                    // legal_name is the authoritative name; trading_name is a presentation alias a
                    // payer may legitimately have used, so fall back to it rather than answering
                    // NO_DATA for a party whose only recorded name is its trading name.
                    party.legalName?.takeIf { it.isNotBlank() }
                        ?: party.tradingName?.takeIf { it.isNotBlank() }
                }
            }
        }
        // Recover INSIDE the guarded method, not outside it. A 404 is an ordinary answer — "we
        // hold no name for this IBAN" — but it reaches the caller as a failed Uni, and SmallRye
        // Fault Tolerance judges this method by the Uni it returns. Recovering outside left
        // @Retry replaying every unknown IBAN three times and @CircuitBreaker counting each as a
        // failure, so a handful of them opened the breaker and turned *genuine* verifications
        // into NO_DATA for 10s. Mapped here, a 404 is simply a success carrying null.
        .onFailure(::isNotFound).recoverWithItem(null as String?)

    /** A 404 on either hop means "we hold no name for this IBAN" — a NO_DATA answer, not an error. */
    private fun isNotFound(failure: Throwable): Boolean = failure is WebApplicationException &&
        failure.response?.status == Response.Status.NOT_FOUND.statusCode

    private companion object {
        const val RESILIENCE_TIMEOUT_MS = 3_000L
    }
}
