// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.identifiers

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The lending typed ids (ADR-0028). What matters is that a malformed string fails LOUDLY and names
 * the type it failed to parse — these ids arrive from path parameters, so a silent coercion or an
 * anonymous `IllegalArgumentException` is how a caller gets a 500 instead of a 400.
 */
class LendingIdsTest {

    private val uuid = "018f4c2e-0f1a-7c3a-9b0f-2a3b4c5d6e7f"

    @Test
    fun `of parses a canonical UUID string for each lending id type`() {
        assertThat(LoanApplicationId.of(uuid).value).isEqualTo(UUID.fromString(uuid))
        assertThat(LoanId.of(uuid).value).isEqualTo(UUID.fromString(uuid))
        assertThat(CollateralId.of(uuid).value).isEqualTo(UUID.fromString(uuid))
    }

    @Test
    fun `of names the failing type in the message and keeps the cause`() {
        assertThatThrownBy { LoanId.of("not-a-uuid") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("LoanId")
            .hasMessageContaining("not-a-uuid")
            .hasCauseInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { LoanApplicationId.of("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("LoanApplicationId")

        assertThatThrownBy { CollateralId.of("018f4c2e-0f1a-7c3a-9b0f") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CollateralId")
    }

    @Test
    fun `random mints a fresh id every call`() {
        val ids = (1..50).map { LoanId.random() }.toSet()
        assertThat(ids).hasSize(50)
    }

    @Test
    fun `toString round-trips through of`() {
        val id = CollateralId.random()
        assertThat(CollateralId.of(id.value.toString())).isEqualTo(id)
    }

    @Test
    fun `the three lending id types are not interchangeable despite sharing a UUID`() {
        val raw = UUID.fromString(uuid)
        // Same underlying value, different types — the whole point of ADR-0028 typed ids: a
        // structural (data class) equality across types would defeat it.
        assertThat(LoanId(raw)).isNotEqualTo(LoanApplicationId(raw))
        assertThat(LoanId(raw)).isNotEqualTo(CollateralId(raw))
        assertThat(LoanId(raw)).isEqualTo(LoanId(raw))
    }

    @Test
    fun `every lending id is an EntityId exposing its UUID through the shared contract`() {
        val ids: List<EntityId> = listOf(LoanApplicationId.random(), LoanId.random(), CollateralId.random())
        assertThat(ids.map { it.value }).doesNotHaveDuplicates()
    }
}
