// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.integration

import com.openbank.cardissuance.infrastructure.persistence.entity.CardEntity
import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Card-side AC7 (issue #2990): an ACTIVE delegated grant lets the delegate through
 * the guard, a revocation takes it away — through the real projection and REST check,
 * never via a synchronous call to delegation-service.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(CardDelegationProjectionIT.InMemoryDelegationChannel::class)
class CardDelegationProjectionIT {

    class InMemoryDelegationChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("delegation-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var cardRepositoryImpl: com.openbank.cardissuance.infrastructure.persistence.repository.CardRepositoryImpl

    private val holderParty: UUID = UUID.randomUUID()
    private val delegateParty: UUID = UUID.randomUUID()
    private val grantId: UUID = UUID.randomUUID()

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `grant activation opens card access and revocation closes it`(): Unit = runBlocking {
        val cardId = seedCard()
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        source.runOnVertxContext(true)

        source.send(delegationEvent("DelegationActivated", cardId, lifecycleRevision = 1))
        assertThat(awaitAuthorized(cardId, expected = true)).isTrue()

        source.send(delegationEvent("DelegationRevoked", cardId, lifecycleRevision = 2))
        assertThat(awaitAuthorized(cardId, expected = false)).isTrue()

        source.send(delegationEvent("DelegationReinstated", cardId, lifecycleRevision = 1))
        assertThat(awaitAuthorized(cardId, expected = false))
            .describedAs("a delayed older opening must not resurrect revoked card authority")
            .isTrue()
    }

    private fun seedCard(): UUID {
        val cardId = UUID.randomUUID()
        val entity = CardEntity().apply {
            id = cardId
            idempotencyKey = "it-${UUID.randomUUID()}"
            partyId = holderParty
            accountId = UUID.randomUUID()
            productCode = "DEBIT_BASIC"
            cardType = "VIRTUAL"
            network = "VISA"
            maskedPan = "411111******1111"
            cardholderName = "Delegation IT"
            embossedName = "DELEGATION IT"
            expiryDate = LocalDate.now().plusYears(3)
            status = "ACTIVE"
            dailyLimitMinorUnits = 100_000
            monthlyLimitMinorUnits = 500_000
            currency = "CZK"
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        VertxContextSupport.subscribeAndAwait<Unit> {
            Panache.withTransaction { cardRepositoryImpl.persist(entity).replaceWith(Unit) }
        }
        return cardId
    }

    private suspend fun awaitAuthorized(cardId: UUID, expected: Boolean): Boolean {
        repeat(40) {
            val authorized: Boolean = (
                Given { this } When {
                    get("/api/v1/cards/$cardId/delegation/check?partyId=$delegateParty&intent=VIEW")
                } Then {
                    statusCode(200)
                }
                ).extract().path("authorized")
            if (authorized == expected) return true
            delay(250)
        }
        return false
    }

    private fun delegationEvent(type: String, cardId: UUID, lifecycleRevision: Long): String =
        """
        {
          "eventType": "$type",
          "aggregateId": "$grantId",
          "grantorPartyId": "$holderParty",
          "granteePartyId": "$delegateParty",
          "resourceType": "CARD",
          "resourceId": "$cardId",
          "capabilities": ["CARD_VIEW"],
          "validFrom": "2026-01-01T00:00:00Z",
          "occurredAt": "2026-08-01T12:00:00Z"
          ,"lifecycleRevision": $lifecycleRevision
        }
        """.trimIndent()
}
