// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.AccountingClassMapRepository
import com.openbank.ledger.domain.model.AccountingClassMapping
import com.openbank.ledger.domain.model.AccountingRole
import com.openbank.ledger.domain.model.LedgerValidationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AccountingClassServiceTest {
    private val id = UUID.fromString("a0000000-0000-0000-0000-000000001500")
    private val mapped = AccountingClassMapping("INTERBANK_PLACEMENT", AccountingRole.PRINCIPAL, "CZK", id, "1500")

    private val repo = object : AccountingClassMapRepository {
        override suspend fun find(accountingClass: String, role: AccountingRole, currencyCode: String) = mapped.takeIf {
            it.accountingClass == accountingClass && it.role == role && it.currencyCode == currencyCode
        }

        override suspend fun listMappings() = listOf(mapped)
    }
    private val service = AccountingClassService(repo)

    @Test
    fun `resolves a mapped triple to its account`(): Unit = runBlocking {
        assertThat(service.resolve("INTERBANK_PLACEMENT", "PRINCIPAL", "CZK").glAccountId).isEqualTo(id)
    }

    @Test
    fun `an unmapped triple is refused, never defaulted`(): Unit = runBlocking {
        listOf(
            Triple("INTERBANK_PLACEMENT", "PRINCIPAL", "EUR"),
            Triple("OTHER", "PRINCIPAL", "CZK"),
            Triple("INTERBANK_PLACEMENT", "FEE_INCOME", "CZK"),
        ).forEach { (c, r, ccy) ->
            assertThatThrownBy { runBlocking { service.resolve(c, r, ccy) } }
                .isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("no GL account is mapped")
        }
    }

    @Test
    fun `an unknown role is named in the error`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.resolve("INTERBANK_PLACEMENT", "BOGUS", "CZK") } }
            .isInstanceOf(LedgerValidationException::class.java)
            .hasMessageContaining("unknown accountingRole 'BOGUS'")
    }
}
