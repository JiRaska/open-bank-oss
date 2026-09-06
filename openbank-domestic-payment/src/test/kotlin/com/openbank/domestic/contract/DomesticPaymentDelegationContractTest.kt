// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class DomesticPaymentDelegationContractTest {

    private val asyncApi = File("../openbank-contracts/openbank-domestic-payment/asyncapi.yaml").readText()
    private val producerAsyncApi = File("../openbank-contracts/openbank-delegation-service/asyncapi.yaml").readText()
    private val openApi = File("src/main/resources/openapi.yaml").readText()

    @Test
    fun `AsyncAPI documents additive all-or-none delegated spend context on status events`() {
        val context = asyncApi.substringAfter("    delegationContext:").substringBefore("\n  messages:")
        assertThat(context).contains("both UUIDs or both", "absent/null")
        listOf("initiatedByPartyId", "delegationId", "reservationId").forEach { field ->
            assertThat(context).contains(
                "$field:\n          type: [string, 'null']\n          format: uuid",
            )
        }

        val statusMessage = asyncApi.substringAfter("    DOMESTIC_PAYMENT_STATUS_CHANGED:\n      name:")
        assertThat(statusMessage).contains(
            "\$ref: '#/components/schemas/delegationContext'",
            "const: DOMESTIC_PAYMENT_STATUS_CHANGED",
            "- paymentId",
            "- previousStatus",
            "- newStatus",
            "- occurredAt",
        )
        val required = statusMessage.substringAfter("            required:").substringBefore("            properties:")
        assertThat(required).doesNotContain("delegationId", "reservationId")
    }

    @Test
    fun `channel and event names match the running producer`() {
        assertThat(asyncApi).contains(
            "address: openbank.domestic.payment.events",
            "channel: openbank.delegation.spend-reservation-state",
            "const: DOMESTIC_PAYMENT_CREATED",
            "const: DELEGATED_SPEND_FINALIZED_ABSENT",
        )
        assertThat(producerAsyncApi).contains(
            "address: openbank.delegation.spend-reservation-state",
            "const: DelegationSpendReservationStateChanged",
        )
    }

    @Test
    fun `reservation consumer contract matches producer and retains only a domain-separated hash`() {
        val producerSchema = schemaBlock(producerAsyncApi, "spendReservationState")
        val producerMessage = producerAsyncApi.substringAfter("    DelegationSpendReservationStateChanged:")
        listOf(
            "reservationId",
            "delegationId",
            "grantorPartyId",
            "granteePartyId",
            "resourceType",
            "resourceId",
            "amount",
            "currency",
            "idempotencyKeyHash",
            "operationType",
            "state",
            "reservationVersion",
            "createdAt",
            "settledAt",
        ).forEach { field ->
            assertThat(producerSchema).contains(field)
        }
        assertThat(producerMessage).contains(
            "name: DelegationSpendReservationStateChanged",
            "\$ref: '#/components/schemas/spendReservationState'",
        )
        assertThat(producerAsyncApi).contains(
            "openbank.delegation.spend-reservation.idempotency-key.v1",
            "pattern: '^[0-9a-f]{64}$'",
        )
        assertThat(producerSchema).doesNotContain("idempotencyKey:")
    }

    @Test
    fun `REST contract does not advertise the trust seam before it is authorized and implemented`() {
        // The persistence/event half is safe to expand independently. Accepting identity and
        // delegation IDs from HTTP headers changes a money-path trust boundary and is intentionally
        // blocked pending explicit authorization. This guard prevents publishing those headers in
        // OpenAPI while the resource still cannot validate them.
        val createOperation = openApi.substringAfter("  /api/v1/domestic-payments:")
            .substringBefore("    get:")
        assertThat(createOperation).doesNotContain(
            "X-Customer-Party-Id",
            "X-Delegation-Id",
            "X-Delegation-Reservation-Id",
        )
    }

    @Test
    fun `REST contract publishes durable idempotency replay and conflict semantics`() {
        val createOperation = openApi.substringAfter("  /api/v1/domestic-payments:")
            .substringBefore("    get:")

        assertThat(createOperation).contains(
            "X-Idempotency-Replayed:",
            "'409':",
            "application/problem+json:",
            "#/components/schemas/IdempotencyConflictResponse",
        )
        val conflictSchema = openApi.substringAfter("    IdempotencyConflictResponse:")
            .substringBefore("\n    ApprovalResponse:")
        assertThat(conflictSchema).contains(
            "urn:openbank:error:idempotency-key-reused",
            "IDEMPOTENCY_KEY_REUSED",
            "enum: [409]",
        )
    }

    /**
     * The YAML block under a top-level `    <key>:`, ending at the next key of the SAME indent.
     *
     * This used to slice from `spendReservationState:` to `DelegationSpendReservationStateChanged:`
     * — but the first is a schema and the second a MESSAGE hundreds of lines below, so the span
     * silently swallowed every schema added between them. #8334's `spendReserved:` sits in that
     * gap and carries the raw caller-chosen `idempotencyKey` by design (ADR-0249 D4), which turned
     * the `doesNotContain("idempotencyKey:")` guard below red on main against a schema it was never
     * meant to read. The guard itself was right the whole time: `spendReservationState` publishes
     * only `idempotencyKeyHash`.
     *
     * Anchoring on indentation rather than on whichever schema happens to be the neighbour keeps
     * that true when the next one lands.
     */
    private fun schemaBlock(yaml: String, key: String): String {
        val marker = "\n    $key:"
        val start = yaml.indexOf(marker)
        require(start >= 0) { "schema '$key' not found in the producer AsyncAPI" }
        val body = yaml.substring(start + marker.length)
        val end = Regex("\\n {4}\\w+:").find(body)?.range?.first ?: body.length
        return body.substring(0, end)
    }
}
