// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.mockk.coEvery
import io.mockk.mockk
import io.vertx.core.Vertx
import io.vertx.core.http.ClientAuth
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.SelfSignedCertificate
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class HostedBankMcpHttpUpstreamTest {
    @Test
    fun `dedicated mutual TLS preserves canonical resource and protocol across nonce retry`(): Unit = runBlocking {
        val vertx = Vertx.vertx()
        val bankTls = SelfSignedCertificate.create("localhost")
        val hostedTls = SelfSignedCertificate.create("dedicated-hosted-adapter")
        val calls = CopyOnWriteArrayList<Call>()
        val server = vertx.createHttpServer(
            HttpServerOptions()
                .setSsl(true)
                .setKeyCertOptions(bankTls.keyCertOptions())
                .setTrustOptions(hostedTls.trustOptions())
                .setClientAuth(ClientAuth.REQUIRED),
        ).requestHandler { answer(it, calls) }.listen(0, "127.0.0.1").toCompletionStage().await()
        try {
            val vendor = Vendor.CHATGPT
            val proof = mockk<HostedBankTransitProof>()
            coEvery { proof.signResource(vendor, "bank-token", null) } returns "first-proof"
            coEvery { proof.signResource(vendor, "bank-token", "nonce.value") } returns "nonce-proof"
            val mapper = ObjectMapper()
            val upstream = HostedBankMcpHttpUpstream.usingDedicatedIdentity(
                vertx,
                "localhost",
                server.actualPort(),
                hostedTls.keyCertOptions(),
                bankTls.trustOptions(),
                mapper,
                proof,
            )
            val tools = mapper.readTree("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            val notification = mapper.readTree("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            val list = upstream.dispatch(vendor, "bank-token", tools, PROTOCOL)
            val status = upstream.dispatch(vendor, "bank-token", notification, null)
            val refused = upstream.dispatch(vendor, "bank-token", tools, PROTOCOL)
            assertThat(list.status).isEqualTo(200)
            assertThat(list.body!!.path("result").path("tools").isArray).isTrue()
            assertThat(status.status).isEqualTo(204)
            assertThat(status.body).isNull()
            assertThat(refused.status).isEqualTo(502)
            assertThat(calls).hasSize(4)
            assertThat(calls.take(2).map(Call::proof)).containsExactly("first-proof", "nonce-proof")
            assertThat(calls.take(2)).allSatisfy { call ->
                assertThat(call.uri).isEqualTo(HostedOAuthAdmission.RESOURCE)
                assertThat(call.authorization).isEqualTo("DPoP bank-token")
                assertThat(call.accept).isEqualTo("application/json")
                assertThat(call.protocol).isEqualTo(PROTOCOL)
                assertThat(call.tlsIdentity).contains("dedicated-hosted-adapter")
            }
        } finally {
            server.close().toCompletionStage().await()
            vertx.close().toCompletionStage().await()
            hostedTls.delete()
            bankTls.delete()
        }
    }

    private fun answer(request: HttpServerRequest, calls: MutableList<Call>) {
        calls += Call(
            request.absoluteURI(),
            request.getHeader("Authorization"),
            request.getHeader("DPoP"),
            request.getHeader("Accept"),
            request.getHeader("MCP-Protocol-Version"),
            request.sslSession().peerPrincipal.name,
        )
        val response = request.response()
        when (calls.size) {
            1 -> response.setStatusCode(401)
                .putHeader("WWW-Authenticate", """DPoP error="use_dpop_nonce", error_description="nonce needed"""")
                .putHeader("DPoP-Nonce", "nonce.value")
                .end()
            2 -> response.putHeader("Content-Type", "application/json; charset=utf-8")
                .end("""{"result":{"tools":[]}}""")
            3 -> response.setStatusCode(204).end()
            else -> response.putHeader("Content-Type", "text/event-stream").end()
        }
    }

    private data class Call(
        val uri: String,
        val authorization: String?,
        val proof: String?,
        val accept: String?,
        val protocol: String?,
        val tlsIdentity: String,
    )

    private companion object {
        const val PROTOCOL = "2025-03-26"
    }
}
