// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.governance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The parser is the anti-drift barrier between rules.yaml and the agents that mirror its sections
 * in config — every convention it tolerates (inline comments, comment continuations inside list
 * entries, quoted values containing `#`) exists in the real rules.yaml today, so the fixtures
 * below mirror the real document's shape, not an idealised one.
 */
class RulesYamlSectionsTest {

    @Test
    fun `money-path services are parsed with inline and continuation comments dropped`() {
        val services = RulesYamlSections.moneyPathServices(
            """
            money_path_services:
              - openbank-ledger-service
              - openbank-sdd-service                  # issue #1478: posts a real debit
                                                       # continuation of the same comment
              - openbank-fx-service

            review:
              default_approvals: 1
            """.trimIndent().lines(),
        )

        assertThat(services)
            .containsExactlyInAnyOrder("openbank-ledger-service", "openbank-sdd-service", "openbank-fx-service")
    }

    @Test
    fun `money-path parse stops at the next top-level key`() {
        val services = RulesYamlSections.moneyPathServices(
            """
            money_path_services:
              - openbank-ledger-service
            review:
              default_approvals: 1
              money_path_approvals: 2
            """.trimIndent().lines(),
        )

        assertThat(services).containsExactly("openbank-ledger-service")
    }

    @Test
    fun `review approvals are parsed as a pair`() {
        val approvals = RulesYamlSections.reviewApprovals(
            """
            review:
              default_approvals: 1
              money_path_approvals: 2
            """.trimIndent().lines(),
        )

        assertThat(approvals).isEqualTo(1 to 2)
    }

    @Test
    fun `review approvals are null when a key is missing`() {
        assertThat(
            RulesYamlSections.reviewApprovals("review:\n  default_approvals: 1".lines()),
        ).isNull()
        assertThat(RulesYamlSections.reviewApprovals("other: 1".lines())).isNull()
    }

    @Test
    fun `a rule allowlist of topics is parsed, quoted values keep their hash signs`() {
        val topics = RulesYamlSections.allowlistValues(
            """
            rules:
              event_consumer_liveness:
                gate: event-consumer-liveness
                allowlist:
                  - topic: "openbank.pid.events"
                    reason: "dead code #7190"
                  - topic: "proposal-events"
                    reason: "ADR-0244, consumer-side only"
            """.trimIndent().lines(),
            ruleKey = "event_consumer_liveness",
            entryKey = "topic",
        )

        assertThat(topics).containsExactlyInAnyOrder("openbank.pid.events", "proposal-events")
    }

    @Test
    fun `a rule allowlist of edges is parsed from the same document shape`() {
        val edges = RulesYamlSections.allowlistValues(
            """
            rules:
              lineage_code_audit:
                gate: lineage-code-audit
                allowlist:
                  - edge: "openbank-copilot-service->openbank-fx-service:api"
                    reason: "shared CustomerEdgeRestClient"
            """.trimIndent().lines(),
            ruleKey = "lineage_code_audit",
            entryKey = "edge",
        )

        assertThat(edges).containsExactly("openbank-copilot-service->openbank-fx-service:api")
    }

    @Test
    fun `allowlist is empty when the rule or section is absent`() {
        assertThat(
            RulesYamlSections.allowlistValues("rules: {}".lines(), "event_consumer_liveness", "topic"),
        ).isEmpty()
        assertThat(
            RulesYamlSections.allowlistValues(
                """
                rules:
                  event_consumer_liveness:
                    gate: event-consumer-liveness
                """.trimIndent().lines(),
                "event_consumer_liveness",
                "topic",
            ),
        ).isEmpty()
    }

    @Test
    fun `unquoted allowlist values are comment-stripped`() {
        val topics = RulesYamlSections.allowlistValues(
            """
            rules:
              event_consumer_liveness:
                allowlist:
                  - topic: openbank.pid.events   # dead code
            """.trimIndent().lines(),
            ruleKey = "event_consumer_liveness",
            entryKey = "topic",
        )

        assertThat(topics).containsExactly("openbank.pid.events")
    }

    @Test
    fun `the real checked-in rules yaml parses to a non-empty money-path set and review pair`() {
        val configuredPath = requireNotNull(System.getProperty("openbank.test.governance-rules")) {
            "Run this test through Gradle: openbank.test.governance-rules must identify the checked-in rules.yaml"
        }
        val path = Path.of(configuredPath)
        assertThat(Files.isRegularFile(path))
            .describedAs("checked-in governance rules must exist at %s", path)
            .isTrue

        val lines = Files.readAllLines(path)
        assertThat(RulesYamlSections.moneyPathServices(lines)).isNotEmpty
        assertThat(RulesYamlSections.reviewApprovals(lines)).isNotNull
        assertThat(
            RulesYamlSections.allowlistValues(lines, "event_consumer_liveness", "topic"),
        ).isNotEmpty
        assertThat(
            RulesYamlSections.allowlistValues(lines, "lineage_code_audit", "edge"),
        ).isNotEmpty
    }
}
