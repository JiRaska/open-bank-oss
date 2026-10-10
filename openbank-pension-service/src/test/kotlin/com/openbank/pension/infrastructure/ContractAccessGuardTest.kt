// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ContractAccessGuardTest {

    private val owner = UUID.randomUUID()
    private val contract = PensionContract.draft(
        owner, ProductLine.DPS, "XX", 1, UUID.randomUUID(), ProviderType.PENSION_COMPANY,
        LocalDate.parse("1985-01-01"), ContributionSchedule(BigDecimal.TEN, "CZK", ContributionFrequency.MONTHLY),
        "BALANCED", emptyList(), LocalDate.parse("2026-10-09"), Instant.EPOCH,
    )

    private fun guard(principal: String, vararg roles: String) = ContractAccessGuard().apply {
        identity = mockk<SecurityIdentity> {
            every { this@mockk.principal } returns Principal { principal }
            every { hasRole(any()) } answers { firstArg<String>() in roles }
        }
        trustedRelays = listOf(ContractAccessGuard.DEFAULT_RELAY)
    }

    private val edge = guard(ContractAccessGuard.DEFAULT_RELAY, "ROLE_API")

    @Test
    fun `the owner sees the contract and another party gets not-found`() {
        assertThat(edge.requireVisible(edge.readerFor(owner.toString()), contract)).isSameAs(contract)
        assertThatThrownBy { edge.requireVisible(edge.readerFor(UUID.randomUUID().toString()), contract) }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `staff read without a header, but a change requires a participant`() {
        val staff = guard("alice", "ROLE_OPERATOR")
        assertThat(staff.readerFor(null)).isEqualTo(Caller.STAFF)
        assertThat(staff.requireVisible(Caller.STAFF, contract)).isSameAs(contract)
        assertThatThrownBy { staff.actingParticipant(null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a non-staff caller without the header is refused`() {
        assertThatThrownBy { edge.readerFor(null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a party header from anyone but the trusted relay is forbidden`() {
        val other = guard("service-account-other", "ROLE_API")
        assertThatThrownBy { other.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { other.actingParticipant(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
        val staffWithHeader = guard("alice", "ROLE_OPERATOR")
        assertThatThrownBy { staffWithHeader.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
    }
}
