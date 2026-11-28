// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.monitoring

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Monitoring keys counters by currency string, so a value that is not an active ISO 4217 code
 * would open a counter no real booking can ever reach. Three-letter shape is not enough: the
 * shared CurrencyCode is the only authority.
 */
class MonitoringCurrencyValidationTest {

    @ParameterizedTest
    @ValueSource(strings = ["ABC", "DEM", "HRK", "XAU", "eur"])
    fun `a currency that is not an active canonical ISO 4217 code is refused everywhere`(currency: String) {
        assertThatThrownBy { movement(currency) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { band(currency) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { feature(currency) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CZK", "EUR", "JPY", "KWD"])
    fun `active ISO 4217 codes are accepted everywhere`(currency: String) {
        assertThatCode { movement(currency) }.doesNotThrowAnyException()
        assertThatCode { band(currency) }.doesNotThrowAnyException()
        assertThatCode { feature(currency) }.doesNotThrowAnyException()
    }

    private fun movement(currency: String) = BookedMovement.fromSignedDelta(
        journalEntryId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        currency = currency,
        delta = BigDecimal("-10.00"),
        transactionId = UUID.randomUUID(),
        entryDate = LocalDate.of(2026, 11, 18),
        bookedAt = Instant.parse("2026-11-18T10:00:00Z"),
        sourceEventId = null,
    )

    private fun band(currency: String) = AmountBand("SMALL", currency, BigDecimal.ZERO, BigDecimal("1000"))

    private fun feature(currency: String) = CountFeatureDefinition(
        id = "count.out.small",
        revision = 1,
        schemeId = "default",
        schemeRevision = 1,
        currency = currency,
        direction = MovementDirection.OUT,
        rail = null,
        band = "SMALL",
        windowSeconds = 3600,
    )
}
