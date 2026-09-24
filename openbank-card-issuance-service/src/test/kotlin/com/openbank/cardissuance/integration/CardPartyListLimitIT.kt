// SPDX-License-Identifier: Apache-2.0
package com.openbank.cardissuance.integration

import com.openbank.cardissuance.infrastructure.persistence.entity.CardEntity
import com.openbank.cardissuance.infrastructure.persistence.repository.CardRepositoryImpl
import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The Customer 360 limit must be applied by the owning database, with the GDPR full list preserved. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CardPartyListLimitIT {
    @Inject
    lateinit var cards: CardRepositoryImpl

    @Test
    @TestSecurity(user = "card-list-reader", roles = ["ROLE_OPERATOR"])
    fun `party card list returns a bounded newest slice and keeps legacy full reads`() {
        val partyId = UUID.randomUUID()
        val oldest = seedCard(partyId, Instant.parse("2026-01-01T00:00:00Z"))
        seedCard(partyId, Instant.parse("2026-01-02T00:00:00Z"))
        val newest = seedCard(partyId, Instant.parse("2026-01-03T00:00:00Z"))

        val limited = Given {
            queryParam("limit", 1)
        } When {
            get("/api/v1/cards/party/$partyId")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(limited.jsonPath().getList<String>("id")).containsExactly(newest.toString())

        val legacy = Given { this } When {
            get("/api/v1/cards/party/$partyId")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(legacy.jsonPath().getList<String>("id")).hasSize(3).contains(oldest.toString())

        Given {
            queryParam("limit", 0)
        } When {
            get("/api/v1/cards/party/$partyId")
        } Then {
            statusCode(400)
        }
    }

    private fun seedCard(partyId: UUID, createdAt: Instant): UUID {
        val id = UUID.randomUUID()
        val entity = CardEntity().apply {
            this.id = id
            idempotencyKey = "graph-it-${UUID.randomUUID()}"
            this.partyId = partyId
            accountId = UUID.randomUUID()
            productCode = "DEBIT_BASIC"
            cardType = "VIRTUAL"
            network = "VISA"
            maskedPan = "411111******1111"
            cardholderName = "Graph IT"
            embossedName = "GRAPH IT"
            expiryDate = LocalDate.now().plusYears(3)
            status = "ACTIVE"
            dailyLimitMinorUnits = 100_000
            monthlyLimitMinorUnits = 500_000
            currency = "CZK"
            this.createdAt = createdAt
            updatedAt = createdAt
        }
        VertxContextSupport.subscribeAndAwait<Unit> {
            Panache.withTransaction { cards.persist(entity).replaceWith(Unit) }
        }
        return id
    }
}
