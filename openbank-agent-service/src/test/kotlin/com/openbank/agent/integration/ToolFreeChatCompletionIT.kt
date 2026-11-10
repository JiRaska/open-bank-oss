// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.application.AgentChatService
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(ToolFreeChatCompletionIT.NoExternalInfraProfile::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
class ToolFreeChatCompletionIT {
    class NoExternalInfraProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.flyway.migrate-at-start" to "false",
            "quarkus.oidc-client.early-tokens-acquisition" to "false",
            "quarkus.http.test-port" to "0",
            "quarkus.management.test-port" to "0",
        )
    }

    private lateinit var service: AgentChatService

    @BeforeEach
    fun installService() {
        service = mockk()
        QuarkusMock.installMockForType(service, AgentChatService::class.java)
    }

    @Test
    fun `incomplete tool-free evidence is 503 on the actual HTTP surface`() {
        coEvery { service.chat(any(), any(), any(), any()) } returns AgentChatService.ChatOutcome(
            reply = "partial explanation cut off mid",
            model = "fixture-model",
            toolCalls = emptyList(),
            unavailable = true,
        )

        val response = given().contentType(ContentType.JSON)
            .body("""{"messages":[{"role":"user","content":"Explain this test."}],"allowTools":false}""")
            .`when`().post("/agent/chat")
            .then().statusCode(503).extract().asString()

        assertThat(response).doesNotContain("partial explanation cut off mid")
    }

    @Test
    fun `complete tool-free evidence keeps the successful response and tool restriction`() {
        coEvery { service.chat(any(), any(), any(), any()) } returns AgentChatService.ChatOutcome(
            reply = "The retained test passed; individual assertions were not recorded.",
            model = "fixture-model",
            toolCalls = emptyList(),
        )

        given().contentType(ContentType.JSON)
            .body("""{"messages":[{"role":"user","content":"Explain this test."}],"allowTools":false}""")
            .`when`().post("/agent/chat")
            .then().statusCode(200)
            .body("toolsRestricted", equalTo(true))
            .body("toolCalls.size()", equalTo(0))
            .body("reply", equalTo("The retained test passed; individual assertions were not recorded."))
    }

    @Test
    fun `ordinary chat preserves the existing backend degradation response`() {
        coEvery { service.chat(any(), any(), any(), any()) } returns AgentChatService.ChatOutcome(
            reply = "The model backend is temporarily unavailable.",
            model = "fixture-model",
            toolCalls = emptyList(),
            unavailable = true,
        )

        given().contentType(ContentType.JSON)
            .body("""{"messages":[{"role":"user","content":"Explain this test."}]}""")
            .`when`().post("/agent/chat")
            .then().statusCode(200)
            .body("toolsRestricted", equalTo(false))
            .body("reply", equalTo("The model backend is temporarily unavailable."))
    }
}
