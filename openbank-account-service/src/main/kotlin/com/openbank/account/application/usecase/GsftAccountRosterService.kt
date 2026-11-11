// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.GsftAccountRosterRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The minimal account register fields GSFT needs before looking up balances and holder facts. */
data class GsftRosterEntry(
    val id: UUID,
    val accountNumber: String,
    val partyId: UUID,
    val currencyCode: String,
    val accountType: String,
)

@ApplicationScoped
class GsftAccountRosterService(private val repository: GsftAccountRosterRepository, private val clock: Clock) {
    suspend fun list(relevantDay: LocalDate, requestedLimit: Int, cursor: String?): CursorPage<GsftRosterEntry> {
        require(!relevantDay.isAfter(LocalDate.now(clock.withZone(REPORTING_ZONE)))) {
            "asOf must not be in the future"
        }
        val limit = requestedLimit.coerceIn(1, MAX_PAGE_SIZE)
        val prefix = "$relevantDay/"
        val afterId = cursor?.let {
            val value = CursorEncoder.decode(it)
            require(value.startsWith(prefix)) { "account roster cursor does not match asOf" }
            UUID.fromString(value.removePrefix(prefix))
        }
        val cutoff = relevantDay.plusDays(1).atStartOfDay(REPORTING_ZONE).toInstant()
        val rows = repository.findDepositAccounts(cutoff, limit + 1, afterId)
        check(rows.none { it.status == AccountStatus.CLOSED && it.closedAt == null }) {
            "account roster cannot establish when a closed account left the register"
        }
        val page = rows.take(limit).map(Account::toGsftEntry)
        val hasNext = rows.size > limit
        return CursorPage(
            data = page,
            pagination = PageInfo(
                limit = limit,
                hasNextPage = hasNext,
                nextCursor = if (hasNext) CursorEncoder.encode("$prefix${page.last().id}") else null,
            ),
        )
    }

    private companion object {
        val REPORTING_ZONE: ZoneId = ZoneId.of("Europe/Prague")
        const val MAX_PAGE_SIZE = 200
    }
}

private fun Account.toGsftEntry(): GsftRosterEntry = GsftRosterEntry(
    id = id,
    accountNumber = accountNumber.value,
    partyId = partyId,
    currencyCode = currency.code,
    accountType = accountType.name,
)
