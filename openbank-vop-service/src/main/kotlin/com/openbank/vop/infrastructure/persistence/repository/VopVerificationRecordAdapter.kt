// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.persistence.repository

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.vop.application.port.out.VopVerificationRecordPort
import com.openbank.vop.domain.model.VopVerification
import com.openbank.vop.infrastructure.persistence.entity.VopVerificationEntity
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger

/**
 * Writes the VoP evidence row (ADR-0171 §7).
 *
 * Recording is best-effort **on purpose**: a failure to write the evidence row must not fail the
 * verification the payer is waiting on. VoP is fail-open (ADR-0171 §3), and turning a logging
 * outage into a payment-flow outage would invert that decision through the back door. The failure
 * is logged at ERROR so the evidence gap is visible rather than silent.
 */
@ApplicationScoped
class VopVerificationRecordAdapter @Inject constructor(private val sf: Mutiny.SessionFactory) :
    VopVerificationRecordPort {

    private val log = Logger.getLogger(VopVerificationRecordAdapter::class.java)

    @WithTransaction
    override fun record(
        ibanHash: String,
        suppliedNameHash: String,
        verification: VopVerification,
        requestedBy: String,
    ): Uni<Void> = sf.withTransaction { session ->
        session.persist(
            VopVerificationEntity().apply {
                id = Ids.newId()
                this.ibanHash = ibanHash
                this.suppliedNameHash = suppliedNameHash
                outcome = verification.outcome
                noDataReason = verification.noDataReason
                this.requestedBy = requestedBy
                verifiedAt = verification.verifiedAt
            },
        )
    }.onFailure().recoverWithUni { failure ->
        log.errorf(failure, "Failed to record VoP evidence for requester=%s; verification still returned", requestedBy)
        Uni.createFrom().voidItem()
    }
}
