// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.OpenAmlCaseCommand
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Adapter over [AmlServiceClient]. Maps the conversion-boundary [OpenAmlCaseCommand] onto the
 * aml-service contract. Unlike the payment services, an FX conversion already carries a resolved
 * [OpenAmlCaseCommand.partyId], so `partyId`/`accountId` flow straight through.
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
                partyId = command.partyId,
                accountId = command.accountId,
                transactionId = command.conversionId,
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
