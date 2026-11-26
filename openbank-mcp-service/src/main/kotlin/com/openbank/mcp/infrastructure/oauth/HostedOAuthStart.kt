// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.FieldProtector
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.AuthorizationRequest
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import com.openbank.mcp.infrastructure.oauth.PgHostedOAuthTransactions.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Validates the outer request then starts its dedicated bank-owned PAR session. */
internal class HostedOAuthStart(
    private val transactions: PgHostedOAuthTransactions,
    private val bank: HostedBankParClient,
    private val protector: FieldProtector,
    private val mapper: ObjectMapper,
    private val enabled: Set<Vendor> = emptySet(),
    private val registrations: HostedOAuthAdmission.Registrations = HostedOAuthAdmission.Registrations(),
) {
    private val random = SecureRandom()

    /**
     * [browserBinding] is the value for the facade's first-party __Host- cookie. The HTTP caller
     * must set Path=/, Secure, HttpOnly and SameSite=Lax before sending [bankAuthorize].
     * Never log either value. A lifetime of at most the pending callback lifetime is sufficient.
     */
    class Pending(val browserBinding: String, val bankAuthorize: URI)

    suspend fun authorize(request: AuthorizationRequest, browserBinding: String?): Pending? {
        val vendorRequest = HostedOAuthAdmission.admit(request, enabled, registrations) ?: return null
        val binding = browserBinding?.takeIf(BINDING_FORMAT::matches) ?: newHandle()
        val bankState = newHandle()
        val bankVerifier = newHandle()
        val stateDigest = digest(bankState)
        val bindingDigest = digest(binding)
        val data = mapper.createObjectNode()
            .put("vendorClientId", vendorRequest.registration.clientId)
            .put("vendorCallback", vendorRequest.registration.callback)
            .put("vendorState", vendorRequest.state)
            .put("vendorChallenge", vendorRequest.challenge)
            .put("bankVerifier", bankVerifier)
        val aad = associatedData(Kind.BANK_CALLBACK, stateDigest, vendorRequest.vendor, bindingDigest)
        val value = withContext(Dispatchers.IO) { protector.encrypt(mapper.writeValueAsBytes(data), aad) }
        val inserted = transactions.put(
            Kind.BANK_CALLBACK,
            stateDigest,
            vendorRequest.vendor,
            bindingDigest,
            value.toByteArray(StandardCharsets.US_ASCII),
        )
        check(inserted) { "Could not persist bank callback state" }
        log.infof(
            "Hosted OAuth stage=bank_par_requested vendor=%s flow=%s",
            vendorRequest.vendor,
            correlation(stateDigest),
        )
        val redirect = bank.push(vendorRequest.vendor, bankState, digest(bankVerifier))
        log.infof(
            "Hosted OAuth stage=bank_par_accepted vendor=%s flow=%s",
            vendorRequest.vendor,
            correlation(stateDigest),
        )
        return Pending(binding, redirect)
    }

    private fun newHandle(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(HANDLE_BYTES).also(random::nextBytes),
    )

    companion object {
        const val HANDLE_BYTES = 32
        const val MAX_VENDOR_STATE_LENGTH = 1024
        private const val LOG_FLOW_LENGTH = 16
        private val log = Logger.getLogger(HostedOAuthStart::class.java)
        private val BINDING_FORMAT = Regex("[A-Za-z0-9_-]{43}")

        fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII)),
        )

        fun correlation(stateDigest: String): String = digest("hosted-oauth-log:$stateDigest").take(LOG_FLOW_LENGTH)

        fun associatedData(kind: Kind, handleDigest: String, vendor: Vendor, bindingDigest: String): ByteArray =
            "openbank:hosted-oauth:v1:${kind.name}:$handleDigest:${vendor.name}:$bindingDigest"
                .toByteArray(StandardCharsets.US_ASCII)
    }
}
