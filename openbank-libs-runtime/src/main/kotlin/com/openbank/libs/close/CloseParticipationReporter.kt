// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.close

import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.Uni
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivers a [CloseParticipationReport] to the ledger. Each participant implements it with its own
 * REST client (`PUT /api/v1/ledger/accounting-days/{date}/participants/{name}`), because the OIDC
 * client a participant authenticates with is its own (#10486) and a library cannot name it.
 */
fun interface CloseParticipationSink {
    fun deliver(participant: String, report: CloseParticipationReport): Uni<Unit>
}

/**
 * The participant side of ADR-0342: sends a day's report and NEVER fails the job that calls it.
 *
 * A delivery failure is not retried here. The caller re-assembles and re-sends on its next tick
 * (the report is derived from persisted job runs, so nothing is lost), and while it is missing the
 * ledger holds the day in CUTOFF — the failure direction is "the close waits and pages", never
 * "the close passes without us". An identical report already delivered is not re-sent, so a
 * participant can re-offer the last few days every tick without growing the ledger's history.
 *
 * Metric `openbank.close.participation.reports{participant,outcome,delivered}`.
 */
class CloseParticipationReporter(
    private val participant: String,
    private val sink: CloseParticipationSink,
    private val meterRegistry: MeterRegistry?,
) {
    private val log = Logger.getLogger(CloseParticipationReporter::class.java)
    private val delivered = ConcurrentHashMap<java.time.LocalDate, CloseParticipationReport>()

    init {
        require(participant.isNotBlank()) { "participant name is required" }
    }

    /** True when [report] is (now or already) delivered; false when delivery failed. */
    fun report(report: CloseParticipationReport): Uni<Boolean> {
        if (delivered[report.businessDate] == report) return Uni.createFrom().item(true)
        return sink.deliver(participant, report)
            .map {
                delivered[report.businessDate] = report
                trimDelivered()
                count(report, true)
                log.infof(
                    "day-close participation %s for %s delivered: %s journals=%d items=%d",
                    participant,
                    report.businessDate,
                    report.outcome,
                    report.journalsPosted,
                    report.itemsProcessed,
                )
                true
            }
            .onFailure().recoverWithItem { e ->
                count(report, false)
                log.warnf(
                    e,
                    "day-close participation %s for %s NOT delivered (%s) — re-sent next tick; the ledger " +
                        "holds the day until it arrives",
                    participant,
                    report.businessDate,
                    report.outcome,
                )
                false
            }
    }

    private fun count(report: CloseParticipationReport, ok: Boolean) {
        meterRegistry?.counter(
            METRIC,
            "participant",
            participant,
            "outcome",
            report.outcome.name.lowercase(),
            "delivered",
            ok.toString(),
        )?.increment()
    }

    private fun trimDelivered() {
        if (delivered.size <= MAX_REMEMBERED) return
        delivered.keys.sorted().take(delivered.size - MAX_REMEMBERED).forEach { delivered.remove(it) }
    }

    companion object {
        const val METRIC = "openbank.close.participation.reports"
        private const val MAX_REMEMBERED = 31
    }
}
