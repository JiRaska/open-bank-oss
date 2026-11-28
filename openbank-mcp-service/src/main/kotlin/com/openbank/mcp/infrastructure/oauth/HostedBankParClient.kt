// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import jakarta.ws.rs.core.Response.Status.CREATED
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Signs with the separately provisioned bank client and DPoP key for the selected vendor. */
internal fun interface HostedBankParProof {
    suspend fun sign(vendor: Vendor, issuer: URI, parEndpoint: URI): Signed

    class Signed(val clientAssertion: String, val dpopProof: String, val dpopThumbprint: String)
}

/** Fixed Keycloak endpoints and bank-owned inputs only. Vendor parameters are never forwarded. */
internal class HostedBankParClient(
    private val http: HttpClient,
    private val mapper: ObjectMapper,
    private val signer: HostedBankParProof,
) {
    init {
        require(http.followRedirects() == HttpClient.Redirect.NEVER)
    }

    suspend fun push(vendor: Vendor, bankState: String, bankPkceChallenge: String): URI {
        val proof = signer.sign(vendor, ISSUER, PAR)
        val form = encodeHostedOAuthForm(
            mapOf(
                "client_id" to vendor.bankClientId,
                "client_assertion_type" to ASSERTION_TYPE,
                "client_assertion" to proof.clientAssertion,
                "response_type" to "code",
                "redirect_uri" to HostedOAuthAdmission.BANK_CALLBACK,
                "resource" to HostedOAuthAdmission.BANK_RESOURCE,
                "scope" to HostedOAuthAdmission.BANK_SCOPE,
                "state" to bankState,
                "code_challenge_method" to "S256",
                "code_challenge" to bankPkceChallenge,
                "dpop_jkt" to proof.dpopThumbprint,
            ),
        )
        val request = HttpRequest.newBuilder(PAR)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("DPoP", proof.dpopProof)
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val bodyHandler = HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES)
        val response = withTimeout(TIMEOUT.toMillis()) { http.sendAsync(request, bodyHandler).await() }
        check(response.statusCode() == CREATED.statusCode) { "Bank PAR refused" }
        val parsed = try {
            mapper.readTree(response.body())
        } catch (_: JacksonException) {
            error("Malformed bank PAR response")
        }
        val requestUri = parsed.path("request_uri").takeIf { it.isTextual }?.textValue()
        val expiry = parsed.path("expires_in")
        check(requestUri != null && REQUEST_URI.matches(requestUri)) { "Invalid bank PAR request URI" }
        check(expiry.isIntegralNumber && expiry.canConvertToInt() && expiry.intValue() in 1..MAX_PAR_SECONDS) {
            "Invalid bank PAR expiry"
        }
        val query = encodeHostedOAuthForm(mapOf("client_id" to vendor.bankClientId, "request_uri" to requestUri))
        return URI.create("$AUTHORIZE?$query")
    }

    private companion object {
        val ISSUER = URI.create("https://kc.open-bank.tech/realms/openbank-customers")
        val PAR = URI.create("$ISSUER/protocol/openid-connect/ext/par/request")
        val AUTHORIZE = URI.create("$ISSUER/protocol/openid-connect/auth")
        val REQUEST_URI = Regex("urn:ietf:params:oauth:request_uri:[A-Za-z0-9._~-]{1,512}")
        val TIMEOUT = Duration.ofSeconds(5)
        const val ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        const val MAX_PAR_SECONDS = 60
        const val MAX_BODY_BYTES = 4_096L
    }
}

internal fun encodeHostedOAuthForm(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
