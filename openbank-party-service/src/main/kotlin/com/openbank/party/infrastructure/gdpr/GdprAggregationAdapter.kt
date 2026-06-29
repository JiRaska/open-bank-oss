// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.gdpr

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.out.GdprAggregationPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.UUID

/**
 * GDPR Art. 15 aggregation adapter — fetches PII from kyc-service and card-issuance-service
 * on a best-effort basis. A downstream being unavailable must never block the export; null/empty
 * results are acceptable and annotated in the response so the DPO knows to follow up manually.
 *
 * HTTP is made synchronously via java.net.http.HttpClient inside [withContext(Dispatchers.IO)]
 * so the Vert.x event loop is never blocked. No MicroProfile REST client is used to avoid the
 * ClientHeadersFactory classpath issue (issue #247).
 */
@ApplicationScoped
class GdprAggregationAdapter : GdprAggregationPort {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.gdpr.kyc-service-url")
    var kycServiceUrl: Optional<String> = Optional.empty()

    @ConfigProperty(name = "openbank.gdpr.card-service-url")
    var cardServiceUrl: Optional<String> = Optional.empty()

    private val log = Logger.getLogger(GdprAggregationAdapter::class.java)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS.toLong()))
            .build()
    }

    override suspend fun fetchKycData(partyId: UUID): Map<String, Any?>? {
        val base = kycServiceUrl.orElse("").takeIf { it.isNotBlank() } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$base/api/v1/kyc/cases/party/$partyId"))
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS.toLong()))
                    .GET()
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == HTTP_OK) {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(resp.body(), Map::class.java) as Map<String, Any?>
                } else {
                    log.warnf("gdpr.aggregate.kyc status=%d partyId=%s", resp.statusCode(), partyId)
                    null
                }
            }.onFailure { e ->
                log.warnf(e, "gdpr.aggregate.kyc FAILED partyId=%s", partyId)
            }.getOrNull()
        }
    }

    override suspend fun fetchCardData(partyId: UUID): List<Map<String, Any?>> {
        val base = cardServiceUrl.orElse("").takeIf { it.isNotBlank() } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$base/api/v1/cards/party/$partyId"))
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS.toLong()))
                    .GET()
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == HTTP_OK) {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(resp.body(), List::class.java) as List<Map<String, Any?>>
                } else {
                    log.warnf("gdpr.aggregate.cards status=%d partyId=%s", resp.statusCode(), partyId)
                    emptyList()
                }
            }.onFailure { e ->
                log.warnf(e, "gdpr.aggregate.cards FAILED partyId=%s", partyId)
            }.getOrElse { emptyList() }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 1
        private const val READ_TIMEOUT_SECONDS = 3
        private const val HTTP_OK = 200
    }
}
