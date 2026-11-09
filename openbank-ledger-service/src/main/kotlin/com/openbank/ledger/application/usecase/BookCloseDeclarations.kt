// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

/** Separate property trees prevent a pension deployment from inheriting the bank's map entries. */
@ConfigMapping(prefix = "openbank.ledger.close")
interface BankCloseParticipants {
    @WithDefault("shadow")
    fun participantsMode(): String
    fun participants(): Map<String, CloseParticipantsConfig.Participant>
}

@ConfigMapping(prefix = "openbank.ledger.close.readiness")
interface BankCloseReadiness {
    fun requiredJobs(): Map<String, Set<String>>
}

@ConfigMapping(prefix = "openbank.ledger.book.close")
interface PensionCloseDeclarations {
    @WithDefault("off")
    fun participantsMode(): String
    fun participants(): Map<String, CloseParticipantsConfig.Participant>
    fun requiredJobs(): Map<String, Set<String>>
}

@ApplicationScoped
class BookCloseParticipants(
    book: LedgerBookConfig,
    private val bank: BankCloseParticipants,
    private val pension: PensionCloseDeclarations,
) : CloseParticipantsConfig {
    private val isBank = book.kind() == LedgerBookKind.BANK
    override fun participantsMode(): String = if (isBank) bank.participantsMode() else pension.participantsMode()
    override fun participants(): Map<String, CloseParticipantsConfig.Participant> =
        if (isBank) bank.participants() else pension.participants()
}

@ApplicationScoped
class BookCloseReadiness(
    book: LedgerBookConfig,
    private val bank: BankCloseReadiness,
    private val pension: PensionCloseDeclarations,
) : CloseReadinessConfig {
    private val isBank = book.kind() == LedgerBookKind.BANK
    override fun requiredJobs(): Map<String, Set<String>> = if (isBank) bank.requiredJobs() else pension.requiredJobs()
}
