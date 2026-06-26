// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.flags.FeatureDisabledException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeatureDisabledMapperTest {

    @Test
    fun `maps FeatureDisabledException to 404 with the flag name in the message`() {
        val response = FeatureDisabledMapper().toResponse(FeatureDisabledException("party-search"))

        assertThat(response.status).isEqualTo(404)
        val body = response.entity as ApiError
        assertThat(body.status).isEqualTo(404)
        assertThat(body.message).contains("party-search")
    }
}
