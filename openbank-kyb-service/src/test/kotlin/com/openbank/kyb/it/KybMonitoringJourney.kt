// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.it

import com.openbank.kyb.domain.model.InitiatorIdentity
import io.restassured.RestAssured.given
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The single-signature business journey over real HTTP, and plain-JDBC reads of what it stored,
 * shared by the ongoing-monitoring ITs. Customer requests carry `X-Customer-Party-Id`; operator
 * requests carry none (OPA is off in tests, so the header alone decides the side).
 */
class KybMonitoringJourney(
    private val parties: StubPartyGateway,
    private val documents: StubDocumentGateway,
    private val jdbcUrl: String,
    private val jdbcUser: String = "openbank",
    private val jdbcPassword: String = "openbank_secret",
) {
    val initiator: UUID = UUID.randomUUID()

    /** Everything up to (not including) the one signature the case needs: case id and ceremony id. */
    fun readyToSign(ico: String): Pair<String, String> {
        val representative = StubAresAdapter.MONITORING_ENTITIES.getValue(ico)
        val caseId = customer()
            .body("""{"scheme":"CZ_ICO","identifier":"$ico","initiatorPartyId":"$initiator"}""")
            .post("/api/v1/kyb/cases").then().statusCode(201).extract().path<String>("id")
        parties.identity = InitiatorIdentity(representative, null, verified = true)
        customer().body("""{"representativeIndex":0,"claimedName":"$representative"}""")
            .post("/api/v1/kyb/cases/$caseId/initiator").then().statusCode(200)
        customer().body(QUESTIONNAIRE).put("/api/v1/kyb/cases/$caseId/questionnaire").then().statusCode(200)
        parties.pep[initiator] = false
        customer().body("""{"uboConfirmed":true,"peps":[],"truthful":true}""")
            .put("/api/v1/kyb/cases/$caseId/declarations").then().statusCode(200)
        val ceremonyId = customer().post("/api/v1/kyb/cases/$caseId/agreement?lang=cs")
            .then().statusCode(200).extract().path<String>("ceremonyId")
        val disclosures = StubDocumentGateway.DISCLOSURES.joinToString(",") {
            """{"code":"${it.code}","version":"${it.version}","sha256":"${it.sha256}"}"""
        }
        customer().body("""{"disclosures":[$disclosures]}""")
            .post("/api/v1/kyb/cases/$caseId/agreement/accept").then().statusCode(200)
        documents.signedBy.getOrPut(UUID.fromString(caseId)) { ConcurrentHashMap.newKeySet() }.add(initiator)
        return caseId to ceremonyId
    }

    /** The completing signature. Returns the HTTP status; the caller asserts what it expects. */
    fun sign(caseId: String, ceremonyId: String): Int =
        customer().body("""{"signatureRef":"$ceremonyId"}""").post("/api/v1/kyb/cases/$caseId/sign").statusCode

    /** An approved (SIGNED) case. */
    fun approved(ico: String): String {
        val (caseId, ceremonyId) = readyToSign(ico)
        check(sign(caseId, ceremonyId) == HTTP_OK) { "the completing signature of $ico was refused" }
        return caseId
    }

    fun customer() = given().contentType("application/json").header(PARTY, initiator.toString())

    fun operator() = given().contentType("application/json")

    fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> =
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
            }
        }

    fun execute(sql: String, vararg args: Any?): Int =
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeUpdate()
            }
        }

    /** One row of `kyb_monitored_subjects`, with the transaction that last wrote it. */
    data class Row(
        val subjectRef: String,
        val desired: String,
        val syncState: String,
        val attemptCount: Int,
        val lastError: String?,
        val xmin: String,
    )

    fun rows(caseId: String): List<Row> = query(
        "select subject_ref, desired, sync_state, attempt_count, last_error, xmin::text " +
            "from kyb_monitored_subjects where case_id = ? order by id",
        UUID.fromString(caseId),
    ) { Row(it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5), it.getString(6)) }

    /** The stored case: status, the transaction that last wrote the row, monitoring review, screening. */
    data class Stored(
        val status: String,
        val xmin: String,
        val reviewFrom: String?,
        val screeningJson: String,
        val entityPartyId: String?,
    )

    fun stored(caseId: String): Stored = query(
        "select status, xmin::text, monitoring_review_from, screening_json, entity_party_id::text " +
            "from kyb_cases where case_id = ?",
        UUID.fromString(caseId),
    ) { Stored(it.getString(1), it.getString(2), it.getString(3), it.getString(4).orEmpty(), it.getString(5)) }.single()

    fun events(caseId: String): List<String> = query(
        "select event_type from kyb_outbox where aggregate_id = ? order by id",
        UUID.fromString(caseId),
    ) { it.getString(1) }

    companion object {
        const val PARTY = "X-Customer-Party-Id"
        const val HTTP_OK = 200
        const val QUESTIONNAIRE = """{"purpose":"OPERATING_ACCOUNT","expectedMonthlyTurnover":"UP_TO_1M",
            "sourceOfFunds":["BUSINESS_REVENUE"],"cashIntensive":false,"countries":["CZ","DE"],
            "taxResidencies":["CZ"],"fatcaStatus":"ACTIVE_NFFE","crsStatus":"ACTIVE_NFE"}"""
    }
}
