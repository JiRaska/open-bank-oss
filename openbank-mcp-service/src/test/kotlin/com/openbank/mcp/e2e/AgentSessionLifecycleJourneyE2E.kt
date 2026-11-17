// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The staff OBO agent-session journey over the real HTTP surface (ADR-0224 D2): an operator issues
 * a session whose role ceiling is clipped to the roles they actually hold, binds it to the token
 * minted for it exactly once, reads it back, and revokes it — while the boundaries hold: another
 * operator's session is not theirs to read, a second bind is a conflict, and revocation takes
 * effect immediately.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "session is issued, bound, used and retired" story, so a wiring regression that deregisters a
 * route (#3371), lets a caller elevate past their own roles, or double-binds a token fails here
 * even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_e2e")])
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentSessionLifecycleJourneyE2E {

    private val path = "/api/v1/mcp/sessions"
    private lateinit var sessionId: String

    @Test
    @Order(1)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `1 - an operator issues a session clipped to the roles they hold`() {
        val created = Given {
            contentType("application/json")
            body("""{"clientId":"admin-ui","roleCeiling":["ROLE_OPERATOR","ROLE_ADMIN"],"purpose":"e2e"}""")
        } When {
            post(path)
        } Then {
            statusCode(201)
            body("subject", equalTo("jane.operator"))
            body("roleCeiling", containsString("ROLE_OPERATOR"))
            body("roleCeiling", org.hamcrest.Matchers.not(containsString("ROLE_ADMIN")))
            body("revoked", equalTo(false))
        } Extract { path<String>("id") }
        sessionId = created
    }

    @Test
    @Order(2)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `2 - the owner reads the session back and a stranger cannot`() {
        Given { this } When { get("$path/$sessionId") } Then {
            statusCode(200)
            body("id", equalTo(sessionId))
            body("clientId", equalTo("admin-ui"))
        }

        Given { this } When { get("$path/00000000-0000-0000-0000-000000000099") } Then { statusCode(404) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "john.operator", roles = ["ROLE_OPERATOR"])
    fun `3 - another operator's session is not theirs to read or revoke`() {
        Given { this } When { get("$path/$sessionId") } Then {
            statusCode(403)
            body("error", equalTo("not your session"))
        }
        Given { this } When { delete("$path/$sessionId") } Then { statusCode(403) }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `4 - the session binds one token jti and refuses a second bind`() {
        Given {
            contentType("application/json")
            body("""{"jti":"e2e-jti-1"}""")
        } When {
            patch("$path/$sessionId/bind")
        } Then { statusCode(200) }

        Given {
            contentType("application/json")
            body("""{"jti":"e2e-jti-2"}""")
        } When {
            patch("$path/$sessionId/bind")
        } Then {
            statusCode(409)
            body("error", equalTo("session already bound"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `5 - a role ceiling past the caller's own roles is refused`() {
        Given {
            contentType("application/json")
            body("""{"clientId":"admin-ui","roleCeiling":["ROLE_ADMIN"],"purpose":"elevation"}""")
        } When {
            post(path)
        } Then {
            statusCode(403)
            body("error", containsString("subset of your own roles"))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "jane.operator", roles = ["ROLE_OPERATOR"])
    fun `6 - revocation takes effect immediately and is answered once`() {
        Given { this } When { delete("$path/$sessionId") } Then { statusCode(204) }
        Given { this } When { delete("$path/$sessionId") } Then { statusCode(404) }

        Given { this } When { get("$path/$sessionId") } Then {
            statusCode(200)
            body("revoked", equalTo(true))
        }
    }
}
