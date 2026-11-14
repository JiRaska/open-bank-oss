// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.HoldDecisionCommand
import com.openbank.aml.application.port.`in`.HoldDecisionUseCase
import com.openbank.aml.application.port.`in`.WithdrawHoldDecisionCommand
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.port.out.HoldDecisionRepository
import com.openbank.aml.domain.model.AmlCase
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * ADR-0374 point 3: the two steps of a held payment's decision. The rule itself is in [AmlCase]; its
 * refusals are conflicts (409). A proposal writes no outbox row; only the confirmation emits the event.
 */
@ApplicationScoped
class HoldDecisionService(
    private val amlCaseRepository: AmlCaseRepository,
    private val holdDecisions: HoldDecisionRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : HoldDecisionUseCase {
    override suspend fun proposeHoldDecision(command: HoldDecisionCommand): AmlCase {
        val amlCase = amlCaseRepository.findById(command.caseId) ?: throw AmlCaseNotFoundException(command.caseId)
        val proposed = amlCase.proposeHoldDecision(command.outcome, command.reason, command.analyst, Instant.now(clock))
        return holdDecisions.writeHoldDecision(amlCase, proposed, event = null)
    }

    override suspend fun confirmHoldDecision(command: HoldDecisionCommand): AmlCase {
        val amlCase = amlCaseRepository.findById(command.caseId) ?: throw AmlCaseNotFoundException(command.caseId)
        val decided = amlCase.confirmHoldDecision(command.outcome, command.reason, command.analyst, Instant.now(clock))
        return holdDecisions.writeHoldDecision(amlCase, decided, objectMapper.statusChangedEvent(amlCase, decided))
    }

    override suspend fun withdrawHoldDecision(command: WithdrawHoldDecisionCommand): AmlCase {
        val amlCase = amlCaseRepository.findById(command.caseId) ?: throw AmlCaseNotFoundException(command.caseId)
        val withdrawn = amlCase.withdrawHoldDecision(command.analyst, Instant.now(clock))
        return holdDecisions.writeHoldDecision(amlCase, withdrawn, event = null)
    }
}
