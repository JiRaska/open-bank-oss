// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Shared harness for the SCA E2E suites: device key material and the exact dynamic-linking
 * payload formats the service verifies signatures over (RTS Art. 5), the HTTP builders every
 * journey uses, and the JDBC readers that assert durable state (challenge row, decision
 * evidence, outbox events) directly. Splitting the journeys across two concrete classes keeps
 * each one inside the complexity budget; the harness lives here exactly once.
 */
@Suppress("LongParameterList", "TooManyFunctions")
abstract class ScaE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    // ----------------------------------------------------------------------------------------------
    // Device key material and signing — the bytes a device must sign, byte-exact.
    // ----------------------------------------------------------------------------------------------

    protected fun newEs256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    protected fun KeyPair.spkiB64(): String = Base64.getEncoder().encodeToString(public.encoded)

    protected fun KeyPair.signPayload(payload: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(private)
        update(payload.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }

    /** The plain-purpose payload: `id | decision | amount | currency | creditorIban | reference`. */
    protected fun decisionPayload(
        challengeId: UUID,
        decision: String,
        amount: String = "",
        currency: String = "",
        creditorIban: String = "",
        reference: String = "",
    ): String = listOf(challengeId, decision, amount, currency, creditorIban, reference).joinToString("|")

    /** The APPROVAL-purpose canonical payload: `id | decision | APPROVAL | approvalRequestId | payloadSha256`. */
    protected fun approvalPayload(
        challengeId: UUID,
        decision: String,
        approvalRequestId: String,
        payloadSha256: String,
    ): String = listOf(challengeId, decision, "APPROVAL", approvalRequestId, payloadSha256).joinToString("|")

    // ----------------------------------------------------------------------------------------------
    // HTTP builders
    // ----------------------------------------------------------------------------------------------

    protected fun jsonSpec(body: String): RequestSpecification =
        RestAssured.given().contentType(ContentType.JSON).body(body)

    protected fun enrollSpec(party: UUID, credentialId: String, publicKey: String): RequestSpecification = jsonSpec(
        """{"credentialId":"$credentialId","publicKey":"$publicKey","algorithm":"ES256"}""",
    ).pathParam("party", party)

    protected fun loginInitiateBody(party: UUID): String =
        """{"partyId":"$party","purpose":"LOGIN","preferredMethod":"PUSH_NOTIFICATION"}"""

    protected fun paymentInitiateBody(
        party: UUID,
        amount: String,
        currency: String,
        creditorIban: String,
        creditorName: String,
        reference: String,
    ): String = """
        {"partyId":"$party","purpose":"PAYMENT_INITIATION","preferredMethod":"PUSH_NOTIFICATION",
         "dynamicLinkingData":{"amount":"$amount","currency":"$currency","creditorIban":"$creditorIban",
           "creditorName":"$creditorName","reference":"$reference"}}
    """.trimIndent()

    protected fun approvalInitiateBody(party: UUID, approvalRequestId: String, payloadSha256: String): String = """
        {"partyId":"$party","purpose":"APPROVAL","preferredMethod":"PUSH_NOTIFICATION",
         "dynamicLinkingData":{"amount":null,"currency":null,"creditorIban":null,"creditorName":null,
           "reference":null,"approvalRequestId":"$approvalRequestId","payloadSha256":"$payloadSha256"}}
    """.trimIndent()

    protected fun initiateSpec(body: String, idempotencyKey: String? = null): RequestSpecification {
        var spec = jsonSpec(body)
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey)
        }
        return spec
    }

    protected fun decideSpec(
        challengeId: UUID,
        credentialId: String,
        decision: String,
        signature: String,
    ): RequestSpecification = jsonSpec(
        """{"credentialId":"$credentialId","decision":"$decision","signature":"$signature"}""",
    )

    protected fun verifyBody(party: UUID, otp: String? = null): String =
        if (otp == null) """{"partyId":"$party"}""" else """{"partyId":"$party","otp":"$otp"}"""

    @Suppress("LongParameterList")
    protected fun consumeBody(
        party: UUID,
        amount: String? = null,
        currency: String? = null,
        creditor: String? = null,
        reference: String? = null,
        approvalRequestId: String? = null,
        payloadSha256: String? = null,
    ): String {
        val fields = buildList {
            add("\"partyId\":\"$party\"")
            if (amount != null) add("\"amount\":\"$amount\"")
            if (currency != null) add("\"currency\":\"$currency\"")
            if (creditor != null) add("\"creditor\":\"$creditor\"")
            if (reference != null) add("\"reference\":\"$reference\"")
            if (approvalRequestId != null) add("\"approvalRequestId\":\"$approvalRequestId\"")
            if (payloadSha256 != null) add("\"payloadSha256\":\"$payloadSha256\"")
        }
        return "{${fields.joinToString(",")}}"
    }

    // ----------------------------------------------------------------------------------------------
    // JDBC readers / writers
    // ----------------------------------------------------------------------------------------------

    protected fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    protected fun <T> withQuery(sql: String, vararg params: Any, map: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { query ->
                params.forEachIndexed { index, param -> query.setObject(index + 1, param) }
                query.executeQuery().use { rows -> map(rows) }
            }
        }

    protected fun queryString(sql: String, vararg params: Any): String? =
        withQuery(sql, *params) { rows -> if (rows.next()) rows.getString(1) else null }

    protected fun queryLong(sql: String, vararg params: Any): Long =
        withQuery(sql, *params) { rows -> rows.also { check(it.next()) }.getLong(1) }

    protected fun challengeStatus(id: UUID): String? = queryString("SELECT status FROM sca_challenges WHERE id = ?", id)

    protected fun challengeConsumedAt(id: UUID): String? =
        queryString("SELECT consumed_at FROM sca_challenges WHERE id = ?", id)

    protected fun countChallenges(party: UUID): Long =
        queryLong("SELECT count(*) FROM sca_challenges WHERE party_id = ?", party)

    protected fun outboxCount(aggregateId: UUID, eventType: String): Long = queryLong(
        "SELECT count(*) FROM sca_outbox WHERE aggregate_id = ? AND event_type = ?",
        aggregateId,
        eventType,
    )

    protected fun outboxPayload(aggregateId: UUID, eventType: String): JsonNode =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT payload FROM sca_outbox WHERE aggregate_id = ? AND event_type = ? " +
                    "ORDER BY created_at DESC LIMIT 1",
            ).use { query ->
                query.setObject(1, aggregateId)
                query.setObject(2, eventType)
                query.executeQuery().use { rows ->
                    check(rows.next()) { "no $eventType outbox row for $aggregateId" }
                    objectMapper.readTree(rows.getString(1))
                }
            }
        }

    protected fun insertChallenge(
        id: UUID,
        party: UUID,
        purpose: String,
        method: String,
        amount: String? = null,
        currency: String? = null,
        creditorIban: String? = null,
        creditorName: String? = null,
        reference: String? = null,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_challenges " +
                    "(id, party_id, purpose, method, status, expires_at, created_at, attempt_count, " +
                    "max_attempts, dynamic_amount, dynamic_currency, dynamic_creditor_iban, " +
                    "dynamic_creditor_name, dynamic_reference) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', now() + interval '5 minutes', now(), 0, 3, ?, ?, ?, ?, ?)",
            ).use { query ->
                query.setObject(1, id)
                query.setObject(2, party)
                query.setString(3, purpose)
                query.setString(4, method)
                query.setString(5, amount)
                query.setString(6, currency)
                query.setString(7, creditorIban)
                query.setString(8, creditorName)
                query.setString(9, reference)
                check(query.executeUpdate() == 1)
            }
        }
    }

    protected fun ExtractableResponse<Response>.jsonString(path: String): String = this.jsonPath().getString(path)
}
