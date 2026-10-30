// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.libs.resilience.ResilienceProfile
import com.openbank.libs.resilience.ResilienceProfiles
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Instant
import java.util.UUID

/** One catalog read: a body, a confirmed 404, or another 4xx the catalog answered deliberately. */
sealed interface CatalogRead<out T> {
    data class Answered<T>(val body: T) : CatalogRead<T>
    data object NotFound : CatalogRead<Nothing>

    /** 400/401/403/…: not an outage, so never retried, and never mistaken for "not found". */
    data class Refused(val status: Int) : CatalogRead<Nothing>
}

/**
 * The two product-catalog reads account opening makes, under the shared `read` resilience profile
 * (ADR-0321 D1). Both are idempotent GETs, so the retry is safe; a 4xx is a deliberate answer and is
 * RETURNED (not thrown) so the retry and the circuit breaker only ever see real faults — a product
 * that does not exist must not trip the breaker for every other product.
 */
@ApplicationScoped
class ResilientProductCatalogReads {

    @Inject
    @RestClient
    lateinit var products: ProductCatalogClient

    @Inject
    @RestClient
    lateinit var revisions: ProductCatalogRevisionClient

    @ResilienceProfile(ResilienceProfiles.READ)
    @Timeout(ResilienceProfiles.Read.TIMEOUT_MS)
    @Retry(
        maxRetries = ResilienceProfiles.Read.MAX_RETRIES,
        delay = ResilienceProfiles.Read.DELAY_MS,
        jitter = ResilienceProfiles.Read.JITTER_MS,
        retryOn = [Exception::class],
    )
    @CircuitBreaker(
        requestVolumeThreshold = ResilienceProfiles.Read.CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = ResilienceProfiles.Read.CB_FAILURE_RATIO,
        delay = ResilienceProfiles.Read.CB_DELAY_MS,
        successThreshold = ResilienceProfiles.Read.CB_SUCCESS_THRESHOLD,
    )
    suspend fun product(productId: UUID): CatalogRead<ProductCatalogResponse> =
        read { products.getById(productId.toString()).awaitSuspending() }

    @ResilienceProfile(ResilienceProfiles.READ)
    @Timeout(ResilienceProfiles.Read.TIMEOUT_MS)
    @Retry(
        maxRetries = ResilienceProfiles.Read.MAX_RETRIES,
        delay = ResilienceProfiles.Read.DELAY_MS,
        jitter = ResilienceProfiles.Read.JITTER_MS,
        retryOn = [Exception::class],
    )
    @CircuitBreaker(
        requestVolumeThreshold = ResilienceProfiles.Read.CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = ResilienceProfiles.Read.CB_FAILURE_RATIO,
        delay = ResilienceProfiles.Read.CB_DELAY_MS,
        successThreshold = ResilienceProfiles.Read.CB_SUCCESS_THRESHOLD,
    )
    suspend fun publishedRevision(productId: UUID, effectiveAt: Instant): CatalogRead<CatalogRevisionResponse> = read {
        revisions.getPublishedRevisionForLegacyProduct(
            productId.toString(),
            effectiveAt.toString(),
        ).awaitSuspending()
    }

    private inline fun <T> read(call: () -> T): CatalogRead<T> = try {
        CatalogRead.Answered(call())
    } catch (e: WebApplicationException) {
        val status = e.response?.status ?: throw e
        when {
            status == NOT_FOUND -> CatalogRead.NotFound
            status in CLIENT_ERRORS -> CatalogRead.Refused(status)
            else -> throw e
        }
    }

    private companion object {
        const val NOT_FOUND = 404
        val CLIENT_ERRORS = 400..499
    }
}
