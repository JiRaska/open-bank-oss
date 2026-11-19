// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.sdd.application.port.`in`.ExpireIdleMandateUseCase
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.SddMandate
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate

/**
 * Idle-expiry transition (ADR-0036 §B). Saves the `EXPIRED` mandate and appends
 * `sdd.mandate.expired.v1` to the outbox in one chain, so the caller's transaction boundary
 * (`MandateExpiryWriter`) commits both or neither. The event is an additive type with the same
 * envelope as every other `sdd.mandate.*.v1` event, so existing consumers are unaffected.
 */
@ApplicationScoped
class MandateExpiryService(private val mandates: SddMandateRepository, private val outbox: SddOutbox) :
    ExpireIdleMandateUseCase {

    /** Clock seam (overridable in tests; CDI uses the default). */
    internal var clock: () -> Instant = Instant::now

    override fun expireIfIdle(mandate: SddMandate, today: LocalDate): Uni<Boolean> {
        val expired = MandateLifecycle.expireIfIdle(mandate, today)
        if (expired.status == mandate.status) return Uni.createFrom().item(false)
        return mandates.save(expired).flatMap { saved ->
            outbox.append(mandateEventMessage(saved, MANDATE_EXPIRED_EVENT, clock())).map { true }
        }
    }

    private companion object {
        const val MANDATE_EXPIRED_EVENT = "sdd.mandate.expired.v1"
    }
}
