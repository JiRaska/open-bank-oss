// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.usecase.CzechDomesticIban
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The provider side of customer-edge's bound-create pact (#16000 part 4). Every id is a literal
 * that must equal `CustomerEdgeDomesticBoundCreatePactConsumerTest`'s; a `QuarkusTestProfile`
 * loads in another classloader, so nothing here may be randomised.
 */
object DomesticBoundCreatePactFixture {
    const val BOUND_STATE = "a RESERVED spend reservation for a delegated domestic payment is projected"
    const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
    const val EDGE_USER = "service-account-openbank-edge"
    const val EDGE_SUB = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"

    val RESERVATION_ID: UUID = UUID.fromString("7a1c0000-0000-4000-8000-000000016000")
    val DELEGATION_ID: UUID = UUID.fromString("7a1c0000-0000-4000-8000-000000016001")
    val ACTING_PARTY_ID: UUID = UUID.fromString("7a1c0000-0000-4000-8000-000000016002")
    val DEBTOR_ACCOUNT_ID: UUID = UUID.fromString("7a1c0000-0000-4000-8000-000000016003")
    val GRANTOR_PARTY_ID: UUID = UUID.fromString("7a1c0000-0000-4000-8000-000000016004")
    private val EVENT_ID: UUID = UUID.fromString("7a1c0000-0000-4000-8000-000000016005")
    const val IDEMPOTENCY_KEY = "pact-bound-create-16000"
    val DEBTOR_IBAN: String = checkNotNull(CzechDomesticIban.fromAccountNumber("1234567890", "0800"))
    private val AT: Instant = Instant.parse("2026-11-14T00:00:00Z")

    /** RESERVED, matching the pact's delegation, grantee, debtor, 1500.00 CZK and key. Fixed event id: re-applying is a no-op. */
    fun snapshot() = DelegatedSpendReservationSnapshot(
        eventId = EVENT_ID,
        reservationId = RESERVATION_ID,
        delegationId = DELEGATION_ID,
        grantorPartyId = GRANTOR_PARTY_ID,
        granteePartyId = ACTING_PARTY_ID,
        resourceType = "ACCOUNT",
        resourceId = DEBTOR_ACCOUNT_ID,
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        idempotencyKeyHash = DelegatedSpendReservationSnapshot.hashIdempotencyKey(IDEMPOTENCY_KEY),
        operationType = "DOMESTIC_PAYMENT",
        reservationState = DelegatedSpendReservationState.RESERVED,
        reservationVersion = 1,
        schemaVersion = 1,
        aggregateType = "DelegationSpendReservation",
        sourceService = "delegation-service",
        createdAt = AT,
        settledAt = null,
        occurredAt = AT,
    )
}

/** Enables [BoundCreatePactAccountLookup] for the provider replay classes only. */
class BoundCreatePactProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(BoundCreatePactAccountLookup::class.java)
}

/** The pact's debtor IBAN is the grantor's pact account; every other IBAN is external. */
@Alternative
@ApplicationScoped
class BoundCreatePactAccountLookup : AccountLookupPort {
    override suspend fun findPartyByIban(iban: String): UUID? = if (iban ==
        DomesticBoundCreatePactFixture.DEBTOR_IBAN
    ) {
        DomesticBoundCreatePactFixture.GRANTOR_PARTY_ID
    } else {
        null
    }

    override suspend fun findPartyByAccountId(accountId: UUID): UUID? = if (accountId ==
        DomesticBoundCreatePactFixture.DEBTOR_ACCOUNT_ID
    ) {
        DomesticBoundCreatePactFixture.GRANTOR_PARTY_ID
    } else {
        null
    }

    override suspend fun findAccountIdByIban(iban: String): UUID? = if (iban ==
        DomesticBoundCreatePactFixture.DEBTOR_IBAN
    ) {
        DomesticBoundCreatePactFixture.DEBTOR_ACCOUNT_ID
    } else {
        null
    }
}
