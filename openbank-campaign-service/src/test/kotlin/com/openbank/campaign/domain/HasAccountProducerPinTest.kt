// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRule
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `SegmentRule.HasAccount` reads a JSON key by name out of an event another service produces, and
 * that coupling fails in the worst direction: rename `partyId` in account-service and this rule
 * stops matching anyone, silently. The evaluator is fail-closed, so a party it can no longer
 * resolve renders as "did not match" — indistinguishable from a correct answer, which is the exact
 * shape of #2891.
 *
 * Nothing else pins the two together. There is no schema registry for these topics, and the JSON
 * key exists only as a Kotlin property name on one side and a string literal on the other. So this
 * test reads the producer's source and asserts the name the rule depends on is still there.
 *
 * ON THE ASSUMPTION, stated rather than hidden: a per-service container build copies only its own
 * module, so the sibling source is legitimately absent there and the test cannot run. It is skipped
 * in that case, and this repository is right to distrust a skip — a skipped test reads as a pass.
 * The mitigation is that the full-fleet build, which is where this coupling can actually break,
 * always has both modules present. If that stops being true, this test stops being evidence.
 */
class HasAccountProducerPinTest {

    private val producer =
        File("../openbank-account-service/src/main/kotlin/com/openbank/account/domain/event/AccountEvents.kt")

    @Test
    fun `the account event still carries the party link this rule reads by name`() {
        assumeTrue(producer.isFile, "sibling module not in this checkout (per-service build) — nothing to pin against")
        val source = producer.readText()

        val created = source.substringAfter("data class AccountCreatedEvent(").substringBefore(") : DomainEvent")
        assertTrue(
            created.contains("val partyId:"),
            "AccountCreatedEvent no longer declares partyId, so SegmentRule.HasAccount now matches nobody — " +
                "and it would do so silently. Rename the key in the rule, or restore the field.",
        )
    }

    /**
     * The other half of the same coupling: the rule reads the key out of the payload of an event
     * whose OTHER variants do not carry it. If a future account event starts carrying `partyId`,
     * this test does not break — the rule simply gets more rows, which is correct. It breaks only
     * on the direction that matters.
     */
    @Test
    fun `the rule reads exactly the key the producer writes`() {
        assumeTrue(producer.isFile, "sibling module not in this checkout (per-service build)")
        val (where, _) = Segment("has-account", 1, listOf(SegmentRule.HasAccount)).toWhereClause()
        val keyInRule = Regex("JSONExtractString\\(payload, '([A-Za-z]+)'\\)").find(where)?.groupValues?.get(1)
        assertTrue(keyInRule == "partyId", "the rule reads '$keyInRule' — actual SQL: $where")
        assertTrue(
            producer.readText().contains("val $keyInRule:"),
            "the rule reads '$keyInRule', which AccountEvents.kt does not declare",
        )
    }
}
