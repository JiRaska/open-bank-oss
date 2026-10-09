// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import java.util.UUID

/** What a first contribution did to a contract still awaiting activation. */
enum class ActivationOutcome {
    /** The onboarding workflow was told; it activates once the cooling-off period has ended. */
    SIGNALLED,

    /** The contract is not a signed, new-contract onboarding awaiting its first contribution. */
    NOT_AWAITING,
}

/**
 * The seam between contributions (slice S3) and onboarding (slice S2, ADR-0334), and the ONLY way
 * money can lead to activation. A PENDING_ACTIVATION contract activates through the onboarding
 * workflow, which enforces the gates the application went through (KID accepted, SCA signature,
 * cooling-off ended); a contribution never moves the contract itself (ADR-0334 S8 — the S3 default
 * adapter used to apply `PensionContract.activate` directly, which let a payment activate a
 * contract whose onboarding had not been signed).
 *
 * Implemented by S2 against the real onboarding workflow — no stub.
 */
interface OnboardingActivationPort {
    /** True only for a SIGNED new-contract onboarding still waiting for its first contribution. */
    suspend fun awaitsFirstContribution(contractId: UUID): Boolean

    suspend fun firstContributionReceived(contractId: UUID): ActivationOutcome
}
