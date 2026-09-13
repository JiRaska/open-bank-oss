// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.usecase.DelegationGrantorAuthorityException
import com.openbank.delegation.application.usecase.DelegationGrantorAuthorityUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GrantorAuthorityExceptionMapperTest {
    @Test
    fun `known authority denial is forbidden`() {
        val response = DelegationGrantorAuthorityExceptionMapper()
            .toResponse(DelegationGrantorAuthorityException("no active mandate"))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity.toString()).contains("GRANTOR_AUTHORITY_REJECTED")
    }

    @Test
    fun `authority outage is retryable service unavailable`() {
        val response = DelegationGrantorAuthorityUnavailableExceptionMapper()
            .toResponse(DelegationGrantorAuthorityUnavailableException("party service unavailable"))

        assertThat(response.status).isEqualTo(503)
        assertThat(response.getHeaderString("Retry-After")).isEqualTo("2")
        assertThat(response.entity.toString()).contains("GRANTOR_AUTHORITY_UNAVAILABLE")
    }
}
