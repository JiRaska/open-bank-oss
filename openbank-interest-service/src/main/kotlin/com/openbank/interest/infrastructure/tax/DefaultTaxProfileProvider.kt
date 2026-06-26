// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.interest.infrastructure.tax

import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.tax.TaxProfile
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * v1 tax-profile provider (ADR-0033 §C). Interest-service has no party tax attributes yet, so this
 * resolves every account to the fiscally conservative CZ-resident-individual default — which is also
 * the fail-safe fallback. Account→party tax-attribute resolution is the documented fast-follow that
 * will replace this with a resilient call to the party service (retry/timeout, fail-safe to default).
 */
@ApplicationScoped
class DefaultTaxProfileProvider : TaxProfilePort {
    override fun resolve(accountId: UUID): Uni<TaxProfile> =
        Uni.createFrom().item(TaxProfile.FAIL_SAFE_DEFAULT)
}
