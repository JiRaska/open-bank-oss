// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedBankMcpUpstream.Reply
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.vertx.core.Future
import io.vertx.core.MultiMap
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.VertxException
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpVersion
import io.vertx.core.http.RequestOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import jakarta.ws.rs.core.Response.Status.BAD_GATEWAY
import jakarta.ws.rs.core.Response.Status.GATEWAY_TIMEOUT
import jakarta.ws.rs.core.Response.Status.OK
import jakarta.ws.rs.core.Response.Status.UNAUTHORIZED
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI

/** Verifies the configured bank TLS peer and independently fixes the HTTP authority used by DPoP. */
internal class HostedBankMcpHttpUpstream private constructor(
    private val http: HttpClient,
    private val tlsPeer: String,
    private val tlsPort: Int,
    private val mapper: ObjectMapper,
    private val proof: HostedBankTransitProof,
) : HostedBankMcpUpstream {
    override suspend fun dispatch(
        vendor: Vendor,
        bankAccessToken: String,
        request: JsonNode,
        protocolVersion: String?,
    ): Reply = try {
        val original = post(vendor, bankAccessToken, request, protocolVersion, null)
        val nonce = original.resourceNonce()
        val result = if (nonce == null) original else post(vendor, bankAccessToken, request, protocolVersion, nonce)
        result.decode()
    } catch (_: IOException) {
        Reply(BAD_GATEWAY.statusCode, null)
    } catch (_: VertxException) {
        Reply(BAD_GATEWAY.statusCode, null)
    }

    private class WireReply(val status: Int, val headers: MultiMap, val body: Buffer)

    private suspend fun post(
        vendor: Vendor,
        accessToken: String,
        payload: JsonNode,
        protocolVersion: String?,
        nonce: String?,
    ): WireReply {
        val proofHeader = proof.signResource(vendor, accessToken, nonce)
        val options = RequestOptions()
            .setMethod(HttpMethod.POST)
            .setSsl(true)
            .setHost(tlsPeer)
            .setPort(tlsPort)
            .setURI(RESOURCE.rawPath)
            .setFollowRedirects(false)
            .putHeader("Host", RESOURCE.host)
            .putHeader("Authorization", "DPoP $accessToken")
            .putHeader("DPoP", proofHeader)
            .putHeader("Content-Type", JSON)
            .putHeader("Accept", JSON)
        if (protocolVersion != null) options.putHeader("MCP-Protocol-Version", protocolVersion)
        val bytes = Buffer.buffer(mapper.writeValueAsBytes(payload))
        return withTimeoutOrNull(TIMEOUT_MS) {
            val request = http.request(options).toCompletionStage().await().setTimeout(TIMEOUT_MS)
            request.send(bytes).compose { response ->
                response.boundedBody().map { WireReply(response.statusCode(), response.headers(), it) }
            }.toCompletionStage().await()
        } ?: WireReply(GATEWAY_TIMEOUT.statusCode, MultiMap.caseInsensitiveMultiMap(), Buffer.buffer())
    }

    private fun HttpClientResponse.boundedBody(): Future<Buffer> {
        val reply = Promise.promise<Buffer>()
        val body = Buffer.buffer()
        exceptionHandler { reply.tryFail(it) }
        handler { chunk ->
            if (chunk.length() > MAX_BODY_BYTES - body.length()) {
                reply.tryFail(VertxException("Bank MCP response exceeds configured limit"))
                request().reset()
            } else if (!reply.future().isComplete) {
                body.appendBuffer(chunk)
            }
        }
        endHandler { reply.tryComplete(body) }
        return reply.future()
    }

    private fun WireReply.resourceNonce(): String? {
        if (status != UNAUTHORIZED.statusCode) return null
        val challenge = headers.getAll("WWW-Authenticate").singleOrNull()
        if (challenge?.startsWith("DPoP ") != true || !NONCE_ERROR.containsMatchIn(challenge)) return null
        return headers.getAll("DPoP-Nonce").singleOrNull()?.takeIf(NONCE::matches)
    }

    private fun WireReply.decode(): Reply {
        if (status != OK.statusCode) return Reply(status, null)
        val contentType = headers.get("Content-Type").orEmpty().substringBefore(';').trim()
        if (!contentType.equals(JSON, ignoreCase = true)) return Reply(BAD_GATEWAY.statusCode, null)
        val document = try {
            mapper.readTree(body.bytes)
        } catch (_: JacksonException) {
            null
        }
        return if (document?.isObject == true) Reply(status, document) else Reply(BAD_GATEWAY.statusCode, null)
    }

    companion object {
        private val RESOURCE = URI.create(HostedOAuthAdmission.RESOURCE)
        private val NONCE = Regex("[A-Za-z0-9_.~-]{1,1024}")
        private val NONCE_ERROR = Regex("""(?:^|[ ,])error="use_dpop_nonce"(?:,|$)""")
        private const val JSON = "application/json"
        private const val TIMEOUT_MS = 5_000L
        private const val MAX_TCP_PORT = 65_535
        private const val MAX_BODY_BYTES = 512 * 1024

        /** Only the adapter client identity and the intended internal TLS peer's trust anchors are accepted here. */
        fun usingDedicatedIdentity(
            vertx: Vertx,
            tlsPeer: String,
            tlsPort: Int,
            identity: PemKeyCertOptions,
            trust: PemTrustOptions,
            mapper: ObjectMapper,
            proof: HostedBankTransitProof,
        ): HostedBankMcpHttpUpstream {
            require(tlsPeer.isNotBlank() && tlsPort in 1..MAX_TCP_PORT)
            val options = HttpClientOptions()
                .setSsl(true)
                .setTrustAll(false)
                .setVerifyHost(true)
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setKeyCertOptions(identity)
                .setTrustOptions(trust)
                .setConnectTimeout(TIMEOUT_MS.toInt())
            return HostedBankMcpHttpUpstream(vertx.createHttpClient(options), tlsPeer, tlsPort, mapper, proof)
        }
    }
}
