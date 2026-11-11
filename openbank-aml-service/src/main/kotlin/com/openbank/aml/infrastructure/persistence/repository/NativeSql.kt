// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Shared plumbing of the native-SQL repositories. Values are always bound, never spliced. */
internal object NativeSql {
    fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)

    fun instant(value: Any?): Instant? = when (value) {
        null -> null
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> error("unexpected timestamp type ${value.javaClass}")
    }

    /** Runs in the current session; call inside `Panache.withTransaction`. */
    fun mutate(sql: String, values: Map<String, Any>): Uni<Int> = Panache.getSession().chain { session ->
        val query = session.createNativeMutationQuery(sql.trimIndent())
        values.forEach { (name, value) -> query.setParameter(name, value) }
        query.executeUpdate()
    }

    /** Rows as `Object[]`; a column may be SQL NULL whatever Kotlin's element type says. */
    fun select(sql: String, values: Map<String, Any>): Uni<List<Array<Any?>>> = Panache.getSession().chain { session ->
        val query = session.createNativeQuery(sql.trimIndent(), Array<Any>::class.java)
        values.forEach { (name, value) -> query.setParameter(name, value) }
        @Suppress("UNCHECKED_CAST") // justified: Hibernate returns Object[]; this only widens the element type.
        query.resultList.map { rows -> rows as List<Array<Any?>> }
    }
}
