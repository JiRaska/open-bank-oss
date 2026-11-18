// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Optional

/** ADR-0383 D4: every way a misconfigured realm could widen the exchanged token is a refusal. */
class KeycloakCustomerTokenExchangeTest {
    private val mapper = ObjectMapper()
    private val endpoint: CustomerTokenEndpointClient = mockk()
    private val subject = "c0ffee00-0000-0000-0000-000000000001"

    private fun exchanger(secret: String? = "test-secret") =
        KeycloakCustomerTokenExchange(endpoint, mapper, "openbank-mcp-service", Optional.ofNullable(secret))

    private fun jwt(claims: Map<String, Any?>): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"none\"}".toByteArray()) + "." +
            enc.encodeToString(mapper.writeValueAsBytes(claims)) + ".sig"
    }

    private fun answer(token: String?, expiresIn: Any? = 60): JsonNode = mapper.valueToTree(
        buildMap {
            token?.let { put("access_token", it) }
            expiresIn?.let { put("expires_in", it) }
            put("token_type", "Bearer")
        },
    )

    private fun respond(node: JsonNode) {
        every { endpoint.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } returns node
    }

    private val good = mapOf("sub" to subject, "aud" to listOf("openbank-balance-service"))

    @Test
    fun `a well-formed answer for the customer and audience is accepted`() {
        val token = jwt(good)
        respond(answer(token))
        assertThat(exchanger().exchange("subject-token", subject, DownstreamAudience.BALANCE)).isEqualTo(token)
    }

    @Test
    fun `a token living longer than 60 seconds is refused`() {
        respond(answer(jwt(good), expiresIn = 300))
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java).hasMessageContaining("lifetime")
    }

    @Test
    fun `a token with no stated lifetime is refused`() {
        respond(answer(jwt(good), expiresIn = null))
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java)
    }

    @Test
    fun `a token for another principal is refused`() {
        respond(answer(jwt(good + ("sub" to "service-account-openbank-mcp"))))
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java).hasMessageContaining("customer")
    }

    @Test
    fun `a token for another audience is refused`() {
        respond(answer(jwt(good + ("aud" to "openbank-ledger-service"))))
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java).hasMessageContaining("openbank-balance-service")
    }

    @Test
    fun `no token or an unreadable token is refused`() {
        respond(answer(null))
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java)
        respond(answer("not-a-jwt"))
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java)
    }

    @Test
    fun `an endpoint failure is a refusal`() {
        every { endpoint.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            jakarta.ws.rs.WebApplicationException(400)
        assertThatThrownBy { exchanger().exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java)
    }

    @Test
    fun `with no client secret configured nothing is exchanged`() {
        assertThatThrownBy { exchanger(secret = null).exchange("s", subject, DownstreamAudience.BALANCE) }
            .isInstanceOf(TokenExchangeRefused::class.java).hasMessageContaining("secret")
    }
}
