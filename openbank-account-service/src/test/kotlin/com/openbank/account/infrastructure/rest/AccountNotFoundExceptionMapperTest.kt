// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.AccountNotFoundException
import com.openbank.libs.api.error.ApiError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountNotFoundExceptionMapperTest {

    @Test
    fun `maps AccountNotFoundException to a 404 ACCOUNT_NOT_FOUND ApiError`() {
        val response = AccountNotFoundExceptionMapper()
            .toResponse(AccountNotFoundException("Account not found: 42"))

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(404)
        assertThat(error.code).isEqualTo("ACCOUNT_NOT_FOUND")
        assertThat(error.message).isEqualTo("Account not found: 42")
        assertThat(error.traceId).isNotBlank()
    }
}
