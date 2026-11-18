// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.client

import com.openbank.sdd.application.port.out.AmlCasePort
import com.openbank.sdd.application.port.out.OpenAmlCaseCommand
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Adapter over [AmlServiceClient]. The SDD mandate carries the debtor ACCOUNT, not its party, and sdd
 * holds no account-to-party lookup grant yet, so the case's required `partyId` carries the account id
 * — the shape sepa-payment fixed in #8505. It is logged loudly so those rows are identifiable; the
 * resolution is a follow-up recorded in docs/threat-models/openbank-sdd-service.md.
 */
@ApplicationScoped
class AmlCaseAdapter(@RestClient private val client: AmlServiceClient) : AmlCasePort {

    @Inject
    lateinit var self: AmlCaseAdapter

    private val log = Logger.getLogger(AmlCaseAdapter::class.java)

    override fun openCase(command: OpenAmlCaseCommand): Uni<Void> =
        Uni.createFrom().deferred { self.openCaseWithResilience(command) }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(CALL_TIMEOUT_MS)
    fun openCaseWithResilience(command: OpenAmlCaseCommand): Uni<Void> {
        log.warnf(
            "aml.case.party_unresolved mandate_id=%s debtor_account_id=%s — party_id carries the ACCOUNT id",
            command.mandateId,
            command.debtorAccountId,
        )
        return client.createCase(
            command.idempotencyKey,
            CreateAmlCaseRequest(
                partyId = command.debtorAccountId,
                accountId = command.debtorAccountId,
                transactionId = null,
                customerReference = command.customerReference,
                screeningType = SCREENING_TYPE,
                riskLevel = command.riskLevel.name,
                alertCode = command.alertCode,
                alertDetail = command.alertDetail,
                matchedEntity = command.matchedEntity,
            ),
        ).replaceWithVoid()
    }

    private companion object {
        const val SCREENING_TYPE = "TRANSACTION_MONITORING"
    }
}

/** Upper bound on one call to the compliance service, retries included per attempt. */
private const val CALL_TIMEOUT_MS = 5_000L
