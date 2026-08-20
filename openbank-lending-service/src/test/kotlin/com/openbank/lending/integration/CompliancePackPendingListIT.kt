// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

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
 * it. An empty result is a legitimate 200 here — the query executing at all is the property.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CompliancePackPendingListIT {

    @Test
    @TestSecurity(user = "pack-pending-it", roles = ["ROLE_COMPLIANCE"])
    fun `listing pending proposals executes the query instead of answering 500`() {
        When {
            get("/api/v1/lending/compliance-packs/proposals/pending")
        } Then {
            statusCode(200)
        }
    }
}
