// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.DisclosureSummary
import com.openbank.context.domain.AuthorityHistory
import jakarta.ws.rs.core.Response

/** Explicit response inventory. An unrecognized entity fails closed instead of silently omitting evidence. */
internal object ContextDisclosureSummaries {
    fun response(response: Response): DisclosureSummary {
        if (response.status !in DisclosureSummary.HTTP_SUCCESS_MIN..DisclosureSummary.HTTP_SUCCESS_MAX) {
            return DisclosureSummary.absent(response.status)
        }
        return when (val entity = response.entity) {
            is AuthorityHistory -> DisclosureSummary.materialized(
                entity.observations.map { it.evidenceRef },
                entity.observations.size,
                entity.truncated,
                entity.observations.map { "${it.evidenceRef}:${it.contentHash}" },
                response.status,
            )
            is AmlCaseHistory -> aml(entity, response.status)
            is AmlCaseNetwork -> {
                val histories = listOf(entity.root) + entity.related
                DisclosureSummary.materialized(
                    histories.flatMap { history -> history.observations.map { it.evidenceRef } },
                    histories.sumOf { it.observations.size },
                    histories.any { it.truncated },
                    histories.flatMap { history -> history.observations.map { "${it.evidenceRef}:${it.contentHash}" } },
                    response.status,
                )
            }
            is KybObservationHistory -> DisclosureSummary.materialized(
                entity.observations.map { it.observationId.toString() },
                entity.observations.size,
                entity.truncated,
                entity.observations.map { "${it.observationId}:${it.revision}:${it.sourceSha256}" },
                response.status,
            )
            is FraudCaseNetwork -> {
                val cases = listOf(entity.root) + entity.related.map { it.evidence }
                val refs = cases.flatMap(::fraudRefs).distinct()
                DisclosureSummary.materialized(
                    refs,
                    refs.size,
                    entity.candidateTruncated,
                    cases.map { "${it.caseId}:${it.revision}:${it.scoreId}" },
                    response.status,
                )
            }
            null -> DisclosureSummary.materialized(
                emptyList(),
                0,
                false,
                listOf("status:${response.status}"),
                response.status,
            )
            else -> error("No disclosure audit summary for ${entity::class.simpleName}")
        }
    }

    fun aml(history: AmlCaseHistory?, status: Int = 200): DisclosureSummary = if (history == null) {
        DisclosureSummary.absent(DisclosureSummary.HTTP_NOT_FOUND)
    } else {
        DisclosureSummary.materialized(
            history.observations.map { it.evidenceRef },
            history.observations.size,
            history.truncated,
            history.observations.map { "${it.evidenceRef}:${it.contentHash}" },
            status,
        )
    }

    fun fraud(related: FraudRelatedCase?): DisclosureSummary = if (related == null) {
        DisclosureSummary.absent(DisclosureSummary.HTTP_NOT_FOUND)
    } else {
        val refs = fraudRefs(related.evidence)
        DisclosureSummary.materialized(
            refs,
            refs.size,
            false,
            listOf("${related.evidence.caseId}:${related.evidence.revision}:${related.evidence.scoreId}"),
        )
    }

    private fun fraudRefs(snapshot: FraudCaseSourceSnapshot): List<String> = listOfNotNull(
        snapshot.caseId?.let { "fraud-case:$it" },
        snapshot.scoreId?.let { "score:$it" },
        snapshot.accountId?.let { "account:$it" },
        snapshot.counterpartyId?.let { "counterparty:$it" },
    )
}
