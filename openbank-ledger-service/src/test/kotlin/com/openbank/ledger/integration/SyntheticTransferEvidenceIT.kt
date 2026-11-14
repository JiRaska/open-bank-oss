// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@QuarkusTest
@TestProfile(SyntheticTransferEvidenceIT.FixtureProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SyntheticTransferEvidenceIT {

    class NoBrokerResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("ledger-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    class FixtureProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> = listOf(
            QuarkusTestProfile.TestResourceEntry(
                PostgresTestResource::class.java,
                mapOf("db" to "openbank_ledger_canary_proof_it"),
            ),
            QuarkusTestProfile.TestResourceEntry(NoBrokerResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.synthetic.trusted-principals" to WRITER,
            "openbank.synthetic-transfer.source-account-id" to SOURCE_ID,
            "openbank.synthetic-transfer.target-account-id" to TARGET_ID,
            "quarkus.devservices.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.otel.sdk.disabled" to "true",
            "quarkus.scheduler.enabled" to "false",
        )
    }

    private val syntheticId = UUID.randomUUID()
    private val ordinaryId = UUID.randomUUID()
    private val ambiguousId = UUID.randomUUID()
    private lateinit var journalId: UUID

    @Test
    @Order(1)
    @TestSecurity(user = WRITER, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-transaction"),
            Claim(key = "preferred_username", value = WRITER),
            Claim(key = "sub", value = "synthetic-transfer-it-writer"),
        ],
    )
    fun `book the synthetic and ordinary control journals`() {
        journalId = book(syntheticId, synthetic = true)
        book(ordinaryId, synthetic = false)
        assertThat(book(ambiguousId, synthetic = true)).isNotEqualTo(book(ambiguousId, synthetic = true))
    }

    @Test
    @Order(2)
    @TestSecurity(user = READER, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-transfer-verifier"),
            Claim(key = "preferred_username", value = READER),
            Claim(key = "sub", value = "synthetic-transfer-it-verifier"),
        ],
    )
    fun `the verifier receives only one scoped synthetic posting`() {
        val proof = given().get("$PROOF_PATH/$syntheticId")
        assertThat(proof.statusCode).isEqualTo(200)
        assertThat(proof.getHeader("Cache-Control")).isEqualTo("no-store")
        val body = proof.jsonPath()
        assertThat(body.getUUID("journalId")).isEqualTo(journalId)
        assertThat(body.getUUID("transactionId")).isEqualTo(syntheticId)
        assertThat(body.getString("debitAccountId")).isEqualTo(SOURCE_ID)
        assertThat(body.getString("creditAccountId")).isEqualTo(TARGET_ID)
        assertThat(body.getInt("postingCount")).isEqualTo(1)
        assertThat(body.getInt("amountMinor")).isEqualTo(1)
        assertThat(body.getBoolean("synthetic")).isTrue()
        assertThat(given().get("$PROOF_PATH/$ordinaryId").statusCode).isEqualTo(404)
        assertThat(given().get("$PROOF_PATH/$ambiguousId").statusCode).isEqualTo(404)
        assertThat(given().get("$PROOF_PATH/${UUID.randomUUID()}").statusCode).isEqualTo(404)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "ordinary-api-client", roles = ["ROLE_API"])
    fun `a generic API caller cannot receive proof`() {
        assertThat(given().get("$PROOF_PATH/$syntheticId").statusCode).isEqualTo(403)
    }

    private fun book(transactionId: UUID, synthetic: Boolean): UUID {
        val date = LocalDate.now(ZoneOffset.UTC)
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "$transactionId",
              "entryDate": "$date",
              "valueDate": "$date",
              "createdBy": "$SOURCE_ID",
              "lines": [
                {"glAccountId":"$DEPOSIT_CONTROL","subAccountId":"$SOURCE_ID","side":"DEBIT",
                 "amount":"0.01","currencyCode":"CZK","baseAmount":"0.01","baseCurrencyCode":"CZK"},
                {"glAccountId":"$DEPOSIT_CONTROL","subAccountId":"$TARGET_ID","side":"CREDIT",
                 "amount":"0.01","currencyCode":"CZK","baseAmount":"0.01","baseCurrencyCode":"CZK"}
              ]
            }
        """.trimIndent()
        val response = given().contentType("application/json")
            .apply { if (synthetic) header(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.TRUE_VALUE) }
            .body(payload)
            .post(if (synthetic) "/api/v1/journals/synthetic" else "/api/v1/journals")
        assertThat(response.statusCode).isEqualTo(201)
        assertThat(response.jsonPath().getBoolean("synthetic")).isEqualTo(synthetic)
        return response.jsonPath().getUUID("id")
    }

    private companion object {
        const val SOURCE_ID = "00000000-0000-4000-8000-000000000012"
        const val TARGET_ID = "00000000-0000-4000-8000-000000000013"
        const val DEPOSIT_CONTROL = "a0000000-0000-0000-0000-000000000002"
        const val WRITER = "service-account-openbank-transaction"
        const val READER = "service-account-openbank-synthetic-transfer-verifier"
        const val PROOF_PATH = "/api/v1/journals/synthetic/transactions"
    }
}
