// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TopicProducersTest {

    /**
     * The four the segment derivation got wrong, pinned by value. These are not a sample: two of
     * them name a module that does not exist under the derivation, and one re-creates the #5902
     * lending spelling split.
     */
    @Test
    fun `a topic resolves to the module that declares its outgoing channel`() {
        assertEquals("card-issuance-service", TopicProducers.sourceService("openbank.cards.events"))
        assertEquals("standing-order-service", TopicProducers.sourceService("openbank.standing-orders.order.event"))
        assertEquals("lending-service", TopicProducers.sourceService("openbank.lending.events"))
        assertEquals("fx-service", TopicProducers.sourceService("openbank.fx.conversion.completed"))
        assertEquals("transaction-service", TopicProducers.sourceService("openbank.transactions.transaction.initiated"))
        assertEquals("balance-service", TopicProducers.sourceService("openbank.balance.events"))
    }

    /**
     * The convention `check-source-service-convention.py` enforces on producers: the module
     * directory name WITHOUT the `openbank-` prefix. A row carrying the prefix would attribute the
     * same producer under a second spelling, which is the split this table exists to prevent — and
     * `bronze_events` already holds the evidence of that split from the old derivation.
     */
    @Test
    fun `no row carries the openbank- prefix`() {
        val offenders = TopicProducers.mappedTopics
            .mapNotNull { t -> TopicProducers.sourceService(t)?.let { t to it } }
            .filter { (_, svc) -> svc.startsWith("openbank-") }
        assertTrue(offenders.isEmpty(), "rows with the forbidden prefix: $offenders")
    }

    /**
     * Null beats a confident guess. The caller records `unknown` for a null, which is countable;
     * the derivation this replaced produced a plausible name for a module nobody has, which is not.
     */
    @Test
    fun `an unknown topic resolves to null rather than a derived guess`() {
        assertNull(TopicProducers.sourceService("openbank.not-a-real-domain.events"))
        assertNull(TopicProducers.sourceService(""))
        assertNull(TopicProducers.sourceService(null))
    }

    @Test
    fun `every mapped topic resolves and no value is blank`() {
        assertTrue(TopicProducers.mappedTopics.isNotEmpty())
        for (t in TopicProducers.mappedTopics) {
            val svc = TopicProducers.sourceService(t)
            assertTrue(!svc.isNullOrBlank(), "blank producer for $t")
        }
    }
}
