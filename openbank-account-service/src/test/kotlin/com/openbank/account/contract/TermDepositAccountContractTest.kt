// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

import com.openbank.account.domain.model.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class TermDepositAccountContractTest {
    @Test
    fun `term deposit is an account domain type`() {
        assertThat(AccountType.entries).contains(AccountType.TERM_DEPOSIT)
    }

    @Test
    fun `open account contract exposes term deposits`() {
        val openApi = File("src/main/resources/openapi.yaml").readText()

        assertThat(openApi)
            .contains("TERM_DEPOSIT")
    }
}
