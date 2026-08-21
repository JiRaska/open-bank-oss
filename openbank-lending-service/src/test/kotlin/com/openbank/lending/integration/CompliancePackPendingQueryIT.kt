// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /api/v1/lending/compliance-packs/proposals/pending` against a REAL database.
 *
 * The repository ordered by `proposed_at` — the COLUMN — in a string that Hibernate parses as
 * HQL, where only the entity property `proposedAt` exists. Every call answered 500:
 *
 *     org.hibernate.query.SemanticException: Could not interpret path expression 'proposed_at'
 *
 * No parameters were needed. A plain GET, broken from the day it shipped, on a money-path service.
 *
 * Nothing in the suite could see it. `CompliancePackActivationServiceTest` substitutes a
 * hand-written in-memory `findByState` that filters a map, so no HQL is ever issued and the query
 * string is never parsed — the identical shape to the consent `SuppressionEntity` defect (#5711),
 * where mocked repositories hid a mapping that could not work. A fake repository cannot test a
 * query; only a database can. It was found by fuzzing past authentication (#5913), because a 403
 * had been answering first.
 *
 * So this test asserts the two things a fake cannot: that the query PARSES, and that the ordering
 * it names is the one that comes back. The ordering half matters on its own — `order by` is the
 * part of a query most easily wrong in a way that still returns rows.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CompliancePackPendingQueryIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "pending-query-it", roles = ["ROLE_COMPLIANCE", "ROLE_ADMIN"])
    fun `pending proposals are returned oldest-first, not a 500`() {
        // Inserted newest-first so a query that ignores the ordering, or applies it backwards,
        // cannot pass by accident on insertion order.
        val newer = insertProposal(version = 9101, proposedAt = OffsetDateTime.now())
        val older = insertProposal(version = 9102, proposedAt = OffsetDateTime.now().minusDays(2))

        val body = Given {
            header("Accept", "application/json")
        } When {
            get("/api/v1/lending/compliance-packs/proposals/pending")
        } Then {
            // The assertion the defect failed: 500, not 200. Kept explicit rather than implied by
            // the body assertions below, so a regression names the right thing in the report.
            statusCode(200)
        } Extract {
            jsonPath()
        }

        val ids = body.getList<String>("id")
        assertThat(ids).contains(older.toString(), newer.toString())
        assertThat(ids.indexOf(older.toString()))
            .`as`("proposals must come back oldest-first — `order by proposedAt`")
            .isLessThan(ids.indexOf(newer.toString()))
    }

    private fun insertProposal(version: Int, proposedAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO compliance_pack_activation
                  (id, state, jurisdiction, product_type, pack_version, effective_from, payload,
                   content_hash, proposed_by, proposed_at, created_at, updated_at)
                VALUES (?::uuid, 'PROPOSED', 'CZ', 'CONSUMER_CREDIT', ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                var i = 1
                st.setString(i++, id.toString())
                st.setInt(i++, version)
                st.setObject(i++, LocalDate.parse("2026-08-01"))
                st.setString(i++, "{}")
                st.setString(i++, "sha256:pending-query-it-$version")
                st.setString(i++, "pending-query-it-maker")
                st.setObject(i++, proposedAt)
                st.setObject(i++, proposedAt)
                st.setObject(i, proposedAt)
                st.executeUpdate()
            }
        }
        return id
    }
}
