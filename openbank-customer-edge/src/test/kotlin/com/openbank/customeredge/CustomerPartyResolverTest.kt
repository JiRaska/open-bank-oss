// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerPartyResolverTest {

    private fun resolver(
        partyIdClaim: String?,
        sub: String?,
        merges: PartyMergeResolver,
        actingFor: ActingForResolver,
    ) = CustomerPartyResolver(merges, actingFor).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns partyIdClaim
            every { subject } returns sub
        }
    }

    @Test
    fun `the token party is followed through a merge and then the verified profile switch`() {
        val claimed = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val merges = mockk<PartyMergeResolver> { every { resolve(claimed) } returns survivor }
        val actingFor = mockk<ActingForResolver> { every { resolve(survivor, entity.toString()) } returns entity }

        val resolved = resolver(claimed.toString(), UUID.randomUUID().toString(), merges, actingFor)
            .resolve(entity.toString())

        assertThat(resolved).isEqualTo(entity)
    }

    @Test
    fun `a token with no usable party claim is refused`() {
        val merges = mockk<PartyMergeResolver>()
        val actingFor = mockk<ActingForResolver>()

        assertThatThrownBy { resolver(null, null, merges, actingFor).resolve(null) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { resolver("not-a-uuid", null, merges, actingFor).resolve(null) }
            .isInstanceOf(ForbiddenException::class.java)
    }
}
