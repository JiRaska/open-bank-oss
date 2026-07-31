// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp

import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.util.UUID

/**
 * Real-DB lifecycle proof for ADR-0224 D2 (a Testcontainers Postgres): issue → bind → revoke,
 * with the merge-on-detached-@Id paths exercised against Hibernate Reactive for real — the exact
 * spot where a mocked repository hides the fleet's persist-vs-merge footgun (#1521).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpSessionLifecycleIT {

    @Inject
    lateinit var sessions: AgentSessionRepository

    // Direct repo calls from the test thread need a Vert.x context (reactive Panache);
    // the HTTP paths above run on the server side and don't.
    private fun <T> db(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private val jti = "it-jti-${UUID.randomUUID()}"
    private var sessionId: String? = null

    @Test
    @Order(1)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `issue bounds the ceiling to the caller's roles and persists the row`() {
        sessionId = given()
            .contentType(ContentType.JSON)
            .body("""{"clientId":"admin-ui","roleCeiling":["ROLE_OPERATOR","ROLE_ADMIN"],"purpose":"it"}""")
            .post("/api/v1/mcp/sessions")
            .then()
            .statusCode(201)
            .extract().path("id")

        assertThat(sessionId).isNotNull()
        val row = db { sessions.findById(UUID.fromString(sessionId)) }
        assertThat(row).isNotNull
        assertThat(row!!.subject).isEqualTo("jane.operator")
        assertThat(row.roleCeiling).isEqualTo("[\"ROLE_OPERATOR\"]")
        assertThat(row.jti).isNull()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `bind attaches the jti once and rejects a second bind`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"jti":"$jti"}""")
            .patch("/api/v1/mcp/sessions/$sessionId/bind")
            .then()
            .statusCode(200)

        given()
            .contentType(ContentType.JSON)
            .body("""{"jti":"other"}""")
            .patch("/api/v1/mcp/sessions/$sessionId/bind")
            .then()
            .statusCode(409)

        val active = db { sessions.findActiveByJti(jti, Instant.now()) }
        assertThat(active).isNotNull
        assertThat(active!!.subject).isEqualTo("jane.operator")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `revoke removes the row from the live lookup immediately`() {
        given()
            .delete("/api/v1/mcp/sessions/$sessionId")
            .then()
            .statusCode(204)

        assertThat(db { sessions.findActiveByJti(jti, Instant.now()) }).isNull()
    }
}
