// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

import io.quarkus.runtime.Startup
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import java.util.Optional
import java.util.UUID

enum class LedgerBookKind { BANK, PENSION_COMPANY, PENSION_FUND }

@ConfigMapping(prefix = "openbank.ledger.book")
interface LedgerBookConfig {
    @WithDefault("BANK")
    fun kind(): LedgerBookKind
    fun id(): Optional<UUID>
}

/** Configuration changes cannot reassign a database once its first pension receipt binds it. */
@Startup
@ApplicationScoped
class PensionBookIdentity(config: LedgerBookConfig, participants: CloseParticipantsConfig, jobs: CloseReadinessConfig) {
    val kind: LedgerBookKind = config.kind()
    val bookId: UUID? = config.id().orElse(null)

    init {
        require((kind == LedgerBookKind.BANK) == (bookId == null)) {
            "A pension book requires an explicit id; the bank must not carry a pension book id"
        }
        if (kind != LedgerBookKind.BANK) {
            val declared = participants.participants().keys
            require(
                "pension-fund" in declared &&
                    declared.all { it.startsWith("pension-") } &&
                    jobs.requiredJobs().keys == declared,
            ) {
                "Pension book needs its own participant and job declarations, including pension-fund"
            }
            require(jobs.requiredJobs().values.none { it.isEmpty() }) { "Pension close jobs must be declared" }
            val sourceJobs = if (kind == LedgerBookKind.PENSION_FUND) {
                setOf("nav", "management-fees")
            } else {
                setOf("management-fees")
            }
            require(jobs.requiredJobs().getValue("pension-fund").containsAll(sourceJobs)) {
                "Pension source completion must exercise $sourceJobs"
            }
            require(
                participants.participants().getValue("pension-fund").principal() ==
                    "service-account-openbank-pension-fund",
            ) { "Pension source completion needs its own verified client" }
        }
    }
}
