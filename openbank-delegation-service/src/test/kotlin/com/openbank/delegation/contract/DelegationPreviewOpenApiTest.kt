// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationPreviewOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

    @Test
    fun `preview is a documented pre-SCA validation and not an authority decision`() {
        assertThat(contract).contains("/api/v1/delegations/preview:")
        assertThat(contract).contains("PreviewDelegationRequest:")
        assertThat(contract).contains("preview never reads or consumes SCA")
        assertThat(contract).contains("this response is never authorization")
        assertThat(contract).contains("Counterparty names are deliberately not returned")
        assertThat(contract).contains("EXPOSURE_UNSUPPORTED")
        assertThat(contract).contains("Historical audit metadata only")
    }

    /**
     * The 403 description was EXTENDED by this branch, not replaced: organization grant authority
     * adds a second way to be refused — the caller is the right party but the acting human holds
     * no active mandate for it. The literal is pinned rather than matched loosely because a
     * substring of the old text still matches the new one, so a relaxed assertion would pass
     * against a spec that had silently dropped the authority half.
     */
    @Test
    fun `preview names both ways a caller is refused with 403`() {
        assertThat(contract).contains(
            "'403': { description: The authenticated party is not the grantor, " +
                "or the actor has no active authority for it }",
        )
    }
}
