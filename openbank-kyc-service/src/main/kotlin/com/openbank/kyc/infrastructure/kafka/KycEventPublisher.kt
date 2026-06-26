// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.domain.model.KycCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.time.Clock
import java.time.Instant

@ApplicationScoped
class KycEventPublisher {

    @Inject
    @Channel("kyc-events-out")
    lateinit var emitter: Emitter<String>

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    fun publishCaseOpened(case: KycCase) = publish("KYC_CASE_OPENED", case)
    fun publishCaseStatusChanged(case: KycCase) = publish("KYC_CASE_STATUS_CHANGED", case)
    fun publishCaseApproved(case: KycCase) = publish("KYC_CASE_APPROVED", case)
    fun publishCaseRejected(case: KycCase) = publish("KYC_CASE_REJECTED", case)

    private fun publish(eventType: String, case: KycCase) {
        val event = mapOf(
            "eventType" to eventType,
            "kycCaseId" to case.id,
            "partyId" to case.partyId,
            "status" to case.status,
            "riskLevel" to case.riskLevel,
            "occurredAt" to Instant.now(clock),
        )
        emitter.send(objectMapper.writeValueAsString(event))
    }
}
