// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.contract

import com.openbank.copilot.application.CopilotChatService
import com.openbank.copilot.domain.AI_DISCLOSURE
import io.mockk.coEvery
import io.mockk.mockkClass
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Real-HTTP proof that `POST /api/v1/copilot/chat/stream` carries the AI-generated marker as response
 * headers on every outcome (EU AI Act Art. 50(1)) and leaves the event body untouched. The use case is
 * replaced per test so each outcome the service can produce is driven through the real resource.
 */
@QuarkusTest
class CopilotStreamAiMarkerIT {

    private fun streamWith(chunks: List<String>): Response {
        val useCase = mockkClass(CopilotChatService::class)
        coEvery { useCase.handleStream(any(), any(), any(), any()) } coAnswers {
            val onChunk = arg<suspend (String) -> Unit>(3)
            chunks.forEach { onChunk(it) }
        }
        QuarkusMock.installMockForType(useCase, CopilotChatService::class.java)
        return given().contentType(ContentType.JSON).body(mapOf("message" to "hello"))
            .`when`().post("/api/v1/copilot/chat/stream")
    }

    private fun assertMarked(response: Response) {
        response.then().statusCode(200)
        assertThat(response.header("X-AI-Generated")).isEqualTo("true")
        val raw = response.header("X-AI-Disclosure")
        assertThat(raw).isNotNull().matches("[\\x20-\\x7E]+")
        assertThat(URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)).isEqualTo(AI_DISCLOSURE)
    }

    @Test
    @TestSecurity(user = "copilot-stream-customer")
    fun `normal reply stream is marked and its body is unchanged`() {
        val response = streamWith(listOf("Ahoj", ", svete"))
        assertMarked(response)
        assertThat(response.body.asString()).contains("Ahoj").contains(", svete").doesNotContain("AI-Disclosure")
    }

    @Test
    @TestSecurity(user = "copilot-stream-customer")
    fun `refusal stream is marked`() {
        assertMarked(streamWith(listOf("Tuto žádost nemohu zpracovat.")))
    }

    @Test
    @TestSecurity(user = "copilot-stream-customer")
    fun `disabled stream is marked`() {
        // No mock: the default test profile really has the capability off, so this is the real service.
        val response = given().contentType(ContentType.JSON).body(mapOf("message" to "hello"))
            .`when`().post("/api/v1/copilot/chat/stream")
        assertMarked(response)
        assertThat(response.body.asString()).contains("Asistent je momentálně vypnutý.")
    }

    @Test
    @TestSecurity(user = "copilot-stream-customer")
    fun `degraded error fallback stream is marked`() {
        assertMarked(streamWith(listOf("Omlouvám se, odpověď se nepodařilo vytvořit.")))
    }
}
