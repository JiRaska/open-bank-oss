// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit coverage for the ADR-0155 exception mappers (403/409 on the four-eyes decide endpoint). */
class ApprovalExceptionMappersTest {

    @Test
    fun `SelfApprovalNotAllowedMapper maps to 403 with the exception message`() {
        val response = SelfApprovalNotAllowedMapper().toResponse(SelfApprovalNotAllowedException("maker-1"))

        assertThat(response.status).isEqualTo(403)
        val body = response.entity as ApiError
        assertThat(body.status).isEqualTo(403)
        assertThat(body.code).isEqualTo("FORBIDDEN")
        assertThat(body.message).contains("maker-1")
    }

    @Test
    fun `InvalidApprovalStateMapper maps to 409 with the exception message`() {
        val exception = InvalidApprovalStateException("appr-1", ApprovalStatus.PENDING, ApprovalStatus.EXECUTED)

        val response = InvalidApprovalStateMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(409)
        val body = response.entity as ApiError
        assertThat(body.status).isEqualTo(409)
        assertThat(body.code).isEqualTo("CONFLICT")
        assertThat(body.message).contains("appr-1")
    }
}
