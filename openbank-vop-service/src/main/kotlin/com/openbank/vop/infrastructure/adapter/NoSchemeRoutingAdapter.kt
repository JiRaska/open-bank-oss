// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.adapter

import com.openbank.vop.application.port.out.VopSchemeRoutingPort
import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * The requester side of VoP, honestly (ADR-0171 §4).
 *
 * There is no EPC VoP routing link in this platform — exactly as the interbank rails reach only
 * `openbank-clearing-simulator`. Rather than fabricate a verdict for an IBAN we cannot ask anyone
 * about, every external IBAN gets NO_DATA / NO_SCHEME_CONNECTIVITY, and the payer is told we could
 * not check. That is the truthful answer, and under IPR Art. 5c a truthful "we could not verify"
 * discharges the duty to inform in a way a fabricated "match" would not.
 *
 * This class is the seam a real EPC routing adapter replaces. When one exists, it implements
 * [VopSchemeRoutingPort] and this bean goes away — the use case does not change.
 */
@ApplicationScoped
class NoSchemeRoutingAdapter(private val clock: Clock) : VopSchemeRoutingPort {

    override fun verifyExternal(iban: String, suppliedName: String): Uni<VopVerification> = Uni.createFrom().item(
        VopVerification(
            outcome = VopOutcome.NO_DATA,
            noDataReason = VopNoDataReason.NO_SCHEME_CONNECTIVITY,
            verifiedAt = clock.instant(),
        ),
    )
}
