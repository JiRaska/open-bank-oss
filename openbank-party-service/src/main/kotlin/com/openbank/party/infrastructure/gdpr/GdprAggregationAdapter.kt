// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.gdpr

import com.openbank.party.application.port.out.GdprAggregationAuthException
import com.openbank.party.application.port.out.GdprAggregationPort
import com.openbank.party.infrastructure.client.CardServiceRestClient
import com.openbank.party.infrastructure.client.KycServiceRestClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * GDPR Art. 15 aggregation adapter — fetches PII from kyc-service and card-issuance-service.
 *
 * Both hops go through a MicroProfile REST client carrying an M2M bearer
 * ([io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter], oidc-client
 * `openbank-services`). The previous implementation built raw `java.net.http` requests with **no
 * Authorization header**, which both role-protected endpoints answered 401; the adapter treated
 * any non-200 as "no data", so every deployed Art. 15 export silently shipped without KYC and card
 * PII while still reading as successful. The old KDoc justified the raw client with "the
 * ClientHeadersFactory classpath issue (issue #247)" — #247 is an unrelated (and merged) admin-ui
 * dossier-status change, so that constraint never applied here.
 *
 * Failure handling is deliberately split:
 *  - 401/403 → [GdprAggregationAuthException]. We were *refused* data that exists; degrading to
 *    null would be indistinguishable from the subject genuinely having no case/cards.
 *  - 404 → null / empty list. The subject genuinely has no KYC case or no cards.
 *  - anything else (timeout, 5xx, DNS) → null / empty list, logged. A downstream outage must not
 *    block the data subject's request; the DPO follows up from the log.
 */
@ApplicationScoped
class GdprAggregationAdapter(
    @RestClient private val kycClient: KycServiceRestClient,
    @RestClient private val cardClient: CardServiceRestClient,
) : GdprAggregationPort {

    private val log = Logger.getLogger(GdprAggregationAdapter::class.java)

    override suspend fun fetchKycData(partyId: UUID): Map<String, Any?>? = kycClient.getCaseByParty(partyId)
        .onFailure().recoverWithUni(recover<Map<String, Any?>>(KYC, partyId, null))
        .awaitSuspending()

    override suspend fun fetchCardData(partyId: UUID): List<Map<String, Any?>> = cardClient.listByParty(partyId)
        .onFailure().recoverWithUni(recover(CARDS, partyId, emptyList()))
        .awaitSuspending()

    /**
     * Splits a downstream failure into "refused" and "absent or unavailable". Expressed as a
     * Mutiny recovery rather than a `catch` so the authz case propagates as a real failure on the
     * reactive chain instead of being reconstructed after the fact.
     */
    private fun <T> recover(service: String, partyId: UUID, fallback: T?): (Throwable) -> Uni<T> = { t ->
        val status = (t as? WebApplicationException)?.response?.status
        if (status == UNAUTHORIZED || status == FORBIDDEN) {
            log.errorf(
                "gdpr.aggregate.%s DENIED status=%d partyId=%s — export must not proceed",
                service,
                status,
                partyId,
            )
            Uni.createFrom().failure(GdprAggregationAuthException(service, status))
        } else {
            log.warnf(t, "gdpr.aggregate.%s degraded status=%s partyId=%s", service, status ?: "unreachable", partyId)
            Uni.createFrom().item { fallback }
        }
    }

    companion object {
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val KYC = "kyc-service"
        private const val CARDS = "card-issuance-service"
    }
}
