// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * ADR-0385 D3 V2 retains the V1 fields and adds the authenticated party, the independently verified
 * PAR-proving key thumbprint and one account-specific permission set per selected account. Shared
 * fixture expectations pin UTF-8 bytes, ordering and digest in Keycloak and SCA.
 */
class McpConnectContextV2 private constructor(private val canonical: String) {
    data class AccountScope(val accountId: String, val capabilities: Collection<String>)

    fun canonical(): String = canonical

    fun payloadSha256(): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        const val VERSION = "openbank-mcp-connect-context/v2"
        private const val UUID_LENGTH = 36
        private const val SHA256_BYTES = 32
        private val JKT = Regex("^[A-Za-z0-9_-]{43}$")
        private val allowedCapabilities = setOf(
            "ACCOUNT_READ_BALANCES",
            "ACCOUNT_READ_TRANSACTIONS",
            "ACCOUNT_VIEW_DETAILS",
            "ACCOUNT_DOWNLOAD_STATEMENTS",
            "ACCOUNT_PROPOSE_PAYMENT",
        )

        /**
         * The caller supplies the party and JKT from verified authorization-server state and
         * the account choices from an ownership-validated approval. This validates their encoding.
         */
        fun of(
            base: McpConnectContext,
            partyId: String,
            parProofThumbprint: String,
            accounts: Collection<AccountScope>,
        ): McpConnectContextV2 {
            requireCanonicalUuid(partyId)
            requireCanonicalThumbprint(parProofThumbprint)
            require(accounts.isNotEmpty()) { "selected accounts are required" }

            val selected = sortedMapOf<String, String>()
            accounts.forEach { account ->
                requireCanonicalUuid(account.accountId)
                val capabilities = account.capabilities.toSortedSet()
                require(capabilities.isNotEmpty() && allowedCapabilities.containsAll(capabilities)) {
                    "invalid account capabilities"
                }
                require(selected.putIfAbsent(account.accountId, capabilities.joinToString(",")) == null) {
                    "account selected twice"
                }
            }
            val canonical = buildString {
                append(VERSION)
                append(base.canonical().removePrefix(McpConnectContext.VERSION))
                append("\nparty_id=")
                append(partyId)
                append("\npar_jkt=")
                append(parProofThumbprint)
                selected.forEach { (account, capabilities) ->
                    append("\naccount_scope=")
                    append(account)
                    append(':')
                    append(capabilities)
                }
            }
            return McpConnectContextV2(canonical)
        }

        private fun requireCanonicalUuid(value: String) {
            require(value.length == UUID_LENGTH && UUID.fromString(value).toString() == value) {
                "expected a canonical UUID"
            }
        }

        private fun requireCanonicalThumbprint(value: String) {
            require(JKT.matches(value)) { "invalid PAR proof thumbprint" }
            val decoded = Base64.getUrlDecoder().decode(value)
            val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
            require(decoded.size == SHA256_BYTES && canonical == value) { "invalid PAR proof thumbprint" }
        }
    }
}
