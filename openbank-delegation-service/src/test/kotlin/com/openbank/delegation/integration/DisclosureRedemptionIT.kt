// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.it.PostgresTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Proves the public two-factor exchange and max-view race against real Postgres and HTTP. */
@QuarkusTest
@QuarkusTestResource(DisclosureRedemptionIT.Dependencies::class)
@QuarkusTestResource(PostgresTestResource::class)
class DisclosureRedemptionIT {
    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "customer", roles = ["ROLE_API"])
    fun `only one racing request receives a view-once sealed PDF`() {
        val grantor = UUID.randomUUID()
        val disclosureId = seedReadyDisclosure(grantor)
        connector.sink<String>("notification-requests-out").clear()
        val issued = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .body(
                """{"recipient":"recipient@example.test","expiresAt":"${Instant.now().plusSeconds(
                    600,
                )}","maxViews":1}""",
            )
            .post("/api/v1/disclosures/$disclosureId/redemptions")
            .then().statusCode(201).extract()

        val notification = connector.sink<String>("notification-requests-out").received().single().payload
        val otp = mapper.readTree(notification).path("variables").path("code").asText()
        val magicToken = issued.path<String>("magicToken")
        val ticket = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""{"magicToken":"$magicToken","otp":"$otp"}""")
            .post("/api/v1/public/disclosures/verify")
            .then().statusCode(200).extract().path<String>("accessTicket")

        val pool = Executors.newFixedThreadPool(2)
        val statuses = try {
            (1..2).map {
                pool.submit<Int> {
                    RestAssured.given().contentType(ContentType.JSON)
                        .body("""{"accessTicket":"$ticket"}""")
                        .post("/api/v1/public/disclosures/content").statusCode
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 404)
        assertThat(redemptionState(disclosureId)).isEqualTo("EXHAUSTED:1:true")
    }

    @Test
    @TestSecurity(user = "customer", roles = ["ROLE_API"])
    fun `five wrong OTP attempts lock the redemption and erase verification secrets`() {
        val grantor = UUID.randomUUID()
        val disclosureId = seedReadyDisclosure(grantor)
        connector.sink<String>("notification-requests-out").clear()
        val magicToken = RestAssured.given()
            .contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", grantor.toString())
            .body(
                """{"recipient":"recipient@example.test","expiresAt":"${Instant.now().plusSeconds(
                    600,
                )}","maxViews":1}""",
            )
            .post("/api/v1/disclosures/$disclosureId/redemptions")
            .then().statusCode(201).extract().path<String>("magicToken")
        val issuedOtp = mapper.readTree(
            connector.sink<String>("notification-requests-out").received().single().payload,
        ).path("variables").path("code").asText()
        val wrongOtp = if (issuedOtp == "000000") "000001" else "000000"

        repeat(5) {
            RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""{"magicToken":"$magicToken","otp":"$wrongOtp"}""")
                .post("/api/v1/public/disclosures/verify")
                .then().statusCode(404)
        }

        assertThat(redemptionState(disclosureId)).isEqualTo("LOCKED:0:true")
        assertThat(activeVerificationSecrets(disclosureId)).isEqualTo("true:true:true")
    }

    private fun seedReadyDisclosure(grantor: UUID): UUID {
        val grantId = UUID.randomUUID()
        val disclosureId = UUID.randomUUID()
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                     valid_from, valid_to, status, created_at, updated_at)
                VALUES (?, ?, ?, 'DOCUMENT', ?, 'SOLO', now() - interval '1 day',
                        now() + interval '30 days', 'ACTIVE', now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, grantId)
                statement.setObject(2, grantor)
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO delegation_disclosures
                    (id, request_id, delegation_id, grantor_party_id, source_document_id, status,
                     snapshot_id, source_sha256, snapshot_sha256, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'READY', ?, ?, ?, ?, now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, disclosureId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, grantId)
                statement.setObject(4, grantor)
                statement.setObject(5, UUID.randomUUID())
                statement.setObject(6, UUID.randomUUID())
                statement.setString(7, SOURCE_SHA)
                statement.setString(8, PDF_SHA)
                statement.setLong(9, PDF.size.toLong())
                statement.executeUpdate()
            }
        }
        return disclosureId
    }

    private fun redemptionState(disclosureId: UUID): String = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT status, views, access_ticket_hash IS NULL FROM disclosure_redemptions WHERE disclosure_id=?",
        ).use { statement ->
            statement.setObject(1, disclosureId)
            statement.executeQuery().use { rows ->
                rows.next()
                "${rows.getString(1)}:${rows.getInt(2)}:${rows.getBoolean(3)}"
            }
        }
    }

    private fun activeVerificationSecrets(disclosureId: UUID): String = jdbc().use { connection ->
        connection.prepareStatement(
            """
            SELECT magic_token_hash IS NULL, otp_salt IS NULL, otp_hash IS NULL
            FROM disclosure_redemptions WHERE disclosure_id=?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, disclosureId)
            statement.executeQuery().use { rows ->
                rows.next()
                "${rows.getBoolean(1)}:${rows.getBoolean(2)}:${rows.getBoolean(3)}"
            }
        }
    }

    private fun jdbc() = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    class Dependencies : QuarkusTestResourceLifecycleManager {
        private lateinit var server: HttpServer

        override fun start(): Map<String, String> {
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/api/v1/documents/disclosure-snapshots/") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/pdf")
                exchange.sendResponseHeaders(200, PDF.size.toLong())
                exchange.responseBody.use { it.write(PDF) }
            }
            server.start()
            return InMemoryConnector.switchIncomingChannelsToInMemory("disclosure-snapshot-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory(
                    "delegation-events-out",
                    "spend-reservation-state-out",
                    "approval-group-revisions-out",
                    "notification-requests-out",
                ) + mapOf("quarkus.rest-client.document-service.url" to "http://localhost:${server.address.port}")
        }

        override fun stop() {
            server.stop(0)
            InMemoryConnector.clear()
        }
    }

    private companion object {
        val PDF: ByteArray = "%PDF-1.7 sealed disclosure".toByteArray()
        val PDF_SHA: String = sha256(PDF)
        val SOURCE_SHA: String = "b".repeat(64)

        fun sha256(content: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }
}
