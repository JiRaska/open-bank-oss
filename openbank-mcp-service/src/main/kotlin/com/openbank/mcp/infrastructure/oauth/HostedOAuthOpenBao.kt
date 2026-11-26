// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.CachingOpenBaoTokenSource
import com.openbank.libs.security.OpenBaoKubernetesLogin
import com.openbank.libs.security.OpenBaoTransitFieldProtector
import com.openbank.libs.security.OpenBaoTransport
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import java.net.URI
import java.nio.file.Path

/** One scoped Kubernetes login and dedicated Transit custody and signing keys for hosted OAuth. */
internal class HostedOAuthOpenBao(
    address: URI,
    caFile: Path,
    serviceAccountToken: Path,
    mapper: ObjectMapper,
    custodyPepper: ByteArray,
    signingKeys: Map<Vendor, HostedBankTransitProof.VendorKeys>,
) {
    init {
        require(address.scheme == "https" && address.host != null && address.userInfo == null)
        require(address.rawPath.isNullOrEmpty() && address.rawQuery == null && address.rawFragment == null)
    }

    private val transport = OpenBaoTransport(address, OpenBaoTransport.pemTrustSslContext(caFile))
    private val login = OpenBaoKubernetesLogin(transport, AUTH_ROLE, serviceAccountToken)
    private val tokens = CachingOpenBaoTokenSource(login::login)

    val signer = HostedBankTransitProof(transport, tokens, mapper, signingKeys)
    val custody = OpenBaoTransitFieldProtector(
        transport,
        CUSTODY_KEY,
        tokens,
        custodyPepper,
        objectMapper = mapper,
    )

    private companion object {
        const val AUTH_ROLE = "mcp-hosted-oauth"
        const val CUSTODY_KEY = "mcp-hosted-oauth-custody"
    }
}
