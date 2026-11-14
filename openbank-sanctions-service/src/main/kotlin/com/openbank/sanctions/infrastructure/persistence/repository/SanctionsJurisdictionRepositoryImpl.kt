// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.openbank.sanctions.application.port.out.SanctionsJurisdictionRepository
import com.openbank.sanctions.domain.model.JurisdictionMeasure
import com.openbank.sanctions.domain.model.SanctionsJurisdiction
import com.openbank.sanctions.domain.model.SanctionsListType
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped

/** PgPool read of `sanctions_jurisdictions`; the table is written only by reviewed migrations. */
@ApplicationScoped
class SanctionsJurisdictionRepositoryImpl(private val pool: PgPool) : SanctionsJurisdictionRepository {

    override suspend fun findActive(
        countryCodes: Set<String>,
        listTypes: List<SanctionsListType>,
    ): List<SanctionsJurisdiction> {
        if (countryCodes.isEmpty() || listTypes.isEmpty()) return emptyList()
        val sql = """
            SELECT list_type, source_list, country_code, measure, version
            FROM sanctions_jurisdictions
            WHERE active = true
              AND country_code = ANY($1)
              AND list_type = ANY($2)
            ORDER BY list_type, country_code, source_list
        """.trimIndent()
        val rows = pool.preparedQuery(sql)
            .execute(Tuple.of(countryCodes.toTypedArray(), listTypes.map { it.name }.toTypedArray()))
            .awaitSuspending()
        return rows.map { row ->
            SanctionsJurisdiction(
                listType = SanctionsListType.valueOf(row.getString("list_type")),
                countryCode = row.getString("country_code"),
                measure = JurisdictionMeasure.valueOf(row.getString("measure")),
                version = row.getInteger("version"),
                sourceList = row.getString("source_list"),
            )
        }
    }
}
