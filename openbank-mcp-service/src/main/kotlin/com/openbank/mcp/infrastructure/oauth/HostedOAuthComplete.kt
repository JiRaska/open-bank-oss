// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.FieldProtector
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Registration
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.HANDLE_BYTES
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.MAX_VENDOR_STATE_LENGTH
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.associatedData
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.correlation
import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.digest
import com.openbank.mcp.infrastructure.oauth.PgHostedOAuthTransactions.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Exchanges the bank code and verifies its approved, active, vendor-specific bank connection. */
internal fun interface HostedBankCodeExchange {
    suspend fun exchange(vendor: Vendor, bankCode: String, bankVerifier: String): VerifiedConnection?

    class VerifiedConnection(val connectionId: UUID, val vendor: Vendor)
}

/** The credential is exchanged for an identity only after explicit, replay-safe verification. */
internal fun interface HostedVendorAuthentication {
    suspend fun authenticate(credentials: Credentials): Vendor?

    class Credentials(val clientId: String, val method: HostedOAuthAdmission.TokenAuthentication, val proof: String)
}

/** The implementation must recheck the active bank connection before minting an opaque credential. */
internal fun interface HostedVendorGrantIssuer {
    suspend fun issue(vendor: Vendor, connectionId: UUID, clientId: String): HostedVendorTokens?
}

internal class HostedOAuthComplete(
    private val transactions: PgHostedOAuthTransactions,
    private val bank: HostedBankCodeExchange,
    private val vendorAuthentication: HostedVendorAuthentication,
    private val grantIssuer: HostedVendorGrantIssuer,
    private val protector: FieldProtector,
    private val mapper: ObjectMapper,
    private val enabled: Set<Vendor> = emptySet(),
    private val registrations: HostedOAuthAdmission.Registrations = HostedOAuthAdmission.Registrations(),
) {
    private val random = SecureRandom()

    suspend fun callback(bankState: String, browserBinding: String, bankCode: String): URI? {
        if (!HANDLE.matches(bankState) || !HANDLE.matches(browserBinding) || !boundedCode(bankCode)) return null
        val stateDigest = digest(bankState)
        val bindingDigest = digest(browserBinding)
        val transaction = transactions.consumeBankCallback(stateDigest, bindingDigest) ?: return null
        if (transaction.vendor !in enabled) return null
        val consent = decrypt(transaction, Kind.BANK_CALLBACK, stateDigest, bindingDigest)
        val registration = registrationFor(consent, transaction.vendor) ?: return null
        val bankVerifier = consent.path("bankVerifier").textValue()?.takeIf(HANDLE::matches) ?: return null
        val state = consent.path("vendorState").textValue()?.takeIf(::boundedCode) ?: return null
        val challenge = consent.path("vendorChallenge").textValue()?.takeIf(HANDLE::matches) ?: return null
        log.infof(
            "Hosted OAuth stage=bank_callback_validated vendor=%s flow=%s",
            transaction.vendor,
            correlation(stateDigest),
        )
        val verified = bank.exchange(transaction.vendor, bankCode, bankVerifier) ?: return null
        if (verified.vendor != transaction.vendor) return null
        val redirect = createVendorCode(verified, registration, state, challenge)
        log.infof(
            "Hosted OAuth stage=vendor_code_issued vendor=%s flow=%s",
            verified.vendor,
            correlation(stateDigest),
        )
        return redirect
    }

    class Redemption(val code: String, val verifier: String, val redirectUri: String, val resource: String)

    suspend fun redeem(request: Redemption, credentials: HostedVendorAuthentication.Credentials): HostedVendorTokens? {
        if (!HANDLE.matches(request.code) || !VERIFIER.matches(request.verifier)) return null
        val vendor = vendorAuthentication.authenticate(credentials) ?: return null
        val registration = registrations.find(credentials.clientId)?.takeIf { it.vendor == vendor } ?: return null
        if (vendor !in enabled || request.redirectUri != registration.callback) return null
        if (request.resource != HostedOAuthAdmission.RESOURCE) return null
        return redeemFor(registration, digest(request.code), digest(request.verifier))
    }

    private suspend fun createVendorCode(
        connection: HostedBankCodeExchange.VerifiedConnection,
        registration: Registration,
        state: String,
        challenge: String,
    ): URI {
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(HANDLE_BYTES).also(random::nextBytes),
        )
        val codeDigest = digest(code)
        val data = connection.connectionId.toString().toByteArray(StandardCharsets.US_ASCII)
        val key = codeBinding(registration, challenge)
        val aad = associatedData(Kind.VENDOR_CODE, codeDigest, connection.vendor, key)
        val sealed = withContext(Dispatchers.IO) { protector.encrypt(data, aad) }
        check(
            transactions.put(
                Kind.VENDOR_CODE,
                codeDigest,
                connection.vendor,
                key,
                sealed.toByteArray(StandardCharsets.US_ASCII),
            ),
        ) { "Could not persist vendor authorization code" }
        val encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8)
        val issuer = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8)
        return URI.create("${registration.callback}?code=$code&state=$encodedState&iss=$issuer")
    }

    private suspend fun redeemFor(
        registration: Registration,
        codeDigest: String,
        pkceDigest: String,
    ): HostedVendorTokens? {
        val vendor = registration.vendor
        val key = codeBinding(registration, pkceDigest)
        val transaction = transactions.consumeVendorCode(codeDigest, key, vendor) ?: return null
        val aad = associatedData(Kind.VENDOR_CODE, codeDigest, vendor, key)
        val payload = withContext(Dispatchers.IO) { protector.decrypt(transaction.sealedPayload.decodeToString(), aad) }
        return grantIssuer.issue(vendor, UUID.fromString(payload.decodeToString()), registration.clientId)
    }

    private fun registrationFor(consent: JsonNode, vendor: Vendor): Registration? {
        val clientNode = consent.get("vendorClientId")
        val callbackNode = consent.get("vendorCallback")
        val clientId = if (clientNode == null) vendor.clientId else clientNode.textValue()
        val callback = if (callbackNode == null) vendor.callback else callbackNode.textValue()
        return registrations.find(clientId)?.takeIf { it.vendor == vendor && it.callback == callback }
    }

    private fun codeBinding(registration: Registration, pkceDigest: String): String =
        if (registration.clientId == registration.vendor.clientId) {
            pkceDigest
        } else {
            digest("${registration.clientId}\n${registration.callback}\n$pkceDigest")
        }

    private suspend fun decrypt(
        transaction: PgHostedOAuthTransactions.Consumed,
        kind: Kind,
        handleDigest: String,
        bindingDigest: String,
    ) = withContext(Dispatchers.IO) {
        val aad = associatedData(kind, handleDigest, transaction.vendor, bindingDigest)
        mapper.readTree(protector.decrypt(transaction.sealedPayload.decodeToString(), aad))
    }

    private fun boundedCode(value: String) = value.length in 1..MAX_VENDOR_STATE_LENGTH && value.all { it in '!'..'~' }

    companion object {
        private val log = Logger.getLogger(HostedOAuthComplete::class.java)
        const val ISSUER = "https://mcp.open-bank.tech/oauth/hosted"
        private val HANDLE = Regex("[A-Za-z0-9_-]{43}")
        private val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")
    }
}
