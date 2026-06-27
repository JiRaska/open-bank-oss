// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.OpenAmlCaseCommand
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Adapter over [AmlServiceClient]. Maps the payment-boundary [OpenAmlCaseCommand] onto the aml-service
 * contract. Since a payment carries no resolved party, [OpenAmlCaseCommand.debtorAccountId] fills both
 * `partyId` and `accountId`; proper account→party resolution is a documented fast-follow (ADR-0032 §D).
 * Failures propagate so the use-case's best-effort wrapper can log them without flipping the verdict.
 */
@ApplicationScoped
class AmlCaseAdapter(@RestClient private val client: AmlServiceClient) : AmlCasePort {

    @Inject
    lateinit var self: AmlCaseAdapter

    override suspend fun openCase(command: OpenAmlCaseCommand) {
        self.openCaseWithResilience(command)
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(5_000)
    open suspend fun openCaseWithResilience(command: OpenAmlCaseCommand) {
        client.createCase(
            command.idempotencyKey,
            CreateAmlCaseRequest(
                partyId = command.debtorAccountId,
                accountId = command.debtorAccountId,
                transactionId = command.paymentId,
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
        const val SCREENING_TYPE = "TRANSACTION_MONITORING"
    }
}
