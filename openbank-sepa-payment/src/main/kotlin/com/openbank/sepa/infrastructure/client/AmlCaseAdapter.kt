// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.AccountLookupPort
import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.OpenAmlCaseCommand
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Adapter over [AmlServiceClient]. Maps the payment-boundary [OpenAmlCaseCommand] onto the aml-service
 * contract. The case's required `partyId` is resolved from the debtor account via [AccountLookupPort]
 * (the SEPA rail's port of the domestic #3274 fix, #8505); before that, `debtorAccountId` filled both
 * `partyId` and `accountId`, so every SEPA-rail case pointed at a party that does not exist and no join
 * to the party register resolved. Failures propagate so the use-case's best-effort wrapper can log them
 * without flipping the verdict.
 */
@ApplicationScoped
class AmlCaseAdapter(@RestClient private val client: AmlServiceClient) : AmlCasePort {

    @Inject
    lateinit var self: AmlCaseAdapter

    @Inject
    lateinit var accounts: AccountLookupPort

    private val log = Logger.getLogger(AmlCaseAdapter::class.java)

    override suspend fun openCase(command: OpenAmlCaseCommand) {
        self.openCaseWithResilience(command)
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(5_000)
    open suspend fun openCaseWithResilience(command: OpenAmlCaseCommand) {
        // #8505 (the SEPA twin of #3274): the command carries only `debtorAccountId`, and this used
        // to fill the case's required `partyId` with it — so every SEPA-rail case pointed at a party
        // that does not exist and no join to the party register resolved. `party_id` is `not null`,
        // so the column read as populated and healthy, and nothing detected that the UUID came from
        // the wrong table.
        //
        // On a failed lookup the account id is still sent, because the alternative is losing an
        // AML case over a resolution outage — but it is now LOUD instead of silent, so the rows
        // that carry the old shape are identifiable rather than indistinguishable.
        val partyId = accounts.findPartyByAccountId(command.debtorAccountId)
        if (partyId == null) {
            log.warnf(
                "aml.case.party_unresolved payment_id=%s debtor_account_id=%s — opening the case with the " +
                    "ACCOUNT id in party_id; a join to the party register will not resolve it (#8505)",
                command.paymentId,
                command.debtorAccountId,
            )
        }
        client.createCase(
            command.idempotencyKey,
            CreateAmlCaseRequest(
                partyId = partyId ?: command.debtorAccountId,
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
