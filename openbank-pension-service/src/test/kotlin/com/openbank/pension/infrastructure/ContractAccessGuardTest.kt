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
import org.eclipse.microprofile.jwt.JsonWebToken
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

    private fun guard(principal: Principal, vararg roles: String) = ContractAccessGuard().apply {
        identity = mockk<SecurityIdentity> {
            every { this@mockk.principal } returns principal
            every { hasRole(any()) } answers { firstArg<String>() in roles }
        }
        trustedRelayClients = listOf(ContractAccessGuard.DEFAULT_RELAY_CLIENT)
    }

    private fun guard(name: String, vararg roles: String) = guard(Principal { name }, *roles)

    private fun jwt(azp: String?, preferredUsername: String?, sub: String? = UUID.randomUUID().toString()) =
        mockk<JsonWebToken> {
            every { name } returns (preferredUsername ?: "anon")
            every { subject } returns sub
            every { getClaim<String?>("azp") } returns azp
            every { getClaim<String?>("preferred_username") } returns preferredUsername
        }

    private val relayName = ContractAccessGuard.SERVICE_ACCOUNT_PREFIX + ContractAccessGuard.DEFAULT_RELAY_CLIENT
    private val edge = guard(jwt(ContractAccessGuard.DEFAULT_RELAY_CLIENT, relayName), "ROLE_API")

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

    @Test
    fun `the relay's NAME on a token issued to a different client is forbidden`() {
        val impostor = guard(jwt(azp = "openbank-admin-ui", preferredUsername = relayName), "ROLE_API")
        assertThatThrownBy { impostor.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { impostor.actingParticipant(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a non-JWT principal carrying the relay's name is forbidden`() {
        val named = guard(relayName, "ROLE_API")
        assertThatThrownBy { named.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a human user token issued through the relay client is forbidden`() {
        val human = guard(jwt(azp = ContractAccessGuard.DEFAULT_RELAY_CLIENT, preferredUsername = "alice"), "ROLE_API")
        assertThatThrownBy { human.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
        val noAzp = guard(jwt(azp = null, preferredUsername = relayName), "ROLE_API")
        assertThatThrownBy { noAzp.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
        val noSubject = guard(jwt(ContractAccessGuard.DEFAULT_RELAY_CLIENT, relayName, sub = null), "ROLE_API")
        assertThatThrownBy { noSubject.readerFor(owner.toString()) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the relay's own service-account token is trusted`() {
        assertThat(edge.readerFor(owner.toString())).isEqualTo(Caller.customer(owner))
        assertThat(edge.actingParticipant(owner.toString())).isEqualTo(Caller.customer(owner))
    }
}
