// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** Real bearer-token verification without Keycloak or TestSecurity identities. */
@QuarkusTest
@TestProfile(DrOfflineAuthenticationIT.OfflineProfile::class)
class DrOfflineAuthenticationIT {
    class OfflineProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(DrOfflineAuthenticationResource::class.java))

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.oidc.enabled" to "true",
            "quarkus.config.locations" to generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("openbank-infra/gitops/dr-restore-templates/ledger-dr-check.properties") }
                .first { Files.isRegularFile(it) }.toUri().toString(),
        )
    }

    @ConfigProperty(name = "dr.test.viewer-token")
    lateinit var viewerToken: String

    @ConfigProperty(name = "dr.test.expired-token")
    lateinit var expiredToken: String

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `database reader cannot mutate journal rows or assume the owner identity`() {
        dataSource.connection.use { connection ->
            assertThat(connection.metaData.userName).isEqualTo("ledger_dr_check")
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT has_table_privilege(current_user, 'journal_entries', 'SELECT'), " +
                        "has_table_privilege(current_user, 'journal_entries', 'INSERT,UPDATE,DELETE,TRUNCATE')",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBoolean(1)).isTrue()
                    assertThat(rows.getBoolean(2)).isFalse()
                }
                for (sql in listOf(
                    "DELETE FROM journal_entries WHERE false",
                    "UPDATE journal_entries SET id = id WHERE false",
                    "SET ROLE openbank",
                )) {
                    val failure = assertThrows(SQLException::class.java) { statement.execute(sql) }
                    assertThat(failure.sqlState).isEqualTo("42501")
                }
            }
        }
    }

    @Test
    fun `DR readiness requires the database but no live broker or cache`() {
        val response = given().get("/q/health/ready")
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(200)
        assertThat(response.jsonPath().getString("status")).isEqualTo("UP")
        assertThat(response.jsonPath().getList<String>("checks.name"))
            .anyMatch { it.contains("PostgreSQL") || it.contains("Database") }
    }

    @Test
    fun `temporary viewer can read trial balance`() {
        given().auth().oauth2(viewerToken)
            .queryParam("fiscalYear", 2026)
            .get("/api/v1/ledger/close/trial-balance")
            .then().statusCode(200)
    }

    @Test
    fun `anonymous and expired credentials cannot read restored data`() {
        given().queryParam("fiscalYear", 2026)
            .get("/api/v1/ledger/close/trial-balance").then().statusCode(401)
        given().auth().oauth2(expiredToken).queryParam("fiscalYear", 2026)
            .get("/api/v1/ledger/close/trial-balance").then().statusCode(401)
    }

    @Test
    fun `a forged signature cannot read restored data`() {
        val parts = viewerToken.split('.')
        val replacement = if (parts[2].first() == 'A') 'B' else 'A'
        val forged = "${parts[0]}.${parts[1]}.$replacement${parts[2].drop(1)}"
        given().auth().oauth2(forged).queryParam("fiscalYear", 2026)
            .get("/api/v1/ledger/close/trial-balance").then().statusCode(401)
    }

    @Test
    fun `temporary viewer cannot create a year close`() {
        given().auth().oauth2(viewerToken).contentType("application/json")
            .post("/api/v1/ledger/close/2026").then().statusCode(403)
    }
}

class DrOfflineAuthenticationResource : QuarkusTestResourceLifecycleManager {
    private val infrastructure = PostgresTestResource()

    override fun start(): Map<String, String> {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun token(expiry: Long): String {
            val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
            val claims = """
                {"iss":"urn:openbank:dr-check","aud":"openbank-dr-check",
                 "sub":"dr-viewer","groups":["ROLE_VIEWER"],"iat":${expiry - 3600},"exp":$expiry}
            """.trimIndent()
            val input = "$header.${encoder.encodeToString(claims.toByteArray())}"
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keys.private)
                update(input.toByteArray())
            }.sign()
            return "$input.${encoder.encodeToString(signature)}"
        }
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        val database = infrastructure.start()
        val readerPassword = UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(
            database.getValue("quarkus.datasource.jdbc.url"),
            database.getValue("quarkus.datasource.username"),
            database.getValue("quarkus.datasource.password"),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE ROLE ledger_dr_check LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE " +
                        "NOREPLICATION NOBYPASSRLS PASSWORD '$readerPassword'",
                )
                statement.execute("GRANT pg_read_all_data TO ledger_dr_check")
            }
        }
        return database + mapOf(
            // Initialize this fresh fixture as its owner; only application pools use the reader.
            // The real restore workload disables Flyway and never receives owner credentials.
            "quarkus.flyway.username" to database.getValue("quarkus.datasource.username"),
            "quarkus.flyway.password" to database.getValue("quarkus.datasource.password"),
            "quarkus.datasource.username" to "ledger_dr_check",
            "quarkus.datasource.password" to readerPassword,
            "quarkus.oidc.enabled" to "true",
            "quarkus.oidc.tenant-enabled" to "true",
            "quarkus.oidc.public-key" to publicKey,
            "quarkus.oidc.auth-server-url" to "",
            "quarkus.oidc.discovery-enabled" to "false",
            "quarkus.oidc.token.issuer" to "urn:openbank:dr-check",
            "quarkus.oidc.token.audience" to "openbank-dr-check",
            "quarkus.oidc.roles.role-claim-path" to "groups",
            "quarkus.oidc-client.client-enabled" to "false",
            "quarkus.scheduler.enabled" to "false",
            "kafka.bootstrap.servers" to "127.0.0.1:1",
            "mp.messaging.outgoing.ledger-events-out.bootstrap.servers" to "127.0.0.1:1",
            "quarkus.redis.hosts" to "redis://127.0.0.1:1",
            "openbank.outbox.dispatch-enabled" to "false",
            "dr.test.viewer-token" to token(Instant.now().epochSecond + 300),
            "dr.test.expired-token" to token(Instant.now().epochSecond - 300),
        )
    }

    override fun stop() = infrastructure.stop()
}
