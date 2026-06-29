// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.retention

import com.openbank.cardissuance.application.port.out.CardRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * Enforces the card PII retention policy (ADR-0118 §5, AML Act §16).
 *
 * Cards whose expiry date is older than [retentionYears] years have their cardholder PII
 * (cardholderName, embossedName) anonymised in-place. The card row itself is kept for
 * AML audit purposes (the masked PAN and transaction history remain in ledger/transaction-service).
 *
 * Run daily at 03:00 UTC (configurable). `concurrentExecution = SKIP` prevents overlap
 * on slow runs. Enable [dryRun] to log the count without committing changes.
 */
@ApplicationScoped
class CardPiiRetentionScheduler(
    private val cardRepository: CardRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.retention.card-pii.retention-years", defaultValue = "5")
    private val retentionYears: Long,
    @ConfigProperty(name = "openbank.retention.card-pii.dry-run", defaultValue = "false")
    private val dryRun: Boolean,
    @ConfigProperty(name = "openbank.retention.card-pii.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {

    private val log = Logger.getLogger(CardPiiRetentionScheduler::class.java)

    @Scheduled(
        cron = "{openbank.retention.card-pii.cron:0 0 3 * * ?}",
        concurrentExecution = SKIP,
    )
    suspend fun enforceRetention() {
        if (!enabled) return

        val cutoff = LocalDate.now(clock).minusYears(retentionYears)

        if (dryRun) {
            log.infof(
                "[retention] DRY-RUN: would anonymise card PII for cards with expiry_date < %s (retention %dy)",
                cutoff,
                retentionYears,
            )
            return
        }

        val count = cardRepository.anonymizeExpiredCardPii(cutoff)
        if (count > 0) {
            log.infof(
                "[retention] Anonymised PII for %d card(s) with expiry_date < %s (ADR-0118 §5)",
                count,
                cutoff,
            )
        }
    }
}
