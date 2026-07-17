// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.openbank.document.application.port.out.PartyInfo
import com.openbank.document.application.port.out.PartyLookupPort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * **Fail-open** [PartyLookupPort] adapter (mirrors [ProductCatalogAdapter]'s stance): an
 * unreachable party-service must never block signing — the caller degrades to a template render
 * without the customer's name/address rather than failing the ceremony.
 */
@ApplicationScoped
class PartyAdapter : PartyLookupPort {

    @Inject
    @RestClient
    lateinit var client: PartyClient

    private val log = Logger.getLogger(PartyAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findById(partyId: UUID): PartyInfo? = try {
        val resp = client.getById(partyId.toString()).awaitSuspending()
        PartyInfo(legalName = resp.legalName, formattedAddress = resp.address?.format())
    } catch (e: Exception) {
        log.warnf("party-service unavailable for %s; contract will omit customer details: %s", partyId, e.message)
        null
    }

    // Single-line "street, postal code city" — matches the template's plain-string
    // {{party.address}} placeholder (Article header), not a nested object. Skips whichever
    // parts a party record doesn't have yet (e.g. self-service onboarding that never collected
    // a full postal address) rather than emitting "null" or dangling punctuation.
    private fun PartyAddressClientResponse.format(): String? {
        val cityLine = listOfNotNull(postalCode?.takeIf { it.isNotBlank() }, city?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val parts = listOfNotNull(line1?.takeIf { it.isNotBlank() }, line2?.takeIf { it.isNotBlank() }, cityLine)
        return parts.joinToString(", ").takeIf { it.isNotBlank() }
    }
}
