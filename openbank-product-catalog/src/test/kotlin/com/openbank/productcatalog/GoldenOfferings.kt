// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * A golden approval portfolio is keyed by OFFERING code (ADR-0352 §7), offering codes are unique, and an
 * offering holds at most one open draft — so tests that backtest share one offering per product code.
 * [offering] returns it (creating it on first use) with any open draft a previous test left retired into
 * a closed interval in the past, so each caller starts from "no draft" and a later publish still finds
 * exactly one open-ended predecessor to supersede. Test-only: production never rewrites a revision.
 */
object GoldenOfferings {

    fun offering(dataSource: DataSource, code: String, schemaId: String, schemaVersion: Int, market: String): UUID {
        find(dataSource, code)?.let { existing ->
            retireDrafts(dataSource, existing)
            return existing
        }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val specificationId = create(
            "/api/v2/specifications",
            """{"code":"GOLDEN_$suffix","schemaRef":{"id":"$schemaId","version":$schemaVersion}}""",
        )
        return UUID.fromString(
            create("/api/v2/offerings", """{"specificationId":"$specificationId","code":"$code","market":$market}"""),
        )
    }

    private fun find(dataSource: DataSource, code: String): UUID? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT id FROM catalog_offerings WHERE code = ?").use { st ->
            st.setString(1, code)
            st.executeQuery().use(::firstId)
        }
    }

    private fun firstId(rs: ResultSet): UUID? = if (rs.next()) rs.getObject(1, UUID::class.java) else null

    private fun retireDrafts(dataSource: DataSource, offeringId: UUID) = dataSource.connection.use { c ->
        c.prepareStatement(
            "UPDATE catalog_revisions SET state = 'SUPERSEDED', effective_from = TIMESTAMPTZ '1970-01-01Z', " +
                "effective_to = TIMESTAMPTZ '1970-01-02Z' WHERE offering_id = ? AND state = 'DRAFT'",
        ).use { st ->
            st.setObject(1, offeringId)
            st.executeUpdate()
        }
    }

    private fun create(path: String, json: String): String = Given {
        contentType("application/json")
        body(json)
    } When { post(path) } Then { statusCode(201) } Extract { jsonPath().getString("id") }
}
