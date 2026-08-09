// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.StepCondition
import com.openbank.campaign.domain.model.StopCondition
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ADR-0200 D1 stop condition (#3585) is a domain rule, not workflow plumbing: the workflow
 * only supplies the observable send count, the definition itself decides whether the cap is
 * reached. Pinning the boundary here means a journey stops AT the cap, never one send past it.
 */
class StopConditionTest {

    @Test
    fun `cap must be at least one`() {
        assertThrows(IllegalArgumentException::class.java) { StopCondition(0) }
    }

    @Test
    fun `below the cap the journey continues`() {
        assertFalse(StopCondition(2).reachedBy(0))
        assertFalse(StopCondition(2).reachedBy(1))
    }

    @Test
    fun `at the cap the journey stops before the next step`() {
        assertTrue(StopCondition(2).reachedBy(2))
    }

    @Test
    fun `past the cap stops too — a re-enrolled party is already over`() {
        assertTrue(StopCondition(2).reachedBy(5))
    }
}

/**
 * The ADR-0200 D1 branch condition (#3585). The vocabulary is deliberately narrow — it names the
 * ADR-0239 delivery status and nothing else — so the only thing to pin here is that the honest
 * resting state `PENDING`, and a missing predecessor, both count as NOT confirmed.
 */
class StepConditionTest {

    @Test
    fun `IF_PREVIOUS_CONFIRMED holds only for a confirmed delivery`() {
        assertTrue(StepCondition.IF_PREVIOUS_CONFIRMED.holdsFor(DeliveryStatus.CONFIRMED))
        assertFalse(StepCondition.IF_PREVIOUS_CONFIRMED.holdsFor(DeliveryStatus.PENDING))
        assertFalse(StepCondition.IF_PREVIOUS_CONFIRMED.holdsFor(DeliveryStatus.FAILED))
        assertFalse(StepCondition.IF_PREVIOUS_CONFIRMED.holdsFor(null))
    }

    @Test
    fun `IF_PREVIOUS_NOT_CONFIRMED is its exact complement`() {
        listOf(DeliveryStatus.CONFIRMED, DeliveryStatus.PENDING, DeliveryStatus.FAILED, null).forEach {
            assertTrue(
                StepCondition.IF_PREVIOUS_CONFIRMED.holdsFor(
                    it,
                ) != StepCondition.IF_PREVIOUS_NOT_CONFIRMED.holdsFor(it),
            )
        }
    }
}
