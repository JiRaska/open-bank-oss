// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyQualifier

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class AuthorityHistoryIT {
    @Inject
    @AnyQualifier
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var assignments: AssignmentAdministrationService

    @Test
    fun `consumer group and replay policy resolve from the shipped application config`() {
        val config = ConfigProvider.getConfig()
        assertThat(config.getValue("mp.messaging.incoming.delegation-history-in.group.id", String::class.java))
            .isEqualTo("openbank-context-service-authority-history")
        assertThat(config.getValue("mp.messaging.incoming.delegation-history-in.auto.offset.reset", String::class.java))
            .isEqualTo("earliest")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `offered revision zero survives storage and unrelated spend events do not become authority`() {
        val id = UUID.randomUUID()
        val case = "offered-${UUID.randomUUID()}"
        grant(case, id)
        val source = connector.source<String>("delegation-history-in")
        source.runOnVertxContext(true)
        source.send(event(id, 0, "DelegationOffered"))
        source.send(event(id, 1, "SpendReserved"))
        source.send(event(id, 1, "DelegationActivated"))
        awaitRows(id, 2)
        request(id, case).then().statusCode(200)
            .body("observations.size()", equalTo(2))
            .body("observations[1].evidence.revision", equalTo(0))
            .body("observations[1].evidence.eventType", equalTo("DelegationOffered"))
            .body("actionAuthorization", equalTo("UNKNOWN"))
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `late authority evidence preserves known time and never asserts business action authorization`() {
        val id = UUID.randomUUID()
        val case = "history-${UUID.randomUUID()}"
        val assignment = grant(case, id)
        val source = connector.source<String>("delegation-history-in")
        source.runOnVertxContext(true)
        source.send(event(id, 1, "DelegationActivated"))
        source.send(event(id, 1, "DelegationActivated"))
        awaitRows(id, 1)
        val first = request(id, case).then().statusCode(200)
            .body("observations.size()", equalTo(1)).body("actionAuthorization", equalTo("UNKNOWN"))
            .body("observations[0].evidence.revision", equalTo(1)).extract().jsonPath()
        val knownAt = first.getString("observations[0].recordedAt")
        source.send(event(id, 2, "DelegationRevoked"))
        awaitRows(id, 2)

        request(id, case).then().statusCode(200).body("observations.size()", equalTo(2))
            .body("observations[0].evidence.eventType", equalTo("DelegationRevoked"))
        given().header("X-Investigation-Case-Id", case).header("X-Investigation-Purpose", PURPOSE)
            .queryParam("knownAt", knownAt).get("/api/v1/context/authorizations/$id")
            .then().statusCode(200).body("observations.size()", equalTo(1))
            .body("observations[0].evidence.eventType", equalTo("DelegationActivated"))
        request(UUID.randomUUID(), case).then().statusCode(403)

        onVertx { assignments.revoke(assignment, "revoker") }
        request(id, case).then().statusCode(403)
        assertThatThrownBy {
            connection().use {
                it.createStatement().executeUpdate("DELETE FROM context_authority_history WHERE delegation_id = '$id'")
            }
        }.hasMessageContaining("append-only")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `a legacy broad case assignment does not grant history root access`() {
        val case = "legacy-${UUID.randomUUID()}"
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(ACTOR, case, "PAYMENT_COMPLAINT", null, Instant.now().plusSeconds(3600)),
                "maker",
            )
        }
        onVertx { assignments.decide(proposal.id, true, "checker") }
        request(UUID.randomUUID(), case).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `unknown evidence stays empty and future snapshots are rejected`() {
        val id = UUID.randomUUID()
        val case = "empty-${UUID.randomUUID()}"
        grant(case, id)
        request(id, case).then().statusCode(200).body("observations.size()", equalTo(0))
            .body("actionAuthorization", equalTo("UNKNOWN"))
        given().header("X-Investigation-Case-Id", case).header("X-Investigation-Purpose", PURPOSE)
            .queryParam("effectiveAt", Instant.now().plusSeconds(3600).toString())
            .get("/api/v1/context/authorizations/$id").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "unprivileged", roles = ["ROLE_OPERATOR"])
    fun `operator cannot reach authority history`() {
        request(UUID.randomUUID(), "any-case").then().statusCode(403)
    }

    private fun grant(case: String, id: UUID): UUID {
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(ACTOR, case, PURPOSE, null, Instant.now().plusSeconds(3600), "delegation:$id"),
                "maker",
            )
        }
        val approved = onVertx { assignments.decide(proposal.id, true, "checker") }
        assertThat(approved.rootRef).isEqualTo("delegation:$id")
        return requireNotNull(approved.assignmentId)
    }

    private fun request(id: UUID, case: String) = given()
        .header("X-Investigation-Case-Id", case).header("X-Investigation-Purpose", PURPOSE)
        .get("/api/v1/context/authorizations/$id")

    private fun event(id: UUID, revision: Int, type: String): String =
        """{"eventType":"$type","aggregateId":"$id","lifecycleRevision":$revision,
            "grantorPartyId":"11111111-1111-1111-1111-111111111111",
            "granteePartyId":"22222222-2222-2222-2222-222222222222","resourceType":"ACCOUNT",
            "resourceId":"33333333-3333-3333-3333-333333333333","capabilities":["VIEW"],
            "approvalPolicy":"SOLO","validFrom":"2026-01-01T00:00:00Z","occurredAt":"2026-02-01T00:00:00Z"}"""

    private fun awaitRows(id: UUID, expected: Int) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (rows(id) == expected) return
            Thread.sleep(25)
        }
        assertThat(rows(id)).isEqualTo(expected)
    }

    private fun rows(id: UUID): Int = connection().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM context_authority_history WHERE delegation_id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use {
                it.next()
                it.getInt(1)
            }
        }
    }

    private fun connection() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val ACTOR = "history-analyst"
        const val PURPOSE = "AUTHORIZATION_REVIEW"
    }
}
