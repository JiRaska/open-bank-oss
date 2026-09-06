// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `derive` is an ordered `when`, so each branch is only reachable while every branch above it
 * misses — the interesting assertions are the PRECEDENCE ones, not the happy path. A reordering
 * that looks harmless (e.g. moving the ACTIVE checks below the KYC ones) would leave an
 * approved, device-enrolled party sitting in a KYC column on the cockpit board.
 */
class FunnelStageDeriveTest {

    @Test
    fun `a SUSPENDED party is BLOCKED regardless of kyc and sca state`() {
        for (kyc in KycStage.entries + listOf(null)) {
            assertThat(FunnelStage.derive(PartyStage.SUSPENDED, kyc, true)).isEqualTo(FunnelStage.BLOCKED)
            assertThat(FunnelStage.derive(PartyStage.SUSPENDED, kyc, false)).isEqualTo(FunnelStage.BLOCKED)
        }
    }

    @Test
    fun `a CLOSED party is BLOCKED regardless of kyc and sca state`() {
        for (kyc in KycStage.entries + listOf(null)) {
            assertThat(FunnelStage.derive(PartyStage.CLOSED, kyc, true)).isEqualTo(FunnelStage.BLOCKED)
        }
    }

    @Test
    fun `party status wins over kyc status - an ACTIVE party is never shown in a KYC column`() {
        // The party reached ACTIVE while its KYC record still says UNDER_REVIEW (event ordering).
        // The board must show it as onboarded-pending-SCA, not back in the review queue.
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, KycStage.UNDER_REVIEW, false))
            .isEqualTo(FunnelStage.SCA_PENDING)
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, KycStage.UNDER_REVIEW, true))
            .isEqualTo(FunnelStage.ACTIVE)
    }

    @Test
    fun `an ACTIVE party with no kyc record at all still splits on sca enrolment`() {
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, null, false)).isEqualTo(FunnelStage.SCA_PENDING)
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, null, true)).isEqualTo(FunnelStage.ACTIVE)
    }

    @Test
    fun `UNDER_REVIEW outranks DOCUMENTS_REQUIRED and OPEN for a pending party`() {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.UNDER_REVIEW, false))
            .isEqualTo(FunnelStage.KYC_UNDER_REVIEW)
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.DOCUMENTS_REQUIRED, false))
            .isEqualTo(FunnelStage.KYC_DOCUMENTS_REQUIRED)
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.OPEN, false))
            .isEqualTo(FunnelStage.KYC_OPEN)
    }

    @Test
    fun `a pending party with no kyc record yet reads as KYC_OPEN, never REGISTERED`() {
        // The `kyc == null` arm sits in the KYC_OPEN branch, which makes the trailing
        // `else -> REGISTERED` unreachable: REGISTERED is a stage no derivation can produce.
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, null, false)).isEqualTo(FunnelStage.KYC_OPEN)
    }

    @Test
    fun `a rejected or expired kyc on a still-pending party is BLOCKED`() {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.REJECTED, false))
            .isEqualTo(FunnelStage.BLOCKED)
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.EXPIRED, false))
            .isEqualTo(FunnelStage.BLOCKED)
    }

    @Test
    fun `an APPROVED kyc on a party party-service has not activated yet stays in the KYC column`() {
        // Not ACTIVE and not BLOCKED: the party is waiting on PARTY_STATUS_CHANGED, and the
        // board must not claim it is onboarded before party-service says so.
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.APPROVED, false))
            .isEqualTo(FunnelStage.KYC_OPEN)
    }

    @Test
    fun `sca enrolment alone never advances a party that is not ACTIVE`() {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.APPROVED, true))
            .isNotEqualTo(FunnelStage.ACTIVE)
    }

    @Test
    fun `derive is total - no combination of the three dimensions is left underived`() {
        val stages = buildSet {
            for (party in PartyStage.entries) {
                for (kyc in KycStage.entries + listOf(null)) {
                    for (sca in listOf(true, false)) {
                        add(FunnelStage.derive(party, kyc, sca))
                    }
                }
            }
        }
        // REGISTERED is the one stage the derivation can never produce (see the KYC_OPEN branch).
        assertThat(stages).doesNotContain(FunnelStage.REGISTERED)
        assertThat(stages).containsAll(FunnelStage.entries - FunnelStage.REGISTERED)
    }
}
