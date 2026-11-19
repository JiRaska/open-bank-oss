// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.contract

import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.OffsetDateTime
import java.util.UUID

object StatutoryApprovalPactFixture {
    const val STATE = "a device approved a verified statutory delegation challenge"
    val challengeId: UUID = UUID.fromString("b7190001-0000-4000-8000-000000000001")
    private val actor = UUID.fromString("b7190002-0000-4000-8000-000000000001")
    private val company = UUID.fromString("b7190003-0000-4000-8000-000000000001")
    val identity: QuarkusSecurityIdentity
        get() {
            val jwt = mockk<JsonWebToken>(relaxed = true)
            every { jwt.name } returns "service-account-openbank-delegation"
            every { jwt.subject } returns "synthetic-delegation-subject"
            every { jwt.getClaim<String>("azp") } returns "openbank-delegation"
            every { jwt.getClaim<String>("preferred_username") } returns "service-account-openbank-delegation"
            return QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRole("ROLE_API").build()
        }

    fun challenge(version: Int): ScaChallenge {
        val now = OffsetDateTime.now()
        return ScaChallenge(
            id = challengeId,
            partyId = actor,
            purpose = ScaPurpose.APPROVAL,
            method = ScaMethod.BIOMETRIC,
            status = ScaStatus.COMPLETED,
            expiresAt = now.plusMinutes(5),
            completedAt = now,
            createdAt = now,
            version = version,
            onBehalfOfPartyId = company,
            decidedByPartyId = actor,
            dynamicLinkingData = DynamicLinkingData(
                amount = null,
                currency = null,
                creditorIban = null,
                creditorName = null,
                reference = null,
                approvalRequestId = "delegation-statutory-issue:b7190004-0000-4000-8000-000000000001",
                payloadSha256 = "b".repeat(64),
            ),
        )
    }
}
