// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The bind-parameter bound of [KycRepository.findPartyIdsWithAnyCase], MEASURED.
 *
 * `IN :ids` expands to one bind parameter per id, and PostgreSQL's wire protocol carries a
 * statement's parameter count as an int16 — a hard ceiling of 65,535. The reconciler's candidate
 * set is bounded only by its own page cap (`page-size x max-pages`, 100 x 500 = 50,000 by
 * default), and `OrphanedPartyDetector`'s cap warning used to tell operators to RAISE `max-pages`,
 * which is precisely the action that walks the statement over the ceiling.
 *
 * These cases assert the property the fix buys and the old shape could not satisfy: the batch
 * handed to `setParameter("ids", ...)` — which IS the statement's bind list — never exceeds
 * [KycRepository.ID_BATCH_SIZE], at the configured cap and far beyond it. A comment claiming that
 * is not a proof; this is, and it fails the moment the chunking is removed.
 */
class KycRepositoryBatchingTest {

    private fun ids(n: Int) = List(n) { UUID.randomUUID() }

    @Test
    fun `at the detector's configured cap no statement exceeds the batch size`() {
        val candidates = ids(DEFAULT_PAGE_SIZE * DEFAULT_MAX_PAGES) // 50,000

        val batches = KycRepository.idBatches(candidates)

        assertThat(batches.maxOf { it.size })
            .`as`("largest bind list handed to one statement")
            .isLessThanOrEqualTo(KycRepository.ID_BATCH_SIZE)
        assertThat(batches).hasSize(candidates.size / KycRepository.ID_BATCH_SIZE)
        assertThat(batches.flatten()).containsExactlyElementsOf(candidates)
    }

    @Test
    fun `the per-statement bind count stays under the protocol ceiling well past the cap`() {
        // 13x the default cap: the old single-statement shape needed 650,000 binds here, ~10x the
        // protocol ceiling. The bound must not depend on the register's size at all.
        val candidates = ids(650_000)

        val batches = KycRepository.idBatches(candidates)

        assertThat(batches.maxOf { it.size }).isLessThan(POSTGRES_MAX_BIND_PARAMETERS)
        assertThat(batches.maxOf { it.size }).isEqualTo(KycRepository.ID_BATCH_SIZE)
    }

    @Test
    fun `a partial final batch is kept, so no candidate goes unchecked`() {
        val candidates = ids(KycRepository.ID_BATCH_SIZE + 1)

        val batches = KycRepository.idBatches(candidates)

        assertThat(batches).hasSize(2)
        assertThat(batches.last()).hasSize(1)
        assertThat(batches.flatten()).containsExactlyElementsOf(candidates)
    }

    @Test
    fun `an empty candidate set produces no statement at all`() {
        assertThat(KycRepository.idBatches(emptyList())).isEmpty()
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val DEFAULT_MAX_PAGES = 500

        /** int16 on the wire; the ceiling the old shape was 15,535 away from on defaults. */
        const val POSTGRES_MAX_BIND_PARAMETERS = 65_535
    }
}
