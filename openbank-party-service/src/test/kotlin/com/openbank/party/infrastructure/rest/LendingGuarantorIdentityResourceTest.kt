// SPDX-License-Identifier: Apache-2.0
package com.openbank.party.infrastructure.rest

import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.usecase.PartyNotFoundException
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyClassification
import com.openbank.party.domain.model.PartyStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.UUID

class LendingGuarantorIdentityResourceTest {
    private val id = UUID.randomUUID()

    @Test
    fun `only active customer with approved KYC and cleared AML is verified`(): Unit = runBlocking {
        val parties = mockk<PartyUseCase>()
        val party = mockk<Party>()
        every { party.classification } returns PartyClassification.CUSTOMER
        every { party.status } returns PartyStatus.ACTIVE
        every { party.kycStatus } returns KycStatus.APPROVED
        every { party.amlStatus } returns AmlStatus.CLEARED
        coEvery { parties.getParty(id) } returns party
        val resource = resource(parties, "service-account-openbank-lending-graph")
        assertThat(resource.verify(LendingGuarantorIdentityRequest(id)).verified).isTrue()
        every { party.amlStatus } returns AmlStatus.BLOCKED
        assertThat(resource.verify(LendingGuarantorIdentityRequest(id)).verified).isFalse()
        every { party.amlStatus } returns AmlStatus.CLEARED
        every { party.status } returns PartyStatus.MERGED
        assertThat(resource.verify(LendingGuarantorIdentityRequest(id)).verified).isFalse()
        every { party.status } returns PartyStatus.ACTIVE
        every { party.classification } returns PartyClassification.SYNTHETIC
        assertThat(resource.verify(LendingGuarantorIdentityRequest(id)).verified).isFalse()
    }

    @Test
    fun `unknown party returns only false and source failures propagate`(): Unit = runBlocking {
        val parties = mockk<PartyUseCase>()
        val resource = resource(parties, "service-account-openbank-lending-graph")
        coEvery { parties.getParty(id) } throws PartyNotFoundException(id)
        assertThat(resource.verify(LendingGuarantorIdentityRequest(id)).verified).isFalse()
        coEvery { parties.getParty(id) } throws IllegalStateException("source unavailable")
        assertThatThrownBy { runBlocking { resource.verify(LendingGuarantorIdentityRequest(id)) } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `shared backend identity is denied before Party lookup`(): Unit = runBlocking {
        val parties = mockk<PartyUseCase>()
        assertThatThrownBy {
            runBlocking {
                resource(parties, "service-account-openbank-services")
                    .verify(LendingGuarantorIdentityRequest(id))
            }
        }.isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { parties.getParty(any()) }
    }

    private fun resource(parties: PartyUseCase, principalName: String): LendingGuarantorIdentityResource {
        val identity = mockk<SecurityIdentity>()
        val principal = mockk<Principal>()
        every { principal.name } returns principalName
        every { identity.principal } returns principal
        return LendingGuarantorIdentityResource(parties, identity)
    }
}
