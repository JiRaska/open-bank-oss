// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class FlagDefinitionTest {

    private val now = Instant.parse("2026-06-06T00:00:00Z")

    private fun def(
        key: String = "new-router",
        classification: FlagClassification = FlagClassification.FEATURE,
        owner: String = "payments-team",
        expiresAt: Instant? = now.plus(30, ChronoUnit.DAYS),
        fourEyes: Boolean = classification == FlagClassification.MONEY_PATH,
    ) = FlagDefinition("new-router".let { key }, "desc", classification, owner, expiresAt, fourEyes)

    @Test
    fun `money-path classification defaults fourEyes to true`() {
        val d = FlagDefinition("x-flag", "d", FlagClassification.MONEY_PATH, "team", null)
        assertThat(d.fourEyes).isTrue()
    }

    @Test
    fun `a well-formed feature flag validates clean`() {
        assertThat(def().validate(now)).isEmpty()
    }

    @Test
    fun `isExpired is true only after expiry`() {
        val d = def(expiresAt = now.minus(1, ChronoUnit.DAYS))
        assertThat(d.isExpired(now)).isTrue()
        assertThat(def(expiresAt = now.plus(1, ChronoUnit.DAYS)).isExpired(now)).isFalse()
        assertThat(def(expiresAt = null).isExpired(now)).isFalse()
    }

    @Test
    fun `expired flag is a violation`() {
        val v = def(expiresAt = now.minus(1, ChronoUnit.DAYS)).validate(now)
        assertThat(v).anyMatch { it.contains("expired") }
    }

    @Test
    fun `non kebab-case key is rejected`() {
        assertThat(def(key = "New_Router").validate(now)).anyMatch { it.contains("kebab-case") }
        assertThat(def(key = "").validate(now)).anyMatch { it.contains("kebab-case") }
        assertThat(def(key = "ab-12-cd").validate(now)).noneMatch { it.contains("kebab-case") }
    }

    @Test
    fun `orphan flag without owner is a violation`() {
        assertThat(def(owner = "  ").validate(now)).anyMatch { it.contains("no owner") }
    }

    @Test
    fun `money-path flag without four-eyes is a violation`() {
        val v = def(classification = FlagClassification.MONEY_PATH, fourEyes = false).validate(now)
        assertThat(v).anyMatch { it.contains("MONEY_PATH") && it.contains("four-eyes") }
    }
}
