// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.anacredit.infrastructure.persistence.LoanExposureFact
import com.openbank.anacredit.infrastructure.persistence.LoanExposureFactStore
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal const val LOAN_EXPOSURE_FACT_EVENT = "loan.exposure_fact"

@ApplicationScoped
class LoanExposureFactConsumer(private val facts: LoanExposureFactStore) {

    private val log = Logger.getLogger(LoanExposureFactConsumer::class.java)

    suspend fun consume(node: JsonNode, payload: String) {
        val version = node.path("eventVersion")
        require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) {
            "Unsupported loan exposure fact version"
        }
        val loanId = UUID.fromString(node.requiredText("loanId"))
        require(node.requiredText("sourceService") == "lending")
        require(node.requiredText("aggregateType") == "LOAN")
        require(UUID.fromString(node.requiredText("aggregateId")) == loanId)
        UUID.fromString(node.requiredText("applicationId"))
        UUID.fromString(node.requiredText("partyId"))
        val reference = node.requiredText("sourceReference")
        require(reference == "loan:$loanId:${changeReference(node)}") { "Mismatched loan exposure fact reference" }
        require(node.requiredText("currency").matches(CURRENCY_CODE))
        node.requireNonnegativeAmount("originalPrincipal")
        val effectiveAt = Instant.parse(node.requiredText("effectiveAt"))
        require(Instant.parse(node.requiredText("occurredAt")) == effectiveAt)
        val synthetic = node.path("synthetic")
        require(synthetic.isMissingNode || synthetic.isBoolean)
        val fact = LoanExposureFact(
            eventId = UUID.fromString(node.requiredText("eventId")),
            sourceReference = reference,
            loanId = loanId,
            effectiveAt = effectiveAt.atOffset(ZoneOffset.UTC),
            // Keep the original payload, including fields this version does not interpret.
            payload = payload,
        )
        EventRetry.withRetry(
            log,
            LOAN_EXPOSURE_FACT_EVENT,
            loanId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) {
            facts.append(fact)
        }
    }

    private fun changeReference(node: JsonNode): String {
        val status = node.requiredText("loanStatus")
        return when (node.requiredText("kind")) {
            "DISBURSED" -> {
                Instant.parse(node.requiredText("cashConfirmedAt"))
                "disbursement"
            }
            "REPAID" -> {
                val installmentId = UUID.fromString(node.requiredText("installmentId"))
                node.requireNonnegativeAmount("principalRepaid")
                node.requireNonnegativeAmount("interestRepaid")
                node.requireNonnegativeAmount("unpaidScheduledPrincipal")
                "installment:$installmentId"
            }
            "RESCHEDULED" -> {
                val version = node.path("scheduleVersion")
                require(version.isIntegralNumber && version.canConvertToLong() && version.longValue() >= 0)
                node.requireNonnegativeAmount("unpaidScheduledPrincipal")
                "schedule:${version.longValue()}"
            }
            "ENDED" -> {
                require(status in TERMINAL_STATUSES) { "Exposure end requires a terminal loan status" }
                "status:$status"
            }
            else -> throw IllegalArgumentException("Unknown loan exposure fact kind")
        }
    }

    private fun JsonNode.requiredText(field: String): String {
        val value = path(field).textValue()?.takeIf(String::isNotBlank)
        return requireNotNull(value) { "Missing loan exposure fact field $field" }
    }

    private fun JsonNode.requireNonnegativeAmount(field: String) {
        val amount = path(field)
        require(amount.isNumber && amount.decimalValue().signum() >= 0) {
            "Invalid loan exposure fact amount $field"
        }
    }

    private companion object {
        val CURRENCY_CODE = Regex("[A-Z]{3}")
        val TERMINAL_STATUSES = setOf("CLOSED", "UNWOUND", "WRITTEN_OFF")
    }
}
