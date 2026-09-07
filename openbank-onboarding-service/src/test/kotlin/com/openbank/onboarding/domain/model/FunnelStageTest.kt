// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FunnelStageTest {

    @Test
    fun `an APPROVED kyc on a party party-service has not activated yet stays in the KYC column`() {
        val stage = FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.APPROVED, scaEnrolled = false)
        assertThat(stage).isEqualTo(FunnelStage.KYC_UNDER_REVIEW)
    }

    @Test
    fun `derive is total - no combination of the three dimensions is left underived`() {
        val stages = PartyStage.entries.flatMap { party ->
            (KycStage.entries + null).flatMap { kyc ->
                listOf(true, false).map { scaEnrolled -> FunnelStage.derive(party, kyc, scaEnrolled) }
            }
        }
        // REGISTERED is assigned directly when a party record is first created (PartyCreated,
        // before any KYC case exists) - it is never a derive() output, so it is deliberately
        // excluded here rather than asserted reachable.
        assertThat(stages).doesNotContain(FunnelStage.REGISTERED)
    }

    @Test
    fun `a suspended or closed party is blocked regardless of kyc or sca`() {
        assertThat(FunnelStage.derive(PartyStage.SUSPENDED, KycStage.APPROVED, scaEnrolled = true))
            .isEqualTo(FunnelStage.BLOCKED)
        assertThat(FunnelStage.derive(PartyStage.CLOSED, null, scaEnrolled = false))
            .isEqualTo(FunnelStage.BLOCKED)
    }

    @Test
    fun `an active party is ACTIVE only once sca is enrolled, else SCA_PENDING`() {
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, KycStage.APPROVED, scaEnrolled = true))
            .isEqualTo(FunnelStage.ACTIVE)
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, KycStage.APPROVED, scaEnrolled = false))
            .isEqualTo(FunnelStage.SCA_PENDING)
    }

    @Test
    fun `a rejected or expired kyc is blocked`() {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.REJECTED, scaEnrolled = false))
            .isEqualTo(FunnelStage.BLOCKED)
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.EXPIRED, scaEnrolled = false))
            .isEqualTo(FunnelStage.BLOCKED)
    }

    @Test
    fun `no kyc case yet or an open one is the KYC_OPEN column`() {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, null, scaEnrolled = false))
            .isEqualTo(FunnelStage.KYC_OPEN)
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.OPEN, scaEnrolled = false))
            .isEqualTo(FunnelStage.KYC_OPEN)
    }
}
