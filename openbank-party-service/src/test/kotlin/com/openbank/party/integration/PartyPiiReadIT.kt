// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.PiiMask
import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
class PartyPiiReadIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var mapper: ObjectMapper

    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var kafkaBootstrapServers: String

    private lateinit var id: UUID
    private lateinit var email: String
    private lateinit var legalName: String

    @BeforeEach
    fun seedParty() {
        id = UUID.randomUUID()
        email = "hidden.$id@openbank.test"
        legalName = "Synthetic Directory $id"
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO parties (id, party_id, party_type, legal_name, email, phone, date_of_birth, tax_id)
                VALUES (nextval('parties_seq'), ?, 'INDIVIDUAL', ?, ?, '12345678', '1980-01-03', 'TST980002')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, legalName)
                statement.setString(3, email)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    @Test
    @TestSecurity(user = "synthetic-viewer", roles = ["ROLE_VIEWER"])
    fun `viewer sees masked directory fields and cannot search or reveal`() {
        val listing = Given { queryParam("size", 100) } When {
            get("/api/v1/parties")
        } Then {
            statusCode(200)
            body("items.id", hasItem(id.toString()))
        }
        assertThat(listing.extract().body().asString()).doesNotContain(email, legalName)
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            header("Cache-Control", "no-store")
            body("legalName", equalTo("S. D. ${id.toString().first()}."))
            body("phone", equalTo("***"))
            body("piiMasked", equalTo(true))
            body("piiRevealAllowed", equalTo(false))
            body("address", nullValue())
        }
        Given { queryParam("q", legalName) } When { get("/api/v1/parties/search") } Then { statusCode(403) }
        Given {
            contentType("application/json")
            body("""{"reason":"KYC_REVIEW"}""")
        } When { post("/api/v1/parties/$id/pii-reveal") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "synthetic-kyc", roles = ["ROLE_KYC"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = STAFF_SUBJECT),
            Claim(key = "preferred_username", value = "synthetic-kyc"),
        ],
    )
    fun `interactive kyc reveal requires a reason and publishes to the restricted audit topic`() {
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            body("piiRevealAllowed", equalTo(true))
            body("piiMasked", equalTo(true))
        }
        Given {
            contentType("application/json")
            body("{}")
        } When { post("/api/v1/parties/$id/pii-reveal") } Then { statusCode(400) }
        Given {
            contentType("application/json")
            body("""{"reason":"KYC_REVIEW"}""")
        } When { post("/api/v1/parties/$id/pii-reveal") } Then {
            statusCode(200)
            header("Cache-Control", "no-store")
            body("email", equalTo(email))
            body("phone", equalTo("12345678"))
        }
        val record = readAuditRecord()
        val row = mapper.readTree(record.value())
        assertThat(row.properties().map { it.key }).containsExactlyInAnyOrder(
            "eventId",
            "eventType",
            "partyId",
            "sourceService",
            "actorId",
            "actorType",
            "reason",
            "occurredAt",
        )
        assertThat(record.headers().lastHeader("ce-id").value().toString(Charsets.UTF_8))
            .isEqualTo(row.path("eventId").asText())
        assertThat(record.headers().lastHeader("ce-type").value().toString(Charsets.UTF_8))
            .isEqualTo("PARTY_PII_REVEALED")
        assertThat(row.path("partyId").asText()).isEqualTo(id.toString())
        assertThat(row.path("actorId").asText()).isEqualTo(STAFF_SUBJECT)
        assertThat(row.path("reason").asText()).isEqualTo("KYC_REVIEW")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "67939611-037b-4536-a52a-4adc3be2be83"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `verified customer edge retains its own profile contract but has no staff reveal privilege`() {
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            body("email", equalTo(email))
            body("phone", equalTo("12345678"))
        }
        Given { queryParam("q", email) } When { get("/api/v1/parties/search") } Then {
            statusCode(200)
            body("data[0].id", equalTo(id.toString()))
        }
        Given {
            contentType("application/json")
            body("""{"reason":"KYC_REVIEW"}""")
        } When { post("/api/v1/parties/$id/pii-reveal") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "synthetic-operator", roles = ["ROLE_OPERATOR"])
    fun `operator search matches names without using restricted lookup fields`() {
        Given { queryParam("q", legalName) } When { get("/api/v1/parties/search") } Then {
            statusCode(200)
            body("data[0].id", equalTo(id.toString()))
            body("data[0].dateOfBirth", nullValue())
        }
        for (protectedQuery in listOf(email, "12345678", "TST980002", "$legalName 1980-01-03")) {
            Given { queryParam("q", protectedQuery) } When { get("/api/v1/parties/search") } Then {
                statusCode(200)
                body("data.size()", equalTo(0))
            }
        }
    }

    @Test
    @TestSecurity(user = "synthetic-operator", roles = ["ROLE_OPERATOR"])
    fun `operator contact update returns the masked profile`() {
        Given {
            contentType("application/json")
            body("""{"legalName":"Synthetic Updated"}""")
        } When { patch("/api/v1/parties/$id") } Then {
            statusCode(200)
            body("legalName", equalTo("Synthetic Updated"))
            body("email", equalTo(PiiMask.email(email)))
            body("piiMasked", equalTo(true))
        }
    }

    private fun readAuditRecord(): ConsumerRecord<String, String> {
        val properties = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaBootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "party-pii-it-$id",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        KafkaConsumer(properties, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf("openbank.party.audit"))
            repeat(20) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == id.toString() }?.let { return it }
            }
        }
        error("No disclosure audit record for synthetic party $id")
    }

    companion object {
        private const val STAFF_SUBJECT = "8d7bd8e9-1511-4b12-98b4-9e9e01b3afab"
    }
}
