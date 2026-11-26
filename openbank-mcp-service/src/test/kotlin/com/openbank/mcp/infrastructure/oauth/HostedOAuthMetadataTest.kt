// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HostedOAuthMetadataTest {
    @Test
    fun `discovery advertises only enabled client authentication and the read scope`() {
        assertThat(HostedOAuthMetadata.document(emptySet())).isNull()
        assertThat(HostedOAuthMetadata.document(setOf(Vendor.CHATGPT))).containsAllEntriesOf(
            mapOf(
                "client_id_metadata_document_supported" to true,
                "token_endpoint_auth_methods_supported" to listOf("private_key_jwt"),
                "code_challenge_methods_supported" to listOf("S256"),
                "scopes_supported" to listOf("mcp:balance:read"),
            ),
        )
        assertThat(HostedOAuthMetadata.document(setOf(Vendor.CLAUDE))).containsAllEntriesOf(
            mapOf(
                "client_id_metadata_document_supported" to false,
                "token_endpoint_auth_methods_supported" to listOf("client_secret_basic", "client_secret_post"),
            ),
        )
    }
}
