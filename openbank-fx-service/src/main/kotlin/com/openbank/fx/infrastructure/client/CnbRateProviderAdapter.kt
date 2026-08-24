// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.CnbRateProvider
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Resilient adapter over [CnbFeedClient]. Mirrors the fault-tolerance posture of the outbox
 * dispatchers (`@Retry`/`@Timeout`/`@CircuitBreaker`) so a flaky or slow ČNB feed degrades the
 * daily ingestion gracefully rather than hanging the scheduler. Self-injection routes the call
 * through the CDI proxy so the MicroProfile Fault Tolerance interceptors actually fire.
 */
@ApplicationScoped
class CnbRateProviderAdapter(@RestClient private val client: CnbFeedClient) : CnbRateProvider {

    @Inject
    lateinit var self: CnbRateProviderAdapter

    override suspend fun fetchFixing(date: LocalDate?): String = self.fetchWithResilience(date?.format(FEED_DATE))

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 500, jitter = 200, retryOn = [Exception::class])
    @Timeout(8_000)
    open suspend fun fetchWithResilience(date: String?): String = client.daily(date).awaitSuspending()

    companion object {
        private val FEED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
