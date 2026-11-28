// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedBankCodeExchange.VerifiedConnection
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import jakarta.ws.rs.core.Response.Status.BAD_REQUEST
import jakarta.ws.rs.core.Response.Status.OK
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The vault must validate issuer, audience, signed client ID, DPoP thumbprint and active consent before storing. */
internal fun interface HostedBankGrantVault {
    suspend fun storeApproved(vendor: Vendor, dpopThumbprint: String, tokenResponse: JsonNode): VerifiedConnection?
}

internal fun interface HostedBankRefreshExchange {
    suspend fun refresh(vendor: Vendor, expectedDpopThumbprint: String, refreshToken: String): JsonNode?
}

internal class HostedBankTokenClient(
    private val http: HttpClient,
    private val mapper: ObjectMapper,
    private val signer: HostedBankTransitProof,
    private val vault: HostedBankGrantVault,
) : HostedBankCodeExchange,
    HostedBankRefreshExchange {
    init {
        require(http.followRedirects() == HttpClient.Redirect.NEVER)
    }

    override suspend fun exchange(vendor: Vendor, bankCode: String, bankVerifier: String): VerifiedConnection? {
        val fields = mapOf(
            "grant_type" to "authorization_code",
            "redirect_uri" to HostedOAuthAdmission.BANK_CALLBACK,
            "code" to bankCode,
            "code_verifier" to bankVerifier,
        )
        val (token, thumbprint) = request(vendor, fields) ?: return null
        return vault.storeApproved(vendor, thumbprint, token)?.takeIf { it.vendor == vendor }
    }

    override suspend fun refresh(vendor: Vendor, expectedDpopThumbprint: String, refreshToken: String): JsonNode? =
        request(
            vendor,
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
            expectedDpopThumbprint,
        )?.first

    private suspend fun request(
        vendor: Vendor,
        fields: Map<String, String>,
        expectedDpopThumbprint: String? = null,
    ): Pair<JsonNode, String>? {
        val proof = signer.signToken(vendor)
        if (expectedDpopThumbprint != null && proof.dpopThumbprint != expectedDpopThumbprint) return null
        val first = send(vendor, fields, proof)
        if (first.statusCode() == OK.statusCode) return tokenResult(first, proof)
        val nonce = first.headers().allValues("DPoP-Nonce").singleOrNull()?.takeIf(NONCE::matches)
        if (nonce == null || first.statusCode() != BAD_REQUEST.statusCode) return null
        if (responseJson(first)?.path("error")?.textValue() != "use_dpop_nonce") return null
        val retry = signer.signToken(vendor, nonce)
        if (retry.dpopThumbprint != proof.dpopThumbprint) return null
        val second = send(vendor, fields, retry)
        return second.takeIf { it.statusCode() == OK.statusCode }?.let { tokenResult(it, retry) }
    }

    private fun tokenResult(
        response: HttpResponse<ByteArray>,
        proof: HostedBankParProof.Signed,
    ): Pair<JsonNode, String>? {
        val token = responseJson(response)?.takeIf { it.isObject } ?: return null
        if (token.path("token_type").textValue()?.equals("DPoP", ignoreCase = true) != true) return null
        return token to proof.dpopThumbprint
    }

    private fun responseJson(response: HttpResponse<ByteArray>): JsonNode? = try {
        mapper.readTree(response.body())
    } catch (_: JacksonException) {
        null
    }

    private suspend fun send(
        vendor: Vendor,
        fields: Map<String, String>,
        proof: HostedBankParProof.Signed,
    ): HttpResponse<ByteArray> {
        val form = encodeHostedOAuthForm(
            mapOf(
                "client_id" to vendor.bankClientId,
                "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion" to proof.clientAssertion,
            ) + fields,
        )
        val request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("DPoP", proof.dpopProof)
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val handler = HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES)
        return withTimeout(TIMEOUT.toMillis()) { http.sendAsync(request, handler).await() }
    }

    private companion object {
        val TOKEN_ENDPOINT = URI.create(
            "https://kc.open-bank.tech/realms/openbank-customers/protocol/openid-connect/token",
        )
        val TIMEOUT: Duration = Duration.ofSeconds(5)
        const val MAX_RESPONSE_BYTES = 16_384L
        val NONCE = Regex("[A-Za-z0-9._~-]{1,512}")
    }
}
