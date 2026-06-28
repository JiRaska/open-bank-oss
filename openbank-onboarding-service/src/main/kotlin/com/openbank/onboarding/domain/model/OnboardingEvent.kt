// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Inbound domain events consumed from upstream services and projected into [OnboardingRecord].
 * Kept as a sealed hierarchy so the projection logic can use exhaustive `when` with no
 * framework dependency.
 */
sealed class OnboardingEvent {
    abstract val occurredAt: Instant

    data class PartyCreated(
        val partyId: UUID,
        val legalName: String,
        val email: String,
        override val occurredAt: Instant,
    ) : OnboardingEvent()

    data class PartyStatusChanged(val partyId: UUID, val newStatus: PartyStage, override val occurredAt: Instant) :
        OnboardingEvent()

    data class KycCaseOpened(val partyId: UUID, val kycCaseId: UUID, override val occurredAt: Instant) :
        OnboardingEvent()

    data class KycStatusChanged(
        val partyId: UUID,
        val kycCaseId: UUID,
        val newStatus: KycStage,
        override val occurredAt: Instant,
    ) : OnboardingEvent()

    data class DeviceEnrolled(val partyId: UUID, val credentialId: String, override val occurredAt: Instant) :
        OnboardingEvent()
}
