// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.e2e

import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * E2E suite for the ADR-0284 business-onboarding chain, proving the REAL HTTP wiring — the actual
 * AresRegistryAdapter (with its @Retry/@CircuitBreaker), the real party-service and
 * document-service REST clients (with their OIDC filters and taint propagation) — against
 * [KybE2EWireMockResource], with durable facts asserted over plain JDBC on the shared real
 * Postgres. The module's ITs stub the same ports at the CDI level; this suite excludes those
 * stubs on purpose ([KybE2EProfile]) so the wire evidence means something: the downstream
 * request journal is the proof of what actually crossed the loopback HTTP, verbatim.
 */
@QuarkusTest
@TestProfile(KybE2EProfile::class)
class KybOnboardingE2E {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @BeforeEach
    fun resetJournal() {
        KybE2EWireMockResource.resetJournal()
    }

    private fun aresSubjectHits(ico: String) =
        KybE2EWireMockResource.wm().findAll(getRequestedFor(urlEqualTo("/ekonomicke-subjekty/$ico")))

    private fun aresVrHits(ico: String) =
        KybE2EWireMockResource.wm().findAll(getRequestedFor(urlEqualTo("/ekonomicke-subjekty-vr/$ico")))

    private fun partyCreates() = KybE2EWireMockResource.wm().findAll(postRequestedFor(urlEqualTo("/api/v1/parties")))

    private fun outboxTypes(caseId: String): List<String> =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(
                "select event_type from kyb_outbox where aggregate_id = '$caseId' order by id",
            ).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun caseRow(caseId: String): Triple<String, String?, Int?> =
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(
                "select status, entity_party_id, required_signatures from kyb_cases where case_id = '$caseId'",
            ).use { rs ->
                assertThat(rs.next()).isTrue()
                Triple(rs.getString(1), rs.getString(2), rs.getInt(3).let { if (rs.wasNull()) null else it })
            }
        }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @Suppress("LongMethod") // one continuous journey; splitting it would need shared state between tests
    fun `a sole trader onboards end to end over the real registry party and document services`() {
        val initiator = UUID.randomUUID()

        // 1. the register preview: ARES subject parsed, and the VR call a sole trader must never
        //    get is journal-proven absent (the adapter skips it, not 404-reacts to it).
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"26185610"}""")
        } When { post("/api/v1/kyb/lookup") } Then {
            statusCode(200)
            body("legalName", equalTo("Jan Novák"))
            body("legalFormClass", equalTo("SOLE_TRADER"))
            body("status", equalTo("ACTIVE"))
            body("verification", equalTo("VERIFIED"))
            body("source", equalTo("ares"))
            body("representatives", hasSize<Any>(1))
            body("representatives[0].fullName", equalTo("Jan Novák"))
            body("representationRule.requiredSigners", equalTo(1))
        }
        assertThat(aresSubjectHits("26185610")).isNotEmpty()
        assertThat(aresVrHits("26185610")).isEmpty()

        // 2. start: the real party-service client mints the entity party — the journal is the
        //    evidence: the body verbatim AND the NAMED m2m bearer (#10486 pinned on the wire, the
        //    default-client token differs, so a shared-principal regression fails here).
        val caseId = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", initiator.toString())
                body("""{"scheme":"CZ_ICO","identifier":"26185610","initiatorPartyId":"$initiator"}""")
            } When { post("/api/v1/kyb/cases") } Then {
                statusCode(201)
                body("status", equalTo("REGISTRY_VERIFIED"))
                body("requiredSignatures", equalTo(1))
                body("entityPartyId", notNullValue())
            }
            ).extract().path<String>("id")

        val creates = partyCreates()
        assertThat(creates).hasSize(1)
        assertThat(creates[0].bodyAsString)
            .contains("\"registrationNumber\":\"26185610\"")
            .contains("\"legalName\":\"Jan Novák\"")
            .contains("\"partyType\":\"SOLE_TRADER\"")
            .contains("\"email\":\"cz-26185610@entity.openbank.invalid\"")
        assertThat(creates[0].getHeader("Authorization")).isEqualTo("Bearer kyb-e2e-m2m-token")
        val row = caseRow(caseId)
        assertThat(row.second).isNotNull()
        assertThat(row.third).isEqualTo(1)
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(
                "select source from kyb_registry_extracts where identifier_value = '26185610'",
            ).use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString(1)).isEqualTo("ares")
            }
        }

        // document-service stands in for THIS case from here on: the ensure response must echo
        // the case's own id (requireOwnCeremony refuses a foreign ceremony 409) and the ceremony
        // read must carry the case's own signer, so both stubs are registered per case.
        KybE2EWireMockResource.stubAgreementEnsure(caseId)
        KybE2EWireMockResource.stubCeremony(caseId, listOf(initiator.toString() to "PENDING"))

        // 3. the same initiator retrying is idempotent: same case, still exactly one party mint,
        //    and the register is not even re-asked.
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"scheme":"CZ_ICO","identifier":"26185610","initiatorPartyId":"$initiator"}""")
        } When { post("/api/v1/kyb/cases") } Then {
            statusCode(201)
            body("id", equalTo(caseId))
        }
        assertThat(partyCreates()).hasSize(1)

        // 4. initiator match: who the initiator IS comes from party-service's record (WireMock
        //    answers APPROVED/verifiable), never from the claimed name in the body. A sole trader
        //    needs no co-signers, so the match advances the case straight to READY_TO_SIGN.
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"representativeIndex":0,"claimedName":"whoever the body claims"}""")
        } When { post("/api/v1/kyb/cases/$caseId/initiator") } Then {
            statusCode(200)
            body("status", equalTo("READY_TO_SIGN"))
        }

        // 5. questionnaire + declarations (no outbox rows of their own — see the final outbox pin).
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body(QUESTIONNAIRE)
        } When { put("/api/v1/kyb/cases/$caseId/questionnaire") } Then {
            statusCode(200)
            body("questionnaire.purpose", equalTo("OPERATING_ACCOUNT"))
        }
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body(SOLE_DECLARATIONS)
        } When { put("/api/v1/kyb/cases/$caseId/declarations") } Then { statusCode(200) }

        // 6. the agreement: rendered by the real document-service client; the case records the
        //    ceremony document-service returned.
        val ceremonyId = (
            Given {
                header("X-Customer-Party-Id", initiator.toString())
            } When { post("/api/v1/kyb/cases/$caseId/agreement?lang=cs") } Then {
                statusCode(200)
                body("caseId", equalTo(caseId))
                body("disclosures", hasSize<Any>(2))
            }
            ).extract().path<String>("ceremonyId")
        assertThat(ceremonyId).isNotBlank()

        // 7. accept the disclosures document-service currently holds, then sign: the local
        //    acceptance is not enough — document-service must record the ceremony signature, and
        //    until it does the sign fails closed with CEREMONY_NOT_SIGNED.
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body(ACCEPTED_DISCLOSURES)
        } When { post("/api/v1/kyb/cases/$caseId/agreement/accept") } Then { statusCode(200) }
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"signatureRef":"$ceremonyId"}""")
        } When { post("/api/v1/kyb/cases/$caseId/sign") } Then {
            statusCode(409)
            body("error", equalTo("CEREMONY_NOT_SIGNED"))
        }

        // 8. the customer signs in document-service (the per-case ceremony stub flips PENDING →
        //    SIGNED exactly as document-service would serve it afterwards); now the signature
        //    completes, the mandate grant crosses the real party client, and the case is SIGNED.
        KybE2EWireMockResource.stubCeremony(caseId, listOf(initiator.toString() to "SIGNED"))
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"signatureRef":"$ceremonyId"}""")
        } When { post("/api/v1/kyb/cases/$caseId/sign") } Then {
            statusCode(200)
            body("status", equalTo("SIGNED"))
            body("signedCount", equalTo(1))
        }
        val mandates = KybE2EWireMockResource.wm()
            .findAll(postRequestedFor(urlPathMatching("/api/v1/parties/[0-9a-f-]{36}/mandates")))
        assertThat(mandates).hasSize(1)
        assertThat(mandates[0].bodyAsString)
            .contains("\"agentPartyId\":\"$initiator\"")
            .contains("\"role\":\"OWNER\"")
            .contains("\"authority\":\"SOLE\"")
            .contains("\"source\":\"REGISTRY\"")
            .contains("kyb-case:$caseId:signature:$ceremonyId")
        assertThat(caseRow(caseId).first).isEqualTo("SIGNED")

        // 9. the outbox holds one row per LIFECYCLE event, written in the same transactions as the
        //    case row — questionnaire/declarations/acceptance emit none.
        assertThat(outboxTypes(caseId)).containsExactly(
            "BUSINESS_REGISTRY_VERIFIED",
            "BUSINESS_AGREEMENT_SIGNED",
        )
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `registry search maps hits and the town filter rides textovaAdresa as measured`() {
        Given {
            queryParam("country", "CZ")
            queryParam("name", "stavby")
            queryParam("city", "Praha")
        } When { get("/api/v1/kyb/registry/search") } Then {
            statusCode(200)
            body("hits", hasSize<Any>(2))
            body("totalMatches", equalTo(2))
            body("tooManyMatches", equalTo(false))
            body("hits.find { it.identifier == '45274649' }.name", equalTo("Příklad s.r.o."))
        }
        val searches = KybE2EWireMockResource.wm()
            .findAll(postRequestedFor(urlEqualTo("/ekonomicke-subjekty/vyhledat")))
        assertThat(searches).hasSize(1)
        // The measured filter field: `sidlo.textovaAdresa`, NOT the `nazevObce` every response
        // carries — the wire body proves which one the adapter sent.
        assertThat(searches[0].bodyAsString)
            .contains("\"obchodniJmeno\":\"stavby\"")
            .contains("\"textovaAdresa\":\"Praha\"")
            .doesNotContain("nazevObce")

        // A register without name search declines honestly — 404, and the journal shows the wire
        // was never touched (the GB adapter declines without an API key before any HTTP).
        Given {
            queryParam("country", "GB")
            queryParam("name", "Widgets")
        } When { get("/api/v1/kyb/registry/search") } Then { statusCode(404) }
        assertThat(
            KybE2EWireMockResource.wm()
                .findAll(postRequestedFor(urlEqualTo("/ekonomicke-subjekty/vyhledat"))),
        ).hasSize(1)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `the over-limit search answer is a refine-query outcome carrying the match count, not an outage`() {
        Given {
            queryParam("country", "CZ")
            queryParam("name", "mnoho")
        } When { get("/api/v1/kyb/registry/search") } Then {
            statusCode(200)
            body("hits", hasSize<Any>(0))
            body("tooManyMatches", equalTo(true))
            body("totalMatches", equalTo(2818))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `an unknown identifier opens a manual review case with a review event and no party mint`() {
        val initiator = UUID.randomUUID()
        val before = partyCreates().size

        val caseId = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", initiator.toString())
                body("""{"scheme":"CZ_ICO","identifier":"00000019","initiatorPartyId":"$initiator"}""")
            } When { post("/api/v1/kyb/cases") } Then {
                statusCode(201)
                body("status", equalTo("MANUAL_REVIEW"))
                body("entityPartyId", nullValue())
                body("reviewReason", notNullValue())
            }
            ).extract().path<String>("id")

        // ARES was asked once (404), the register extract cache holds nothing for it, no entity
        // party was minted, and the outbox carries the review event in the same transaction.
        assertThat(aresSubjectHits("00000019")).hasSize(1)
        assertThat(partyCreates().size).isEqualTo(before)
        val (status, entity, _) = caseRow(caseId)
        assertThat(status).isEqualTo("MANUAL_REVIEW")
        assertThat(entity).isNull()
        assertThat(outboxTypes(caseId)).containsExactly("BUSINESS_ONBOARDING_REVIEW_REQUIRED")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a register outage is a 503 after the retry fan-out actually crossed the wire`() {
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"76543218"}""")
        } When { post("/api/v1/kyb/lookup") } Then {
            statusCode(503)
            body("error", equalTo("REGISTRY_UNAVAILABLE"))
        }
        // @Retry(maxRetries = 1) on the real adapter: the initial attempt plus one retry both
        // crossed the wire — a retry that never fires cannot be distinguished from "no retry
        // configured" any other way.
        assertThat(aresSubjectHits("76543218")).hasSize(2)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `validation and caller checks precede any register or party call`() {
        // A wrong check digit is refused before any register is asked.
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"45274648"}""")
        } When { post("/api/v1/kyb/lookup") } Then { statusCode(400) }
        assertThat(aresSubjectHits("45274648")).isEmpty()

        // A body naming a different initiator than the authenticated customer is refused before
        // any register is asked and before any case row exists.
        val stranger = UUID.randomUUID()
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
            body("""{"scheme":"CZ_ICO","identifier":"26185610","initiatorPartyId":"$stranger"}""")
        } When { post("/api/v1/kyb/cases") } Then { statusCode(403) }
        assertThat(aresSubjectHits("26185610")).isEmpty()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `ubo for a Czech identifier is the self-declaration answer, never a not-found`() {
        Given {
            queryParam("scheme", "CZ_ICO")
            queryParam("identifier", "26185610")
        } When { get("/api/v1/kyb/ubo") } Then {
            statusCode(200)
            body("source", equalTo("SELF_DECLARATION"))
            body("requiresDeclaration", equalTo(true))
            body("registerName", notNullValue())
        }

        Given {
            queryParam("scheme", "NOPE")
            queryParam("identifier", "26185610")
        } When { get("/api/v1/kyb/ubo") } Then { statusCode(400) }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a jointly represented s r o is manual review until an operator attests, and one open case per entity`() {
        // The VR document parses: two jednatelé, the joint rule, the court file reference.
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"45274649"}""")
        } When { post("/api/v1/kyb/lookup") } Then {
            statusCode(200)
            body("legalName", equalTo("Příklad s.r.o."))
            body("legalFormClass", equalTo("LIMITED_COMPANY"))
            body("representatives", hasSize<Any>(2))
            body("representatives[0].fullName", equalTo("Jana Nováková"))
            body("representationRule.mode", equalTo("JOINT_N"))
            body("representationRule.requiredSigners", equalTo(2))
            body("sourceRef", equalTo("Městský soud v Praze C 12345"))
        }

        // Without an operator attestation the case is review-bound — the register text alone
        // cannot set the signing quorum.
        val first = UUID.randomUUID()
        val caseId = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", first.toString())
                body("""{"scheme":"CZ_ICO","identifier":"45274649","initiatorPartyId":"$first"}""")
            } When { post("/api/v1/kyb/cases") } Then {
                statusCode(201)
                body("status", equalTo("MANUAL_REVIEW"))
            }
            ).extract().path<String>("id")
        assertThat(outboxTypes(caseId)).containsExactly("BUSINESS_ONBOARDING_REVIEW_REQUIRED")

        // A second person cannot open a second case for the same entity — and the refusal happens
        // before the register is even asked.
        val hitsBefore = aresSubjectHits("45274649").size
        val second = UUID.randomUUID()
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", second.toString())
            body("""{"scheme":"CZ_ICO","identifier":"45274649","initiatorPartyId":"$second"}""")
        } When { post("/api/v1/kyb/cases") } Then { statusCode(403) }
        assertThat(aresSubjectHits("45274649").size).isEqualTo(hitsBefore)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_KYC"])
    fun `the review queue is operator-only and an unparseable status is a 400, not the default list`() {
        // An identifier no stub serves: ARES answers 404 at the WireMock default, the case lands
        // in MANUAL_REVIEW — the queue the operator below reads.
        val applicant = UUID.randomUUID()
        val caseId = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", applicant.toString())
                body("""{"scheme":"CZ_ICO","identifier":"12345679","initiatorPartyId":"$applicant"}""")
            } When { post("/api/v1/kyb/cases") } Then {
                statusCode(201)
                body("status", equalTo("MANUAL_REVIEW"))
            }
            ).extract().path<String>("id")

        Given {
            queryParam("status", "MANUAL_REVIEW")
        } When { get("/api/v1/kyb/cases") } Then {
            statusCode(200)
            body("id", hasItem(caseId))
        }
        // #9038: a typo used to silently substitute MANUAL_REVIEW and answer the WRONG list 200.
        Given {
            queryParam("status", "MANUAL_REVIE")
        } When { get("/api/v1/kyb/cases") } Then { statusCode(400) }
    }

    @Test
    fun `an anonymous caller is refused on the regulatory feed`() {
        Given { this } When { get("/api/v1/kyb/cases") } Then { statusCode(401) }
        Given { this } When { get("/api/v1/kyb/registry/search?country=CZ&name=x") } Then { statusCode(401) }
        Given {
            contentType("application/json")
            body("{}")
        } When { post("/api/v1/kyb/lookup") } Then { statusCode(401) }
    }

    private companion object {
        const val QUESTIONNAIRE = """{"purpose":"OPERATING_ACCOUNT","expectedMonthlyTurnover":"UP_TO_1M",
            "sourceOfFunds":["BUSINESS_REVENUE"],"cashIntensive":false,"countries":["CZ","DE"],
            "taxResidencies":["CZ"],"fatcaStatus":"ACTIVE_NFFE","crsStatus":"ACTIVE_NFE"}"""
        const val SOLE_DECLARATIONS = """{"uboConfirmed":true,"peps":[],"truthful":true}"""
        val ACCEPTED_DISCLOSURES = """{"disclosures":[
            {"code":"VOP_CS","version":"1.1.0","sha256":"${"b".repeat(64)}"},
            {"code":"SAZEBNIK_PO_CS","version":"1.0.0","sha256":"${"c".repeat(64)}"}
        ]}"""
    }
}
