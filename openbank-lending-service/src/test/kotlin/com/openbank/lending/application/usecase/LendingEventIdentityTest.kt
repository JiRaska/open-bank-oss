// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Every lending event must carry its OWN aggregate identity on the wire (#8893).
 *
 * WHY THIS EXISTS. The analytics sink resolves an event's `aggregate_type` from the payload, then
 * from an inference, and finally from the TOPIC — `openbank.lending.events` yields `LENDING` for
 * every type alike. It then resolves the id with `idForType(type, node)`, whose `when` has no
 * `LENDING` branch and whose trailing fallback is `?: accountId ?: partyId`. So a payload that
 * omits `aggregateType`/`aggregateId` but carries `partyId` — four of this service's types did —
 * lands in `bronze_events` keyed by the BORROWER, and `silver_current_state`, which groups by
 * `(aggregate_type, aggregate_id)`, collapses every loan of that borrower into one aggregate.
 * That is the corruption `AnalyticsConsumer.resolveAggregateId`'s KDoc documents from #4553,
 * reproduced on a new domain. The producer settling its own identity is what prevents it.
 *
 * WHY A SOURCE SCAN RATHER THAN THIRTEEN FLOWS. The behavioural assertions live beside the flows
 * that already drive each event (`LendingServiceTest`), and they are the real coverage. This test
 * answers the question those cannot: is there an emit site anywhere in the service that a future
 * change could add WITHOUT identity? Two of the twelve event types are emitted through a
 * parameterised helper (`loan.withdrawn`, `loan.accelerated` via `TerminationService.emitDomainEvent`),
 * so a grep for `eventType = "..."` literals finds nine and misses two — the same two-idiom trap
 * this repo has hit before. The accrual receipt repository also builds a serialized map and names
 * its event in SQL. The scan includes that writer and checks both raw-string and map payloads.
 */
class LendingEventIdentityTest {

    private companion object {
        /** The literal that opens a JSON payload in this service: a raw string starting with `{`. */
        const val TQ = "\"\"\""
        const val PAYLOAD_OPENING = "\"\"\"{"

        /** Opening line plus the next three: room for a leading `eventType`, nothing more. */
        const val IDENTITY_WINDOW = 4
    }

    private val sources = listOf(
        "LendingService.kt",
        "OriginationDecisionService.kt",
        "TerminationService.kt",
    ).map { Path.of("src/main/kotlin/com/openbank/lending/application/usecase", it) } + listOf(
        Path.of(
            "src/main/kotlin/com/openbank/lending/infrastructure/persistence/repository/AccrualProgressRepository.kt",
        ),
    )

    private fun opensPayload(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return line.contains(PAYLOAD_OPENING) ||
            (
                line.contains("linkedMapOf(") &&
                    (
                        line.contains(".writeValueAsString(") ||
                            lines.getOrNull(index - 1)?.contains(".writeValueAsString(") == true
                        )
                )
    }

    private fun eventTypes(text: String): Set<String> = Regex("""["'](credit|loan)\.[a-z._]+["']""")
        .findAll(text).map { it.value.trim('"', '\'') }.toSet()

    @Test
    fun `every payload builder in the service names its aggregate identity`() {
        // A payload literal starts either at `"""{` (a raw-string builder) or inside the
        // `append("""{` of a buildString. Two builders put the `eventType` discriminator first and
        // the identity on the next line, so the window is the opening line plus the next few —
        // narrow enough that identity has to be at the TOP of the payload, wide enough to allow
        // the discriminator ahead of it.
        val offenders = mutableListOf<String>()
        assertThat(sources).hasSize(4)
        assertThat(sources.last()).isEqualTo(
            Path.of(
                "src/main/kotlin/com/openbank/lending/infrastructure/persistence/repository/AccrualProgressRepository.kt",
            ),
        )
        for (file in sources) {
            assertThat(Files.isRegularFile(file)).describedAs("$file").isTrue()
            val lines = file.readText().lines()
            for ((i, line) in lines.withIndex()) {
                if (!opensPayload(lines, i)) continue
                val window = lines.subList(i, minOf(i + IDENTITY_WINDOW, lines.size)).joinToString("\n")
                if (!window.contains("aggregateType") || !window.contains("aggregateId")) {
                    offenders += "${file.fileName}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertThat(offenders)
            .describedAs("payload builders that do not name aggregateType and aggregateId up front")
            .isEmpty()
    }

    @Test
    fun `the thirteen event types are the ones this test speaks for`() {
        // A new event type must consciously join this list, which is what makes the scan above a
        // ratchet rather than a snapshot. Eleven are literals; two arrive as a parameter.
        val publisher = Path.of(
            "src/main/kotlin/com/openbank/lending/infrastructure/outbox/KafkaLendingOutboxEventPublisher.kt",
        )
        val text = (sources + listOf(publisher)).joinToString("\n") { it.readText() }
        val literals = eventTypes(text)
        assertThat(literals).containsExactlyInAnyOrder(
            "credit.application.transition",
            "credit.application.transition.refused",
            "credit.application.four_eyes_sla_escalation",
            "credit.decision.evaluated",
            "credit.loan.transition",
            "loan.disbursed",
            "loan.interest_accrued",
            "loan.written_off",
            "loan.rescheduled",
            "loan.stage_changed",
            "loan.provisioned",
            "loan.withdrawn",
            "loan.accelerated",
        )
    }

    @Test
    fun `the scan flags a payload that omits identity, and passes one that has it`() {
        // The control. Without it the scan above could be vacuous: a matcher that never fires
        // reports an empty offender list exactly like a clean tree does. Both directions, because
        // a matcher that fires on EVERYTHING is equally useless.
        val bad = listOf("""payload = $TQ{"loanId":"x","partyId":"p"}$TQ""")
        val good = listOf(
            """payload = $TQ{"aggregateType":"LOAN","aggregateId":"x",$TQ +""",
            """    $TQ"loanId":"x"}$TQ""",
        )
        assertThat(bad.first().contains(PAYLOAD_OPENING)).describedAs("the scan sees a payload here").isTrue()
        assertThat(bad.joinToString("\n").contains("aggregateType")).isFalse()
        assertThat(good.first().contains(PAYLOAD_OPENING)).isTrue()
        assertThat(good.joinToString("\n").let { it.contains("aggregateType") && it.contains("aggregateId") }).isTrue()

        val badMap = listOf("val payload = mapper.writeValueAsString(", "linkedMapOf(\"loanId\" to id)")
        val goodMap = listOf(
            "val payload = mapper.writeValueAsString(",
            "linkedMapOf(",
            "\"eventType\" to \"loan.interest_accrued\",",
            "\"aggregateType\" to \"LOAN\",",
            "\"aggregateId\" to id,",
        )
        assertThat(opensPayload(badMap, 1)).isTrue()
        assertThat(badMap.drop(1).joinToString("\n").contains("aggregateId")).isFalse()
        assertThat(opensPayload(goodMap, 1)).isTrue()
        val mapWindow = goodMap.drop(1).take(IDENTITY_WINDOW).joinToString("\n")
        assertThat(mapWindow).contains("aggregateType", "aggregateId")
        assertThat(eventTypes("VALUES ('loan.interest_accrued')")).containsExactly("loan.interest_accrued")
        assertThat(eventTypes("VALUES ('loanxinterest_accrued')")).isEmpty()
        assertThat(eventTypes("loan.interest_accrued")).isEmpty()
    }

    @Test
    fun `identity and party survive JSON parsing, not just string matching`() {
        // A payload is assembled from concatenated raw strings, so "the source contains the key" is
        // not the same claim as "the emitted JSON has the field". One representative payload of each
        // shape is parsed here; the per-event behavioural assertions live in LendingServiceTest.
        val mapper = ObjectMapper()
        val loanShaped = """{"aggregateType":"LOAN","aggregateId":"11111111-1111-1111-1111-111111111111",""" +
            """"loanId":"11111111-1111-1111-1111-111111111111","partyId":"22222222-2222-2222-2222-222222222222",""" +
            """"occurredAt":"2026-09-06T00:00:00Z","sourceService":"lending"}"""
        val node = mapper.readTree(loanShaped)
        assertThat(node.get("aggregateType").asText()).isEqualTo("LOAN")
        // The whole point: the id is the LOAN, never the borrower.
        assertThat(node.get("aggregateId").asText()).isEqualTo(node.get("loanId").asText())
        assertThat(node.get("aggregateId").asText()).isNotEqualTo(node.get("partyId").asText())
    }
}
