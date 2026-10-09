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
 * The seam between contributions (slice S3) and onboarding (slice S2, ADR-0334): S3 calls this
 * when a contribution is matched to a PENDING_ACTIVATION contract, instead of parking it as
 * unmatched. Implemented by S2 against the real onboarding workflow — no stub.
 */
interface OnboardingActivationPort {
    suspend fun firstContributionReceived(contractId: UUID): ActivationOutcome
}
