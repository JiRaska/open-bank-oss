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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * `GET /api/v1/lending/compliance-packs/proposals/pending` takes no parameters and had answered 500
 * on every call since it shipped (#5913, found by the two-pass fuzz lane once it could get past
 * authentication). The HQL ordered by `proposed_at` — the COLUMN name — where Hibernate wants the
 * entity property `proposedAt`, so `Panache.list` threw `SemanticException: Could not interpret path
 * expression 'proposed_at'` before a single row was read.
 *
 * The reason no existing test saw it is the reason it is worth a test of its own: the name exists in
 * exactly one of the two worlds (it is real in Postgres, absent in the entity model), so any test that
 * mocks the repository agrees with the broken code. Only a real query against a real schema can tell
 * the two apart — same shape as the consent `SuppressionEntity` defect (#5711).
 *
 * The assertion is the STATUS, not the absence of an exception: this endpoint answered 500 with a
 * perfectly well-formed `INTERNAL_ERROR` body, so "it did not crash" is exactly the oracle that missed
 * it.
 *
 * It then goes one step further and seeds a proposal first, so the assertion is that the row comes
 * BACK. A 200 over an empty table is enough to catch this particular defect — the `SemanticException`
 * fires whether or not any row matches — but it is equally satisfied by a handler that stopped
 * querying altogether, and by an ORDER BY that resolves and then sorts on the wrong thing. Seeding
 * costs one POST and makes the test say what the endpoint is FOR rather than only that it survives.
 *
 * A separate class rather than another `@Order` in [CompliancePackActivationIT]: that test is an
 * ordered maker -> violation -> checker -> active sequence whose whole argument is the ORDER, and a
 * pending row is only observable while it is still PROPOSED. Version 998 keeps this proposal out of
 * that test's `pack_version = 999` row count.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresRedisTestResource::class)
class CompliancePackPendingListIT {

    private companion object {
        const val PACK_VERSION = 998
    }

    /**
     * The REAL shipped pack with only its version changed — an invented payload would be rejected by
     * `CompliancePackParser` and the seeding POST would fail for a reason unrelated to this test.
     */
    private val pack: String by lazy {
        val raw = checkNotNull(javaClass.getResourceAsStream("/compliance-packs/cz-consumer-credit-v1.json")) {
            "the shipped CZ pack must be on the test classpath"
        }.bufferedReader().readText()
        raw.replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": $PACK_VERSION")
    }

    @Test
    @TestSecurity(user = "pack-pending-it", roles = ["ROLE_COMPLIANCE"])
    fun `listing pending proposals executes the query and returns the proposed row`() {
        val proposalId = Given {
            contentType("application/json")
            body(pack)
        } When {
            post("/api/v1/lending/compliance-packs/proposals")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        assertThat(proposalId).isNotBlank()

        val ids = Given {
            contentType("application/json")
        } When {
            get("/api/v1/lending/compliance-packs/proposals/pending")
        } Then {
            // 500 here is the SemanticException: the ORDER BY names a column HQL cannot resolve, so
            // the query fails before any row is read.
            statusCode(200)
        } Extract {
            jsonPath().getList<String>("id")
        }

        assertThat(ids).describedAs("pending proposals returned by the endpoint").contains(proposalId)
    }
}
