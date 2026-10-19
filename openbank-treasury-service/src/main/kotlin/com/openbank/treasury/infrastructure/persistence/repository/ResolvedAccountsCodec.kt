// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.repository

import com.openbank.treasury.domain.model.LedgerRole
import com.openbank.treasury.domain.model.ResolvedAccount
import java.util.UUID

/** `deal_journals.resolved_accounts`: one `class|role|currency|glAccountId` per line, newline separated. */
internal object ResolvedAccountsCodec {
    fun encode(accounts: List<ResolvedAccount>): String? = accounts.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "${it.accountingClass}|${it.role.name}|${it.currency}|${it.glAccountId}" }

    fun decode(raw: String?): List<ResolvedAccount> = raw?.lineSequence()?.filter { it.isNotBlank() }?.map {
        val id = it.substringAfterLast('|')
        val (cls, role, ccy) = it.substringBeforeLast('|').split('|')
        ResolvedAccount(cls, LedgerRole.valueOf(role), ccy, UUID.fromString(id))
    }?.toList().orEmpty()
}
