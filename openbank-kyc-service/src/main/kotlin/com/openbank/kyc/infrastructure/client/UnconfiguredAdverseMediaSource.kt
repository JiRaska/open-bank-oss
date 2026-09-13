// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.AdverseMediaOutcome
import com.openbank.kyc.application.port.out.AdverseMediaScreeningPort
import com.openbank.kyc.application.port.out.AdverseMediaScreeningResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * The only [AdverseMediaScreeningPort] implementation on the platform today: it reports that no
 * adverse-media source is configured, and it can return nothing else.
 *
 * This is **not** a stub that answers "no adverse media found". That distinction is the entire
 * point of the class: OpenBank has no licensed, EU-residency-compatible, change-detectable
 * adverse-media feed (ADR-0256 D5, issue #4459), and a screening path that quietly returns a clean
 * result for a source it never reached is worse than having no path at all — it manufactures
 * evidence of a control that does not exist.
 *
 * When a real source lands, it replaces this bean (an `@Alternative`/`@Priority` or a
 * build-profile selection) and returns a non-null `sourceId`; nothing else in the port contract
 * changes.
 */
@ApplicationScoped
class UnconfiguredAdverseMediaSource : AdverseMediaScreeningPort {

    /** Null by construction — see [AdverseMediaScreeningPort.sourceId]. */
    override val sourceId: String? = null

    override suspend fun screen(name: String, idempotencyKey: String): AdverseMediaScreeningResult =
        AdverseMediaScreeningResult(outcome = AdverseMediaOutcome.SOURCE_NOT_CONFIGURED, sourceId = null)
}
