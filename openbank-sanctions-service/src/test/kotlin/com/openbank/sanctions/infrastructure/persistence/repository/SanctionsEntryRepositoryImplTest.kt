// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.SanctionsListType
import io.mockk.mockk
import io.vertx.mutiny.pgclient.PgPool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Unit coverage for [SanctionsEntryRepositoryImpl.parseColumnOrWarn] — the loud-fallback helper
 * that replaced a silent `runCatching{}.getOrDefault()` in `rowToEntry` (a corrupted `list_type`/
 * `aliases_json`/etc. column used to fall back to a default with zero operator-visible signal).
 * Regression guard: a well-formed column must still parse to its real value (not silently take
 * the default), and a malformed one must fall back safely rather than throw.
 *
 * The value-only tests below would still pass if a future edit silently dropped the `Log.warnf`
 * call and reverted to the old bare `runCatching{}.getOrDefault()` — the returned value is
 * identical either way. The `logs a WARN` tests close that gap by attaching a [Handler] directly
 * to the class's logger (the name `io.quarkus.logging.Log` rewrites `Log.warnf` calls to at
 * build time) and asserting a record is actually published — the thing this whole PR is about.
 */
class SanctionsEntryRepositoryImplTest {

    private val repo = SanctionsEntryRepositoryImpl(mockk<PgPool>(), Clock.systemUTC())

    private val captured = mutableListOf<LogRecord>()
    private val captureHandler = object : Handler() {
        override fun publish(record: LogRecord) {
            captured += record
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
    private val repoLogger = Logger.getLogger(SanctionsEntryRepositoryImpl::class.java.name)

    @BeforeEach
    fun attachHandler() {
        repoLogger.addHandler(captureHandler)
    }

    @AfterEach
    fun detachHandler() {
        repoLogger.removeHandler(captureHandler)
    }

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

    @Test
    fun `a malformed column logs a WARN naming the row id and column`() {
        repo.parseColumnOrWarn("row-corrupt-42", "list_type", "NOT_A_REAL_LIST", SanctionsListType.OFAC_SDN) {
            SanctionsListType.valueOf(it)
        }

        assertThat(captured).hasSize(1)
        val record = captured.single()
        assertThat(record.level).isEqualTo(Level.WARNING)
        assertThat(record.message).contains("row-corrupt-42", "list_type")
    }

    @Test
    fun `a well-formed column logs nothing`() {
        repo.parseColumnOrWarn("row-clean-1", "list_type", "EU_CONSOLIDATED", SanctionsListType.OFAC_SDN) {
            SanctionsListType.valueOf(it)
        }

        assertThat(captured).isEmpty()
    }
}
