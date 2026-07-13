// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.governance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Governance guard (container-free, runs under the path-scoped per-service CI).
 *
 * Hibernate Reactive + Kotlin `PanacheEntity` allocate the `id` from a sequence named
 * `<table>_seq` with the default allocationSize of 50. When the Flyway migration creates the
 * table with `BIGSERIAL` (which only yields `<table>_id_seq`) and the schema is `generation: none`,
 * the sequence Hibernate expects never exists and every INSERT fails at runtime with
 * `relation "<table>_seq" does not exist`.
 *
 * This test fails when any `: PanacheEntity()` table in this service lacks a matching, unquoted,
 * lowercase `<table>_seq` sequence in the Flyway migrations.
 */
class HibernateSequenceGuardTest {

    private val mainKotlin = File("src/main/kotlin")
    private val migrations = File("src/main/resources/db/migration")

    @Test
    fun `every PanacheEntity table has a matching hibernate sequence`() {
        if (!mainKotlin.isDirectory) return

        val panacheEntity = Regex(""":\s*PanacheEntity\s*\(\s*\)""")
        val tableName = Regex("""@Table\(\s*name\s*=\s*"([^"]+)"""")

        val requiredSequences = mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .filter { panacheEntity.containsMatchIn(it) }
            .flatMap { tableName.findAll(it).map { m -> m.groupValues[1] } }
            .map { "${it}_seq" }
            .toSortedSet()

        if (requiredSequences.isEmpty()) return

        val presentSequences = collectCreatedSequences()

        val missing = requiredSequences.filter { it !in presentSequences }
        assertThat(missing)
            .`as`(
                "PanacheEntity tables missing their <table>_seq sequence (Hibernate allocates ids " +
                    "from it; add CREATE SEQUENCE IF NOT EXISTS <name> INCREMENT BY 50). Present: %s",
                presentSequences,
            )
            .isEmpty()
    }

    private fun collectCreatedSequences(): Set<String> {
        if (!migrations.isDirectory) return emptySet()
        val create = Regex(
            """create\s+sequence(?:\s+if\s+not\s+exists)?\s+("?)([A-Za-z0-9_]+)\1""",
            RegexOption.IGNORE_CASE,
        )
        return migrations.walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .flatMap { file ->
                create.findAll(file.readText()).map { m ->
                    val quoted = m.groupValues[1] == "\""
                    val name = m.groupValues[2]
                    if (quoted) name else name.lowercase()
                }
            }
            .toSet()
    }
}
