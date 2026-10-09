// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.application.usecase

import com.openbank.sdd.application.port.`in`.ListMandatesUseCase
import com.openbank.sdd.application.port.`in`.ManageMandateUseCase
import com.openbank.sdd.application.port.`in`.RegisterMandateCommand
import com.openbank.sdd.application.port.`in`.RegisterMandateUseCase
import com.openbank.sdd.application.port.out.DebtorAccountOwnershipPort
import com.openbank.sdd.domain.model.MandateInitiatorScope
import com.openbank.sdd.domain.model.SddMandate
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** ADR-0335 D6: the caller may not register or cancel this mandate (403). */
class MandateScopeViolationException(message: String) : RuntimeException(message)

/**
 * Register / cancel under the caller's [MandateInitiatorScope] (ADR-0335 D6). A General caller
 * passes straight through. A scoped caller is held to its creditor identifier and, on register,
 * to a debtor account that account-service verifies the stated subject party owns, holds active,
 * and that IS the account being mandated. Every refusal happens before anything is persisted.
 */
@ApplicationScoped
class ScopedMandateService(
    private val register: RegisterMandateUseCase,
    private val manage: ManageMandateUseCase,
    private val list: ListMandatesUseCase,
    private val ownership: DebtorAccountOwnershipPort,
) {
    fun register(
        command: RegisterMandateCommand,
        subjectPartyId: UUID?,
        scope: MandateInitiatorScope,
    ): Uni<SddMandate> {
        if (scope !is MandateInitiatorScope.Scoped) return register.register(command)
        if (!scope.ownsCreditor(command.creditorIdentifier)) {
            return refuse("creditor identifier is outside the caller's scope")
        }
        val party = subjectPartyId
            ?: return Uni.createFrom().failure(IllegalArgumentException("partyId is required for this caller"))
        return ownership.verify(command.debtorIban, party).flatMap { verdict ->
            if (verdict.owned && verdict.active && verdict.accountId == command.accountId) {
                register.register(command)
            } else {
                refuse("debtor account is not an active account of the subject party")
            }
        }
    }

    fun cancel(mandateId: UUID, scope: MandateInitiatorScope): Uni<SddMandate> {
        if (scope !is MandateInitiatorScope.Scoped) return manage.cancel(mandateId)
        return list.get(mandateId).flatMap { mandate ->
            val mine = scope.ownsCreditor(mandate.creditorIdentifier)
            if (mine) manage.cancel(mandateId) else refuse("mandate is outside the caller's scope")
        }
    }

    private fun <T> refuse(reason: String): Uni<T> = Uni.createFrom().failure(MandateScopeViolationException(reason))
}
