// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerMaterial
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real OIDC discovery/signature validation, HTTP adapter, assignment lookup and custom PDP call. */
@QuarkusTest
@TestProfile(ContextVerifiedIdentityProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextVerifiedIdentityIT {
    @Inject
    lateinit var policy: IdentityRecordingPolicy

    @BeforeEach
    fun resetPolicy() = policy.queries.clear()

    @Test
    fun `verified interactive bearer reaches custom PDP as human`() {
        val fixture = seed("context-human")
        val token = ListenerMaterial.bearer(listOf("ROLE_COMPLIANCE"), "context-human", "openbank-admin-ui")
        assertThat(request(fixture, token)).isEqualTo(200)
        val query = policy.queries.single()
        assertThat(query.principal.id).isEqualTo("context-human")
        assertThat(query.principal.serviceAccount).isFalse()
        assertThat(query.principal.clientId).isNull()
        assertThat(query.attributes["assignmentVerified"]).isEqualTo(true)
        assertThat(query.attributes["rootScopeVerified"]).isEqualTo(true)
    }

    @Test
    fun `assigned machine with same role keeps bound client and is denied`() {
        val fixture = seed("service-account-context-fixture")
        val token = ListenerMaterial.bearer(listOf("ROLE_COMPLIANCE"), "machine-subject", "context-fixture", true)
        assertThat(request(fixture, token)).isEqualTo(403)
        val query = policy.queries.single()
        assertThat(query.principal.id).isEqualTo("service-account-context-fixture")
        assertThat(query.principal.roles).contains("ROLE_COMPLIANCE")
        assertThat(query.principal.serviceAccount).isTrue()
        assertThat(query.principal.clientId).isEqualTo("context-fixture")
        assertThat(query.attributes["assignmentVerified"]).isEqualTo(true)
    }

    @Test
    fun `certificate and asserted headers cannot replace bearer`() {
        val fixture = seed("context-human")
        assertThat(request(fixture, null)).isEqualTo(401)
        assertThat(policy.queries).isEmpty()
    }

    @Test
    fun `forged bearer signature is rejected before custom PDP`() {
        val fixture = seed("context-human")
        val token = ListenerMaterial.bearer(listOf("ROLE_COMPLIANCE"), "context-human", "openbank-admin-ui")
        val parts = token.split('.')
        val replacement = if (parts[2].first() == 'A') 'B' else 'A'
        val forged = "${parts[0]}.${parts[1]}.$replacement${parts[2].drop(1)}"
        assertThat(request(fixture, forged)).isEqualTo(401)
        assertThat(policy.queries).isEmpty()
    }

    private fun request(fixture: Fixture, token: String?): Int {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(
            URI("https://localhost:$port/api/v1/context/complaints/${fixture.reference}"),
        )
            .timeout(Duration.ofSeconds(20))
            .header("X-Investigation-Case-Id", fixture.caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .header("X-User", "context-human")
            .header("X-Forwarded-Client-Cert", "Subject=CN=context-human")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .GET().build()
        return HttpClient.newBuilder().sslContext(ListenerMaterial.sslContext(true)).build()
            .send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun seed(actor: String): Fixture {
        val fixture = Fixture("CMP-${UUID.randomUUID()}", "case-${UUID.randomUUID()}")
        val root = "complaint:${fixture.reference}"
        val now = Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC)
        execute(
            """INSERT INTO context_case_assignments
                (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref, valid_from, valid_to, created_at)
                VALUES (?, 'openbank-cz', ?, ?, 'PAYMENT_COMPLAINT', ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            actor,
            fixture.caseId,
            root,
            now.minusHours(1),
            now.plusHours(1),
            now,
        )
        execute(
            """INSERT INTO context_nodes
                (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system,
                 source_ref, display_label, classification, valid_from, recorded_at, source_version)
                VALUES (?, ?, 'openbank-cz', 1, 'COMPLAINT', 'COMPLAINT', 'test', ?, 'Fixture', 'INTERNAL', ?, ?, 1)"""
                .trimIndent(),
            UUID.randomUUID(),
            root,
            root,
            now.minusMinutes(1),
            now,
        )
        return fixture
    }

    private fun execute(sql: String, vararg values: Any) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private data class Fixture(val reference: String, val caseId: String)
}

class ContextVerifiedIdentityProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(IdentityRecordingPolicy::class.java)
    override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(ListenerMaterial::class.java))
    override fun getConfigOverrides() = mapOf(
        "quarkus.oidc.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "true",
        "quarkus.oidc.devservices.enabled" to "false",
        "quarkus.keycloak.devservices.enabled" to "false",
        "quarkus.oidc.token.principal-claim" to "preferred_username",
        "quarkus.http.ssl.client-auth" to "required",
        "quarkus.http.auth.permission.context-jwt.paths" to "/api/v1/context/*",
        "quarkus.http.auth.permission.context-jwt.policy" to "authenticated",
        "quarkus.http.auth.permission.context-jwt.auth-mechanism" to "bearer",
    ).flatMap { (key, value) -> listOf(key to value, "%test.$key" to value) }.toMap()
}

/** Selected only by this profile; other HTTP suites retain their own policy fixture. */
@Alternative
@ApplicationScoped
class IdentityRecordingPolicy : PolicyDecisionPoint {
    val queries = CopyOnWriteArrayList<AuthzQuery>()
    override suspend fun allow(query: AuthzQuery): AuthzDecision {
        queries.add(query)
        return AuthzDecision(
            allow = query.action.startsWith("context.") && !query.principal.serviceAccount,
            policyVersion = "verified-identity-fixture",
        )
    }
}
