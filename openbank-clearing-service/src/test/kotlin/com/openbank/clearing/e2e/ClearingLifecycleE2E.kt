// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.e2e

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journeys of the gross/net settlement spine with money-path assertions the
 * happy-path `ClearingApiIT` does not make:
 *
 *  - **Idempotent replay (ADR-0298, #8351)**: a retried submit with the SAME `paymentId` but a
 *    DIFFERENT amount replays the stored item — one PENDING row, the stored amount, never a
 *    second row the cycle could sweep in and settle twice.
 *  - **Exact NET totals**: batch `totalDebit`/`totalCredit`/`netPosition` asserted with
 *    BigDecimal compareTo against the sum of the cleared items.
 *  - **Reconciliation as a state probe**: a batch still `IN_CLEARING` answers 409 CONFLICT with
 *    the stuck item ids; once settled it answers 200 clean with matching counts.
 *  - **Least-privilege reads vs writes** (K7 / ADR-0018): `ROLE_VIEWER` reads the rail but is
 *    refused 403 on submit and settle.
 *
 * `authz.enforce` defaults to `false` (advisory), so no OPA mirror is needed — the `@Authorize`
 * annotations pass with a WARN and the role checks above are the Quarkus OIDC layer.
 */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_it")],
)
class ClearingLifecycleE2E {

    @Test
    @TestSecurity(user = "e2e-payments-1", roles = ["ROLE_PAYMENTS"])
    fun `payments clear end to end with idempotent replay and a clean reconciliation`() {
        val paymentId1 = UUID.randomUUID()
        val paymentId2 = UUID.randomUUID()

        val first = submitPayment(paymentId1, "E2E-C-001", "100.50")
        val itemId = first["id"] as String
        assertThat(first["status"]).isEqualTo("PENDING")
        assertThat(first["currency"]).isEqualTo("EUR")
        assertThat(first["rail"]).isEqualTo("SEPA_SCT")

        // Retry with the same paymentId but a different amount: the stored item replays.
        val replay = submitPayment(paymentId1, "E2E-C-001", "999.99")
        assertThat(replay["id"]).isEqualTo(itemId)
        assertThat(BigDecimal(replay["amount"].toString())).isEqualByComparingTo("100.50")

        val byPayment: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/clearing/items/by-payment/$paymentId1")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(byPayment).hasSize(1)

        submitPayment(paymentId2, "E2E-C-002", "200.25")

        val cycle = triggerCycle()
        val batch = (cycle["batches"] as List<Map<String, Any?>>).single { it["currency"] == "EUR" }
        val batchId = batch["id"] as String
        assertThat(batch["status"]).isEqualTo("IN_CLEARING")
        assertThat(batch["settlementType"]).isEqualTo("NET")
        assertThat((batch["itemCount"] as Number).toInt()).isEqualTo(2)
        assertThat(BigDecimal(batch["totalDebit"].toString())).isEqualByComparingTo("300.75")
        assertThat(BigDecimal(batch["totalCredit"].toString())).isEqualByComparingTo("300.75")
        assertThat(BigDecimal(batch["netPosition"].toString())).isEqualByComparingTo(BigDecimal.ZERO)

        val settled: Map<String, Any?> = Given {
            contentType("application/json")
        } When {
            post("/api/v1/clearing/batches/$batchId/settle")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(settled["status"]).isEqualTo("SETTLED")
        assertThat(settled["settledAt"]).isNotNull()
        assertThat(BigDecimal(settled["totalDebit"].toString())).isEqualByComparingTo("300.75")

        val items: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/clearing/batches/$batchId/items")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(items).hasSize(2)
        assertThat(items.map { it["status"] }).containsOnly("SETTLED")

        assertCleanReconciliation(batchId, expected = 2, settled = 2)
    }

    private fun assertCleanReconciliation(batchId: String, expected: Int, settled: Int) {
        val report: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/clearing/batches/$batchId/reconcile")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(report["clean"]).isEqualTo(true)
        assertThat((report["expectedItemCount"] as Number).toInt()).isEqualTo(expected)
        assertThat((report["settledItemCount"] as Number).toInt()).isEqualTo(settled)
        assertThat(report["stuckItemIds"] as List<*>).isEmpty()
    }

    @Test
    @TestSecurity(user = "e2e-payments-1", roles = ["ROLE_PAYMENTS"])
    fun `reconciliation reports stuck items while a batch is still in clearing`() {
        val paymentId = UUID.randomUUID()
        val item = submitPayment(paymentId, "E2E-C-003", "50.00")
        val itemId = item["id"] as String

        val cycle = triggerCycle()
        val batch = (cycle["batches"] as List<Map<String, Any?>>).single { it["currency"] == "EUR" }
        val batchId = batch["id"] as String
        assertThat(batch["status"]).isEqualTo("IN_CLEARING")

        val report: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/clearing/batches/$batchId/reconcile")
        } Then {
            statusCode(409)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(report["clean"]).isEqualTo(false)
        assertThat((report["expectedItemCount"] as Number).toInt()).isEqualTo(1)
        assertThat((report["settledItemCount"] as Number).toInt()).isEqualTo(0)
        assertThat(report["stuckItemIds"] as List<*>).containsExactly(itemId)

        // Leave the rail tidy: settle the probe batch so a later trigger is not affected.
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/clearing/batches/$batchId/settle")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewers can read the rail but never write to it`() {
        Given {
            this
        } When {
            get("/api/v1/clearing/batches")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            body(submitPayload(UUID.randomUUID(), "E2E-C-004", "10.00"))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(403)
        }

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/clearing/batches/${UUID.randomUUID()}/settle")
        } Then {
            statusCode(403)
        }
    }

    private fun submitPayment(paymentId: UUID, reference: String, amount: String): Map<String, Any?> = Given {
        contentType("application/json")
        body(submitPayload(paymentId, reference, amount))
    } When {
        post("/api/v1/clearing/submit")
    } Then {
        statusCode(201)
    } Extract {
        jsonPath().getMap("")
    }

    private fun triggerCycle(): Map<String, Any?> = Given {
        contentType("application/json")
    } When {
        post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getMap("")
    }

    private fun submitPayload(paymentId: UUID, reference: String, amount: String) = """
        {
          "paymentId": "$paymentId",
          "paymentReference": "$reference",
          "debtorIban": "CZ6508000000192000145399",
          "creditorIban": "DE89370400440532013000",
          "debtorBic": "GIBACZPX",
          "creditorBic": "COBADEFF",
          "amount": "$amount",
          "currency": "EUR",
          "rail": "SEPA_SCT",
          "endToEndId": "E2E-$reference",
          "remittanceInfo": "lifecycle $reference"
        }
    """.trimIndent()
}
