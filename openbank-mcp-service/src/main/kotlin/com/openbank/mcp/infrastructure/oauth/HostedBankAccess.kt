// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedBankJwtVerifier.OwnedTokens
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import java.util.UUID

/** Exposes bank credentials only after live custody validation, including after any on-demand refresh. */
internal class HostedBankAccess(
    private val custody: HostedBankCustody,
    private val binding: HostedBankDpopBinding,
    private val exchanger: HostedBankRefreshExchange,
) {
    suspend fun load(vendor: Vendor, connectionId: UUID): OwnedTokens? {
        val thumbprint = binding.thumbprint(vendor) ?: return null
        val current = custody.loadActive(vendor, connectionId, thumbprint)
        if (current != null) return current
        if (!custody.refreshActive(vendor, connectionId, thumbprint, exchanger)) return null
        return custody.loadActive(vendor, connectionId, thumbprint)
    }
}
