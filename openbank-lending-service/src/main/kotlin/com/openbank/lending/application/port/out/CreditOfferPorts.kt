// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.BorrowerDistressSignals
import java.util.UUID

/**
 * Reads the customer's `CREDIT_OFFERS` consent (ADR-0269 rule 1).
 *
 * Returns null — never false — when consent-service could not be reached. The two cases must stay
 * distinguishable: "the customer said no" and "we do not know" are both refusals here, but only one
 * of them is a fault worth alerting on, and collapsing them hides an outage behind a normal-looking
 * suppression.
 */
interface CreditOffersConsentPort {
    suspend fun hasCreditOffersConsent(partyId: UUID): Boolean?
}

/**
 * Reads the distress inputs behind ADR-0269 rule 2.
 *
 * An implementation that cannot read every input must return signals with
 * [BorrowerDistressSignals.complete] = false rather than substituting defaults. A default of
 * "no arrears" for an unreachable upstream is indistinguishable from a healthy borrower, which is
 * precisely the substitution the flag exists to prevent.
 */
interface BorrowerDistressPort {
    suspend fun signalsFor(partyId: UUID): BorrowerDistressSignals
}
