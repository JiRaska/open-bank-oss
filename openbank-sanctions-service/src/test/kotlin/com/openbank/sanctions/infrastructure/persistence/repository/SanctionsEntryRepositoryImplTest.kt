// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsListType
import io.mockk.mockk
import io.vertx.mutiny.pgclient.PgPool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * Unit coverage for [SanctionsEntryRepositoryImpl.parseColumnOrWarn] — the loud-fallback helper
 * that replaced a silent `runCatching{}.getOrDefault()` in `rowToEntry` (a corrupted `list_type`/
 * `aliases_json`/etc. column used to fall back to a default with zero operator-visible signal).
 * Regression guard: a well-formed column must still parse to its real value (not silently take
 * the default), and a malformed one must fall back safely rather than throw.
 */
class SanctionsEntryRepositoryImplTest {

    private val repo = SanctionsEntryRepositoryImpl(mockk<PgPool>(), Clock.systemUTC())

    @Test
    fun `a well-formed enum column parses to its real value, not the default`() {
        val result = repo.parseColumnOrWarn("row-1", "list_type", "EU_CONSOLIDATED", SanctionsListType.OFAC_SDN) {
            SanctionsListType.valueOf(it)
        }
        assertThat(result).isEqualTo(SanctionsListType.EU_CONSOLIDATED)
    }

    @Test
    fun `a malformed enum column falls back to the default instead of throwing`() {
        val result = repo.parseColumnOrWarn("row-2", "list_type", "NOT_A_REAL_LIST", SanctionsListType.OFAC_SDN) {
            SanctionsListType.valueOf(it)
        }
        assertThat(result).isEqualTo(SanctionsListType.OFAC_SDN)
    }

    @Test
    fun `a well-formed JSON list column parses to its real values, not the default`() {
        val result = repo.parseColumnOrWarn(
            "row-3",
            "aliases_json",
            """["Vova Putin","V. Putin"]""",
            emptyList<String>(),
        ) {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(it, Array<String>::class.java).toList()
        }
        assertThat(result).containsExactly("Vova Putin", "V. Putin")
    }

    @Test
    fun `malformed JSON falls back to the default instead of throwing`() {
        val result = repo.parseColumnOrWarn("row-4", "aliases_json", "{not valid json", emptyList<String>()) {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(it, Array<String>::class.java).toList()
        }
        assertThat(result).isEmpty()
    }

    @Test
    fun `entity type falls back to INDIVIDUAL on a malformed value`() {
        val result = repo.parseColumnOrWarn("row-5", "entity_type", "GARBAGE", EntityType.INDIVIDUAL) {
            EntityType.valueOf(it)
        }
        assertThat(result).isEqualTo(EntityType.INDIVIDUAL)
    }
}
