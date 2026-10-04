// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.CnbPolicyRateDocument
import com.openbank.fx.application.port.out.CnbPolicyRateFeed
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.RestClientBuilder
import java.net.URI

/**
 * Resilient fetch of the three ČNB policy-rate history files, with the same fault-tolerance
 * posture as [CnbRateProviderAdapter]. Self-injection routes the call through the CDI proxy so the
 * MicroProfile Fault Tolerance interceptors fire.
 */
@ApplicationScoped
class CnbPolicyRateFeedAdapter(
    @ConfigProperty(name = "openbank.cnb.policy-rates.repo-url") private val repoUrl: String,
    @ConfigProperty(name = "openbank.cnb.policy-rates.discount-url") private val discountUrl: String,
    @ConfigProperty(name = "openbank.cnb.policy-rates.lombard-url") private val lombardUrl: String,
) : CnbPolicyRateFeed {

    @Inject
    lateinit var self: CnbPolicyRateFeedAdapter

    private val clients = mutableMapOf<String, CnbPolicyRateFeedClient>()

    override suspend fun fetch(instrument: CnbPolicyInstrument): CnbPolicyRateDocument {
        val url = urlOf(instrument)
        return CnbPolicyRateDocument(url, self.fetchWithResilience(url))
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 500, jitter = 200, retryOn = [Exception::class])
    @Timeout(FEED_TIMEOUT_MILLIS)
    open suspend fun fetchWithResilience(url: String): ByteArray = client(url).fetch().awaitSuspending()

    private fun urlOf(instrument: CnbPolicyInstrument): String = when (instrument) {
        CnbPolicyInstrument.REPO_2W -> repoUrl
        CnbPolicyInstrument.DISCOUNT -> discountUrl
        CnbPolicyInstrument.LOMBARD -> lombardUrl
        else -> throw IllegalArgumentException("$instrument has no machine-readable feed")
    }

    @Synchronized
    private fun client(url: String): CnbPolicyRateFeedClient = clients.getOrPut(url) {
        RestClientBuilder.newBuilder().baseUri(URI.create(url)).build(CnbPolicyRateFeedClient::class.java)
    }

    private companion object {
        const val FEED_TIMEOUT_MILLIS = 8_000L
    }
}
