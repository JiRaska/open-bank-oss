// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.e2e

import com.openbank.sanctions.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.vertx.mutiny.pgclient.PgPool
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journeys of the sanctions screening rail over the real HTTP surface against real
 * Postgres. The matching is real — pg_trgm similarity over `sanctions_entries`, no hardcoded
 * data — so the E2E seeds a genuine list entry and watches the screening verdict fall out of the
 * database, exactly as a production hit would:
 *
 *  - a listed entity screens to **HIT** with the match evidence on the check, appears in
 *    `/hits`, and an operator review (`sanctions.clear`, the distinctive four-eyes verb) carries
 *    it to `WHITELISTED` with reviewer, note and timestamp — after which `/hits` no longer lists
 *    it;
 *  - an unlisted entity screens **CLEAR** with no matches, and a retried screen with the SAME
 *    `idempotencyKey` but a DIFFERENT name replays the stored check (ADR-0298, #8351) — never a
 *    second row;
 *  - the null-element guards hold (#7867): `[null]` in `aliases` or `listTypes` is a 400, not an
 *    NPE-driven 500; an unknown `listTypes` value is 400; and `ROLE_VIEWER` reads the queues but
 *    is refused 403 on screen.
 *
 * `%test` sets `authz.enforce: false` (OPA is absent in a test JVM and the interceptor fails
 * closed), so `@Authorize` runs advisory — the deployed default (`AUTHZ_ENFORCE:true`) is
 * untouched.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsLifecycleE2E {

    @Inject
    lateinit var pool: PgPool

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a listed entity screens to a hit that an operator review whitelists end to end`() {
        val listedName = "E2E Sanctioned Person ${UUID.randomUUID().toString().take(8)}"
        seedEntry("e2e-${UUID.randomUUID()}", listedName)

        val screened: Map<String, Any?> = Given {
            contentType("application/json")
            body(screenBody("sc-e2e-hit-${UUID.randomUUID()}", listedName))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        val id = screened["id"] as String
        assertThat(screened["status"]).isEqualTo("HIT")
        assertThat(screened["name"]).isEqualTo(listedName)
        assertThat((screened["overallScore"] as Number).toDouble()).isGreaterThanOrEqualTo(0.85)
        val matches = screened["matches"] as List<Map<String, Any?>>
        assertThat(matches).isNotEmpty()
        assertThat(matches[0]["matchedName"]).isEqualTo(listedName)
        assertThat(matches[0]["listType"]).isNotNull()
        assertListedInHits(id)

        // sanctions.clear: the review carries the check to WHITELISTED with full audit fields.
        val reviewed: Map<String, Any?> = Given {
            contentType("application/json")
            body(
                """
                {
                  "checkId": "$id",
                  "reviewedBy": "e2e-operator-1",
                  "note": "E2E false positive",
                  "newStatus": "WHITELISTED"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sanctions/review")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(reviewed["status"]).isEqualTo("WHITELISTED")
        assertThat(reviewed["reviewedBy"]).isEqualTo("e2e-operator-1")
        assertThat(reviewed["reviewNote"]).isEqualTo("E2E false positive")
        assertThat(reviewed["reviewedAt"]).isNotNull()

        val after: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/sanctions/hits")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(after.map { it["id"] }).doesNotContain(id)
    }

    private fun assertListedInHits(id: String) {
        val hits: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/sanctions/hits")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(hits.map { it["id"] }).contains(id)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `an unlisted entity screens clear and a retried screen replays the stored check`() {
        val key = "sc-e2e-clear-${UUID.randomUUID()}"
        val uniqueName = "E2E Innocent Bystander ${UUID.randomUUID().toString().take(8)}"

        val screened: Map<String, Any?> = Given {
            contentType("application/json")
            body(screenBody(key, uniqueName))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        val id = screened["id"] as String
        assertThat(screened["status"]).isEqualTo("CLEAR")
        assertThat(screened["matches"] as List<*>).isEmpty()
        assertThat((screened["overallScore"] as Number).toDouble()).isEqualTo(0.0)

        // Same key, different name: the stored check replays.
        val replay: Map<String, Any?> = Given {
            contentType("application/json")
            body(screenBody(key, "E2E Someone Else Entirely"))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(replay["id"]).isEqualTo(id)
        assertThat(replay["name"]).isEqualTo(uniqueName)

        val checks: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/sanctions")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(checks.filter { it["idempotencyKey"] == key }).hasSize(1)

        // An unknown check is a 404, not a 500.
        Given {
            this
        } When {
            get("/api/v1/sanctions/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `null array elements and unknown lists are 400s not 500s`() {
        // #7867: Jackson does not null-check collection elements; the service must refuse them.
        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "sc-e2e-null-${UUID.randomUUID()}",
                  "entityType": "INDIVIDUAL",
                  "name": "E2E Null Alias",
                  "aliases": [null]
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "sc-e2e-list-${UUID.randomUUID()}",
                  "entityType": "INDIVIDUAL",
                  "name": "E2E Unknown List",
                  "listTypes": ["NOPE"]
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewers can read the queues but never screen`() {
        Given {
            this
        } When {
            get("/api/v1/sanctions/pending")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            body(screenBody("sc-e2e-viewer-${UUID.randomUUID()}", "E2E Viewer Attempt"))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(403)
        }
    }

    private fun seedEntry(externalId: String, primaryName: String) {
        // search_text is what the pg_trgm similarity query matches against — the import pipeline
        // fills it with the diacritic-stripped, lowercased name (normalizeForSearch). Seed it the
        // same way or the row is invisible to screening.
        VertxContextSupport.subscribeAndAwait {
            pool.preparedQuery(
                "INSERT INTO sanctions_entries " +
                    "(list_type, external_id, entity_type, primary_name, search_text) " +
                    "VALUES ('UN_CONSOLIDATED', $1, 'INDIVIDUAL', $2, $3)",
            ).execute(io.vertx.mutiny.sqlclient.Tuple.of(externalId, primaryName, primaryName.lowercase()))
        }
    }

    private fun screenBody(key: String, name: String) = """
        {
          "idempotencyKey": "$key",
          "entityType": "INDIVIDUAL",
          "name": "$name"
        }
    """.trimIndent()
}
