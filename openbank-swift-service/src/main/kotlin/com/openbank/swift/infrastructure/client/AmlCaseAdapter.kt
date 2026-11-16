// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.swift.application.port.out.AmlCasePort
import com.openbank.swift.application.port.out.OpenAmlCaseCommand
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Adapter over [AmlServiceClient]. swift-service has no account-service client, so the case's
 * `partyId` carries the ordering customer's ACCOUNT id — the same documented compromise
 * sepa-payment falls back to when its party lookup fails (#8505). Failures propagate to the
 * caller's best-effort wrapper and never change a screening verdict.
 */
@ApplicationScoped
class AmlCaseAdapter(@RestClient private val client: AmlServiceClient) : AmlCasePort {

    @Inject
    lateinit var self: AmlCaseAdapter

    override suspend fun openCase(command: OpenAmlCaseCommand) {
        self.openCaseWithResilience(command)
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(CALL_TIMEOUT_MS)
    open suspend fun openCaseWithResilience(command: OpenAmlCaseCommand) {
        client.createCase(
            command.idempotencyKey,
            CreateAmlCaseRequest(
                partyId = command.orderingCustomerAccountId,
                accountId = command.orderingCustomerAccountId,
                transactionId = command.messageId,
                customerReference = command.customerReference,
                screeningType = SCREENING_TYPE,
                riskLevel = command.riskLevel.name,
                alertCode = command.alertCode,
                alertDetail = command.alertDetail,
                matchedEntity = command.matchedEntity,
            ),
        ).awaitSuspending()
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 5_000L
        const val SCREENING_TYPE = "TRANSACTION_MONITORING"
    }
}
