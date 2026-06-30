// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MatchKeyTest {

    @Test
    fun `strips diacritics and lowercases`() {
        assertThat(MatchKey.normalize("Dvořák")).isEqualTo("dvorak")
        assertThat(MatchKey.normalize("ČAPEK")).isEqualTo("capek")
    }

    @Test
    fun `collapses and trims whitespace`() {
        assertThat(MatchKey.normalize("  van   der  Berg ")).isEqualTo("van der berg")
    }

    @Test
    fun `cosmetically different spellings collapse to the same key`() {
        val a = MatchKey.of("Dvořák", "Jan", LocalDate.of(1976, 5, 6), "Brno")
        val b = MatchKey.of(" DVORAK ", "jan", LocalDate.of(1976, 5, 6), "brno")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `absent birthplace yields a trailing empty segment`() {
        val key = MatchKey.of("Smith", "John", LocalDate.of(1990, 1, 2), null)
        assertThat(key).isEqualTo("smith|john|1990-01-02|")
    }

    @Test
    fun `distinct people produce distinct keys`() {
        val a = MatchKey.of("Novak", "Petr", LocalDate.of(1980, 3, 3), "Praha")
        val b = MatchKey.of("Novak", "Petr", LocalDate.of(1981, 3, 3), "Praha")
        assertThat(a).isNotEqualTo(b)
    }
}
