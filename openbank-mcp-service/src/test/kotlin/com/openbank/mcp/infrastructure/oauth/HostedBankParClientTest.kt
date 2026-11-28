// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

class HostedBankParClientTest {
    @Test
    fun `only bank client and pushed handle are in browser redirect`() {
        val output = runBlocking { client(201, 60).push(Vendor.CHATGPT, "bank-state", "bank-challenge") }
        assertThat(output.host).isEqualTo("kc.open-bank.tech")
        assertThat(output.path).isEqualTo("/realms/openbank-customers/protocol/openid-connect/auth")
        assertThat(output.query).isEqualTo(
            "client_id=mcp-bank-hosted-chatgpt&request_uri=urn:ietf:params:oauth:request_uri:ref",
        )
    }

    @Test
    fun `refused and overlong bank PAR do not create a redirect`() {
        for ((status, expiry) in listOf(302 to 60, 400 to 60, 201 to 61)) {
            val result = runCatching { runBlocking { client(status, expiry).push(Vendor.CLAUDE, "", "") } }
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    private fun client(status: Int, expiry: Int): HostedBankParClient {
        val body = """{"request_uri":"urn:ietf:params:oauth:request_uri:ref","expires_in":$expiry}""".toByteArray()
        val response = mockk<HttpResponse<ByteArray>>()
        every { response.statusCode() } returns status
        every { response.body() } returns body
        val http = mockk<HttpClient>()
        every { http.followRedirects() } returns HttpClient.Redirect.NEVER
        every { http.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)
        val proof = HostedBankParProof { _, _, _ ->
            HostedBankParProof.Signed("synthetic-assertion", "synthetic-proof", "synthetic-jkt")
        }
        return HostedBankParClient(http, ObjectMapper(), proof)
    }
}
