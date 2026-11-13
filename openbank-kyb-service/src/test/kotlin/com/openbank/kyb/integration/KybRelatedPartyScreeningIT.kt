// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.application.port.out.RelatedPartyScreeningResult
import com.openbank.kyb.domain.model.InitiatorIdentity
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.it.StubDocumentGateway
import com.openbank.kyb.it.StubPartyGateway
import com.openbank.kyb.it.StubRelatedPartyScreening
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The related-party screening gate, driven over real HTTP against a real Postgres (#15723).
 *
 * What a mocked repository cannot show, and this does: that a REFUSED approval leaves the stored
 * case exactly where it was (status, signature, mandates, outbox), that the screening answers are
 * persisted even on the attempt that is refused, and that the customer's view of the case never
 * carries the finding.
 *
 * One identity with both the edge's role and an operator's: `@TestSecurity` binds one identity per
 * test, the customer routes are scoped by `X-Customer-Party-Id` and the operator routes by its
 * absence, and OPA is off in tests — so the header alone decides which side a request is on.
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class KybRelatedPartyScreeningIT {

    @Inject lateinit var parties: StubPartyGateway

    @Inject lateinit var documents: StubDocumentGateway

    @Inject lateinit var screening: StubRelatedPartyScreening

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private val initiator: UUID = UUID.randomUUID()

    /** Everything up to (not including) the one signature the case needs. Returns case id and ceremony id. */
    private fun readyToSign(ico: String, representative: String): Pair<String, String> {
        val caseId = (
            Given {
                contentType("application/json")
                header(PARTY, initiator.toString())
                body("""{"scheme":"CZ_ICO","identifier":"$ico","initiatorPartyId":"$initiator"}""")
            } When { post("/api/v1/kyb/cases") } Then {
                statusCode(201)
                body("status", equalTo("REGISTRY_VERIFIED"))
                body("requiredSignatures", equalTo(1))
            }
            ).extract().path<String>("id")
        parties.identity = InitiatorIdentity(representative, null, verified = true)
        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body("""{"representativeIndex":0,"claimedName":"$representative"}""")
        } When { post("/api/v1/kyb/cases/$caseId/initiator") } Then {
            statusCode(200)
            body("status", equalTo("READY_TO_SIGN"))
        }
        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body(QUESTIONNAIRE)
        } When { put("/api/v1/kyb/cases/$caseId/questionnaire") } Then { statusCode(200) }
        // The representative IS the initiator, whose PEP fact is on the customer profile.
        parties.pep[initiator] = false
        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body("""{"uboConfirmed":true,"peps":[],"truthful":true}""")
        } When { put("/api/v1/kyb/cases/$caseId/declarations") } Then {
            // The customer's own step succeeds whatever the screen said, or failed to say.
            statusCode(200)
            body("screening", nullValue())
        }
        val ceremonyId = (
            Given { header(PARTY, initiator.toString()) } When {
                post("/api/v1/kyb/cases/$caseId/agreement?lang=cs")
            } Then { statusCode(200) }
            ).extract().path<String>("ceremonyId")
        val disclosures = StubDocumentGateway.DISCLOSURES.joinToString(",") {
            """{"code":"${it.code}","version":"${it.version}","sha256":"${it.sha256}"}"""
        }
        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body("""{"disclosures":[$disclosures]}""")
        } When { post("/api/v1/kyb/cases/$caseId/agreement/accept") } Then { statusCode(200) }
        documents.signedBy.getOrPut(UUID.fromString(caseId)) { ConcurrentHashMap.newKeySet() }.add(initiator)
        return caseId to ceremonyId
    }

    private fun stored(caseId: String): StoredCase =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            val case = c.prepareStatement(
                "select status, signers_json, screening_json, review_reason from kyb_cases where case_id = ?",
            ).use { st ->
                st.setObject(1, UUID.fromString(caseId))
                st.executeQuery().use { rs ->
                    check(rs.next()) { "case $caseId is not stored" }
                    listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                }
            }
            val events = c.prepareStatement("select event_type from kyb_outbox where aggregate_id = ? order by id")
                .use { st ->
                    st.setObject(1, UUID.fromString(caseId))
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
            StoredCase(case[0], case[1], case[2].orEmpty(), case[3], events)
        }

    private fun reviewEventPayload(caseId: String): String =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.prepareStatement(
                "select payload from kyb_outbox where aggregate_id = ? " +
                    "and event_type = 'BUSINESS_ONBOARDING_REVIEW_REQUIRED' order by id desc limit 1",
            ).use { st ->
                st.setObject(1, UUID.fromString(caseId))
                st.executeQuery().use { rs ->
                    check(rs.next()) { "no review event for $caseId" }
                    rs.getString(1)
                }
            }
        }

    private data class StoredCase(
        val status: String,
        val signersJson: String,
        val screeningJson: String,
        val reviewReason: String?,
        val events: List<String>,
    )

    private fun mandatesFor(caseId: String) = parties.mandates.filter { it.evidenceRef.contains(caseId) }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC"])
    @Suppress("LongMethod") // one continuous journey over one stored case, as in KybCaseApiIT
    fun `a potential hit on a representative blocks activation, reviews, and never reaches the customer`() {
        screening.answers["Radim Prověřený"] =
            RelatedPartyScreeningResult(ScreeningVerdict.POTENTIAL_HIT, false, "PROVERENY Radim")
        val (caseId, ceremonyId) = readyToSign("71000011", "Radim Prověřený")

        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body("""{"signatureRef":"$ceremonyId"}""")
        } When { post("/api/v1/kyb/cases/$caseId/sign") } Then {
            statusCode(200)
            body("status", equalTo("MANUAL_REVIEW"))
            // What the applicant is told: the same words as for any AML review. Not who, not what,
            // not even that a screen was the cause.
            body("reviewReason", equalTo("AML risk review before activation"))
            body("screening", nullValue())
        }
        Given { header(PARTY, initiator.toString()) } When { get("/api/v1/kyb/cases/$caseId") } Then {
            statusCode(200)
            body("reviewReason", equalTo("AML risk review before activation"))
            body("screening", nullValue())
        }

        val row = stored(caseId)
        assertThat(row.status).isEqualTo("MANUAL_REVIEW")
        assertThat(row.screeningJson).contains("POTENTIAL_HIT", "Radim Prověřený", "PROVERENY Radim")
        assertThat(row.reviewReason).isEqualTo("AML risk review before activation")
        assertThat(
            row.events,
        ).contains("BUSINESS_ONBOARDING_REVIEW_REQUIRED").doesNotContain("BUSINESS_AGREEMENT_SIGNED")
        // The published event says what the applicant is told, and nothing a list found.
        assertThat(reviewEventPayload(caseId))
            .contains("\"reviewReason\":\"AML risk review before activation\"")
            .doesNotContain("Prověřený", "PROVERENY", "HIT", "screening", "sanction")
        assertThat(mandatesFor(caseId)).describedAs("nobody may act for the entity before a human decides").isEmpty()

        // The operator's view (no customer header) is where the finding is.
        Given { this } When { get("/api/v1/kyb/cases/$caseId") } Then {
            statusCode(200)
            body("screening.complete", equalTo(true))
            body("screening.openFindings", equalTo(1))
            body("screening.ownerSource", equalTo("SELF_DECLARATION"))
            body("screening.subjects", hasSize<Any>(2))
            body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.sanctions", equalTo("POTENTIAL_HIT"))
            body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.matchedName", equalTo("PROVERENY Radim"))
            body("screening.subjects.find { it.kind == 'ENTITY' }.sanctions", equalTo("CLEAR"))
        }

        // Resolving the review does not decide the finding: refused, and nothing is granted.
        Given {
            contentType("application/json")
            body("""{"requiredSignatures":1}""")
        } When { post("/api/v1/kyb/cases/$caseId/review/resolve") } Then {
            statusCode(422)
            body("error", equalTo("SCREENING_FINDING_OPEN"))
        }
        assertThat(stored(caseId).status).isEqualTo("MANUAL_REVIEW")
        assertThat(mandatesFor(caseId)).isEmpty()

        // The operator decides the finding by its own action; who decided is the authenticated principal.
        val key = (
            Given { this } When { get("/api/v1/kyb/cases/$caseId") } Then { statusCode(200) }
            ).extract().path<String>("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.key")
        Given {
            contentType("application/json")
            body("""{"disposition":"CLEARED_FALSE_POSITIVE","reason":"different date of birth"}""")
        } When { post("/api/v1/kyb/cases/$caseId/screening/findings/$key/disposition") } Then {
            statusCode(200)
            body("status", equalTo("MANUAL_REVIEW"))
            body("screening.openFindings", equalTo(0))
            body(
                "screening.subjects.find { it.kind == 'REPRESENTATIVE' }.disposedBy",
                equalTo("service-account-openbank-edge"),
            )
        }
        assertThat(stored(caseId).screeningJson)
            .contains("CLEARED_FALSE_POSITIVE", "different date of birth", "service-account-openbank-edge")

        // Only now can the review be resolved, and only then do the mandates exist.
        Given {
            contentType("application/json")
            body("""{"requiredSignatures":1}""")
        } When { post("/api/v1/kyb/cases/$caseId/review/resolve") } Then {
            statusCode(200)
            body("status", equalTo("SIGNED"))
        }
        assertThat(mandatesFor(caseId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC"])
    fun `with no screen obtainable approval is refused and nothing is stored as approved, then the retry replays`() {
        screening.unavailable += "Hana Čekající"
        val (caseId, ceremonyId) = readyToSign("71000020", "Hana Čekající")

        // Must-refuse: the completing signature while one subject has no answer.
        repeat(2) {
            Given {
                contentType("application/json")
                header(PARTY, initiator.toString())
                body("""{"signatureRef":"$ceremonyId"}""")
            } When { post("/api/v1/kyb/cases/$caseId/sign") } Then {
                statusCode(503)
                body("error", equalTo("SCREENING_PENDING"))
            }
        }
        val refused = stored(caseId)
        assertThat(refused.status).isEqualTo("READY_TO_SIGN")
        assertThat(refused.signersJson).contains("IDENTIFIED").doesNotContain("\"SIGNED\"")
        // The entity's answer was obtained and kept; the person's is PENDING — not clear.
        assertThat(refused.screeningJson).contains("\"sanctions\":\"CLEAR\"", "\"sanctions\":\"PENDING\"")
        assertThat(refused.events).doesNotContain("BUSINESS_AGREEMENT_SIGNED", "BUSINESS_ONBOARDING_COMPLETED")
        assertThat(mandatesFor(caseId)).isEmpty()

        // Must-clear: the same request, once the screen answers.
        screening.unavailable -= "Hana Čekající"
        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body("""{"signatureRef":"$ceremonyId"}""")
        } When { post("/api/v1/kyb/cases/$caseId/sign") } Then {
            statusCode(200)
            body("status", equalTo("SIGNED"))
        }
        val approved = stored(caseId)
        assertThat(approved.status).isEqualTo("SIGNED")
        assertThat(approved.screeningJson).doesNotContain("PENDING", "HIT")
        assertThat(approved.events).contains("BUSINESS_AGREEMENT_SIGNED")
        assertThat(mandatesFor(caseId)).hasSize(1)

        // Every attempt on the person used ONE idempotency key, so sanctions-service replays.
        val attempts = screening.requests.filter { it.name == "Hana Čekající" }
        assertThat(attempts.size).isGreaterThanOrEqualTo(3)
        assertThat(attempts.map { it.idempotencyKey }.toSet()).hasSize(1)
        assertThat(attempts.first().idempotencyKey).startsWith("kyb-screen:$caseId:")
        assertThat(attempts).allMatch { !it.organisation }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC"])
    fun `a PEP match is stored on that person and reviews, and is not stored as a sanctions hit`() {
        screening.answers["Petr Exponovaný"] =
            RelatedPartyScreeningResult(ScreeningVerdict.CLEAR, true, "EXPONOVANY Petr")
        val (caseId, ceremonyId) = readyToSign("71000038", "Petr Exponovaný")

        Given {
            contentType("application/json")
            header(PARTY, initiator.toString())
            body("""{"signatureRef":"$ceremonyId"}""")
        } When { post("/api/v1/kyb/cases/$caseId/sign") } Then {
            statusCode(200)
            body("status", equalTo("MANUAL_REVIEW"))
            body("screening", nullValue())
        }
        Given { this } When { get("/api/v1/kyb/cases/$caseId") } Then {
            statusCode(200)
            body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.pep", equalTo(true))
            body("screening.subjects.find { it.kind == 'REPRESENTATIVE' }.sanctions", equalTo("CLEAR"))
            body("screening.subjects.find { it.kind == 'ENTITY' }.pep", equalTo(false))
        }
        assertThat(stored(caseId).screeningJson).doesNotContain("HIT")
        assertThat(mandatesFor(caseId)).isEmpty()
    }

    /**
     * Two interleaved writers on one case row. The completing signature has READ the case and is
     * held inside its screening rebuild; an operator rejects the case meanwhile; the signature is
     * then released. Whoever wrote second must not silently win: a rejected case must stay
     * rejected, and the late writer must be told (409), not answered 200.
     */
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_KYC"])
    fun `a stale write cannot overwrite a compliance decision made in between`() {
        val name = "Olga Souběžná"
        screening.unavailable += name
        val (caseId, ceremonyId) = readyToSign("71000046", name)
        screening.unavailable -= name
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        screening.entered[name] = entered
        screening.held[name] = release

        val port = RestAssured.port
        val pool = Executors.newSingleThreadExecutor()
        try {
            val lateSignature = pool.submit<Int> {
                RestAssured.given().port(port).contentType("application/json")
                    .header(PARTY, initiator.toString())
                    .body("""{"signatureRef":"$ceremonyId"}""")
                    .post("/api/v1/kyb/cases/$caseId/sign").statusCode
            }
            assertThat(entered.await(20, TimeUnit.SECONDS)).describedAs("the signature reached its rebuild").isTrue()

            Given {
                contentType("application/json")
                body("""{"reason":"confirmed sanctions match on the entity"}""")
            } When { post("/api/v1/kyb/cases/$caseId/reject") } Then {
                statusCode(200)
                body("status", equalTo("REJECTED"))
            }

            release.complete(Unit)
            assertThat(lateSignature.get(30, TimeUnit.SECONDS)).isEqualTo(409)
        } finally {
            release.complete(Unit)
            screening.held.remove(name)
            screening.entered.remove(name)
            pool.shutdownNow()
        }
        val row = stored(caseId)
        assertThat(row.status).describedAs("the operator's rejection must survive the late write").isEqualTo("REJECTED")
        assertThat(row.events).doesNotContain("BUSINESS_AGREEMENT_SIGNED")
        assertThat(mandatesFor(caseId)).isEmpty()
    }

    private companion object {
        const val PARTY = "X-Customer-Party-Id"
        const val QUESTIONNAIRE = """{"purpose":"OPERATING_ACCOUNT","expectedMonthlyTurnover":"UP_TO_1M",
            "sourceOfFunds":["BUSINESS_REVENUE"],"cashIntensive":false,"countries":["CZ","DE"],
            "taxResidencies":["CZ"],"fatcaStatus":"ACTIVE_NFFE","crsStatus":"ACTIVE_NFE"}"""
    }
}
