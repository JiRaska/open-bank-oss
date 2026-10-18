// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.e2e

import com.openbank.consent.application.port.out.ScaChallengeClient
import com.openbank.consent.application.port.out.ScaChallengeSnapshot
import com.openbank.consent.integration.ConsentRevocationOutboxIT
import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * End-to-end consent spine over real HTTP and a real database: the PSD2 TPP flow (grant →
 * SCA-gated activation → validation → re-grant supersession → revocation with the
 * transactional ConsentRevoked outbox row), the GDPR-only marketing flow that is born ACTIVE
 * without SCA (ADR-0205 D1), the AISP 90-day validity clamp, the keyed idempotency contract
 * with the fingerprint-bound reuse refusal, the SCA boundary (missing/unknown/wrong-party/
 * not-completed challenge), party-ownership and grantee-mismatch refusals, and the
 * anonymous/viewer boundary.
 *
 * What is real and what is stood in for: the whole state machine, Postgres, the outbox write
 * and the REST contract run for real. The SCA challenge lookup is a profile-selected
 * `@Alternative` over the resilient REST client — the challenge plane belongs to sca-service,
 * whose own contract suite pins the wire; here the stub answers every SCA boundary
 * deterministically (found / not found / wrong party / not completed / completed).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(ConsentLifecycleE2E.E2EProfile::class)
@QuarkusTestResource(ConsentRevocationOutboxIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class ConsentLifecycleE2E {

    class E2EProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A profile forces this class its own Quarkus boot; pin an ephemeral port so the
            // restart cannot die on QuarkusBindException against the still-bound 8081.
            "quarkus.http.test-port" to "0",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(E2EScaChallengeClient::class.java)
    }

    /** Deterministic SCA challenge plane: register a snapshot per challenge id, JAX-RS 404 when absent. */
    @Alternative
    @ApplicationScoped
    class E2EScaChallengeClient : ScaChallengeClient {
        val challenges: MutableMap<UUID, ScaChallengeSnapshot> = ConcurrentHashMap()

        override suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot =
            challenges[challengeId] ?: throw NotFoundException("no such challenge $challengeId")
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var sca: E2EScaChallengeClient

    private val partyId: UUID = UUID.randomUUID()
    private lateinit var consentId: String
    private lateinit var grantKey: String
    private lateinit var supersededId: String

    private companion object {
        const val OPERATOR = "e2e-consent-operator"
        const val GRANTEE = "tpp-e2e-aisp"
        const val GRANTEE_NAME = "E2E AISP TPP"
        const val IBAN = "CZ6508000000192000145399"
        const val IBAN_2 = "CZ6508000000192000145400"
        val VALID_TO: OffsetDateTime = OffsetDateTime.now().plusDays(30)
    }

    // ---------------------------------------------------------------- PSD2 TPP journey

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `1 - grant is a keyed 201 landing PENDING_SCA`() {
        grantKey = UUID.randomUUID().toString()
        val created = grant(partyId, GRANTEE, listOf("ACCOUNTS_READ", "BALANCES_READ"), listOf(IBAN), grantKey)
        consentId = created.jsonPath().getString("id")
        assertThat(consentId).isNotBlank()
        assertThat(created.jsonPath().getString("status")).isEqualTo("PENDING_SCA")
        assertThat(created.jsonPath().getList<String>("scopes"))
            .containsExactlyInAnyOrder("ACCOUNTS_READ", "BALANCES_READ")
        assertThat(created.jsonPath().getList<String>("accountIbans")).containsExactly(IBAN)
        assertThat(created.header("Location")).endsWith("/api/v1/consents/$consentId")
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `2 - a keyed retry replays the grant and a key reuse with another body is 409`() {
        val replay = grant(partyId, GRANTEE, listOf("ACCOUNTS_READ", "BALANCES_READ"), listOf(IBAN), grantKey)
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(consentId)
        assertThat(countConsents(partyId)).isEqualTo(1)

        // #10916: the key is fingerprint-bound — the same key over a different body must refuse,
        // not replay.
        Given {
            contentType("application/json")
            body(grantBody(partyId, GRANTEE, listOf("ACCOUNTS_READ"), listOf(IBAN_2), grantKey))
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `3 - the AISP 90-day cap clamps an over-long validity`() {
        val created = Given {
            contentType("application/json")
            body(
                grantBody(partyId, "tpp-e2e-clamp", listOf("ACCOUNTS_READ"), emptyList(), null)
                    .replace(VALID_TO.toString(), OffsetDateTime.now().plusDays(200).toString()),
            )
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
        } Extract {
            this
        }
        val clamped = OffsetDateTime.parse(created.jsonPath().getString("validTo"))
        assertThat(clamped)
            .describedAs("PSD2 RTS Art. 10: an AISP consent is capped at 90 days")
            .isBetween(OffsetDateTime.now().plusDays(89), OffsetDateTime.now().plusDays(91))
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `4 - activation walks the whole SCA boundary`() {
        // Not yet active: validation refuses with a reason, never an error.
        val early = validate(consentId, GRANTEE, "ACCOUNTS_READ", IBAN)
        assertThat(early.jsonPath().getBoolean("valid")).isFalse()

        // #3104 — a guard on a non-nullable query parameter is dead code; the contract is 400.
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$consentId/activate")
        } Then {
            statusCode(400)
        }

        // Wrong party: the challenge must vouch for THIS consent's party.
        val foreignChallenge = UUID.randomUUID()
        sca.challenges[foreignChallenge] = ScaChallengeSnapshot(
            foreignChallenge,
            UUID.randomUUID(),
            "CONSENT_GRANT",
            "COMPLETED",
        )
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$consentId/activate?scaSessionId=$foreignChallenge")
        } Then {
            statusCode(422)
        }
        assertThat(storedStatus(consentId)).isEqualTo("PENDING_SCA")

        // Not completed: the challenge exists and matches, but the customer has not finished SCA.
        val openChallenge = UUID.randomUUID()
        sca.challenges[openChallenge] = ScaChallengeSnapshot(openChallenge, partyId, "CONSENT_GRANT", "OPEN")
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$consentId/activate?scaSessionId=$openChallenge")
        } Then {
            statusCode(422)
        }
        assertThat(storedStatus(consentId)).isEqualTo("PENDING_SCA")
    }

    @Test
    @Order(5)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `5 - a completed challenge for this party activates the consent`() {
        // Unknown challenge id: 422, not a 500 out of the resilient client.
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$consentId/activate?scaSessionId=${UUID.randomUUID()}")
        } Then {
            statusCode(422)
        }

        val doneChallenge = UUID.randomUUID()
        sca.challenges[doneChallenge] = ScaChallengeSnapshot(doneChallenge, partyId, "CONSENT_GRANT", "COMPLETED")
        val activated = Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$consentId/activate?scaSessionId=$doneChallenge")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(activated.jsonPath().getString("status")).isEqualTo("ACTIVE")
    }

    @Test
    @Order(6)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `6 - an active consent validates with the RTS Art 10 frequency cap and answers hasActiveConsent`() {
        val covered = validate(consentId, GRANTEE, "ACCOUNTS_READ", IBAN)
        assertThat(covered.jsonPath().getBoolean("valid")).isTrue()
        assertThat(covered.jsonPath().getInt("frequencyPerDay")).isEqualTo(4)
        assertThat(covered.jsonPath().getList<String>("grantedAccounts")).containsExactly(IBAN)
        assertThat(covered.jsonPath().getList<String>("scopes"))
            .containsExactlyInAnyOrder("ACCOUNTS_READ", "BALANCES_READ")

        // The same consent refuses a different grantee and a scope it does not carry.
        assertThat(validate(consentId, "tpp-e2e-other", "ACCOUNTS_READ", IBAN).jsonPath().getBoolean("valid")).isFalse()
        assertThat(validate(consentId, GRANTEE, "PAYMENTS_INITIATE", null).jsonPath().getBoolean("valid")).isFalse()

        // The yes/no sender surface (ADR-0198 D4): true for the granted scope, false for another.
        val check = Given {
            contentType("application/json")
        } When {
            get("/api/v1/consents/party/$partyId/grantee/$GRANTEE/active?scope=ACCOUNTS_READ")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(check.jsonPath().getBoolean("granted")).isTrue()
        val notGranted = Given {
            contentType("application/json")
        } When {
            get("/api/v1/consents/party/$partyId/grantee/$GRANTEE/active?scope=PAYMENTS_INITIATE")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getBoolean("granted")
        }
        assertThat(notGranted).isFalse()
    }

    @Test
    @Order(7)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `7 - re-granting the same grantee and scopes supersedes the old consent`() {
        val secondKey = UUID.randomUUID().toString()
        val regrant = grant(partyId, GRANTEE, listOf("ACCOUNTS_READ", "BALANCES_READ"), listOf(IBAN), secondKey)
        supersededId = regrant.jsonPath().getString("id")
        assertThat(supersededId).isNotEqualTo(consentId)
        assertThat(regrant.jsonPath().getString("status")).isEqualTo("PENDING_SCA")

        val challenge = UUID.randomUUID()
        sca.challenges[challenge] = ScaChallengeSnapshot(challenge, partyId, "CONSENT_GRANT", "COMPLETED")
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/consents/$supersededId/activate?scaSessionId=$challenge")
        } Then {
            statusCode(200)
        }

        assertThat(getConsent(consentId).jsonPath().getString("status")).isEqualTo("SUPERSEDED")
        assertThat(getConsent(supersededId).jsonPath().getString("status")).isEqualTo("ACTIVE")

        // The sender still sees an ACTIVE consent for the grantee — via the replacement.
        val check = Given {
            contentType("application/json")
        } When {
            get("/api/v1/consents/party/$partyId/grantee/$GRANTEE/active?scope=ACCOUNTS_READ")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getBoolean("granted")
        }
        assertThat(check).isTrue()
    }

    @Test
    @Order(8)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `8 - revoking the active consent enqueues exactly one ConsentRevoked outbox row`() {
        val revoked = Given {
            contentType("application/json")
            body("""{"reason":"customer withdrew PSD2 access"}""")
        } When {
            delete("/api/v1/consents/$supersededId?partyId=$partyId")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(revoked.jsonPath().getString("status")).isEqualTo("REVOKED")
        assertThat(storedStatus(supersededId)).isEqualTo("REVOKED")
        assertThat(outboxEventRows(supersededId, "ConsentRevoked"))
            .describedAs("the revocation and its outbox row commit in one transaction (ADR-0126 D3)")
            .isEqualTo(1)

        // Ownership and grantee scoping refuse cleanly.
        Given {
            contentType("application/json")
            body("""{"reason":"not yours"}""")
        } When {
            delete("/api/v1/consents/$consentId?partyId=${UUID.randomUUID()}")
        } Then {
            statusCode(403)
        }
        Given {
            contentType("application/json")
            body("""{"reason":"wrong grantee"}""")
        } When {
            delete("/api/v1/consents/$consentId?partyId=$partyId&granteeId=tpp-e2e-other")
        } Then {
            statusCode(403)
        }

        // After the revocation the sender surface answers no.
        val check = Given {
            contentType("application/json")
        } When {
            get("/api/v1/consents/party/$partyId/grantee/$GRANTEE/active?scope=ACCOUNTS_READ")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getBoolean("granted")
        }
        assertThat(check).isFalse()
    }

    // ---------------------------------------------------------------- GDPR-only flow + guards

    @Test
    @Order(9)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `9 - a GDPR-only marketing consent is born ACTIVE without SCA and mixing scopes refuses`() {
        val marketingParty = UUID.randomUUID()
        val bornActive = Given {
            contentType("application/json")
            body(
                grantBody(
                    marketingParty,
                    "party-service:marketing-comms",
                    listOf("MARKETING_COMMS_EMAIL"),
                    emptyList(),
                    null,
                ),
            )
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
        } Extract {
            this
        }
        assertThat(bornActive.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(bornActive.jsonPath().getList<String>("scopes")).containsExactly("MARKETING_COMMS_EMAIL")

        val check = Given {
            contentType("application/json")
        } When {
            get(
                "/api/v1/consents/party/$marketingParty/grantee/party-service:marketing-comms/active?scope=MARKETING_COMMS_EMAIL",
            )
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getBoolean("granted")
        }
        assertThat(check).isTrue()

        // ADR-0205 D1: mixing a GDPR-only scope with an SCA-required scope is a client error —
        // the request may not ride an SCA-required scope in without the SCA guarantee.
        Given {
            contentType("application/json")
            body(
                grantBody(
                    marketingParty,
                    "party-service:marketing-comms",
                    listOf("MARKETING_COMMS_EMAIL", "ACCOUNTS_READ"),
                    emptyList(),
                    null,
                ),
            )
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @Order(10)
    fun `10 - anonymous callers are rejected`() {
        Given {
            contentType("application/json")
            body(grantBody(UUID.randomUUID(), GRANTEE, listOf("ACCOUNTS_READ"), emptyList(), null))
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @Order(11)
    @TestSecurity(user = "e2e-consent-viewer", roles = ["ROLE_VIEWER"])
    fun `11 - a viewer is outside the consent roles`() {
        Given {
            contentType("application/json")
            body(grantBody(UUID.randomUUID(), GRANTEE, listOf("ACCOUNTS_READ"), emptyList(), null))
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(12)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `12 - unknown consents stay client errors on every surface`() {
        val unknown = UUID.randomUUID()
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/consents/$unknown")
        } Then {
            statusCode(404)
        }
        // The resource-server contract is a boolean answer, not an error: an unknown consent
        // validates as invalid with a machine code.
        val unknownValidation = Given {
            contentType("application/json")
            body("""{"granteeId":"$GRANTEE","requiredScope":"ACCOUNTS_READ","accountIban":null}""")
        } When {
            post("/api/v1/consents/$unknown/validate")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
        assertThat(unknownValidation.jsonPath().getBoolean("valid")).isFalse()
        assertThat(unknownValidation.jsonPath().getString("code")).isEqualTo("CONSENT_NOT_FOUND")
        val foreignPartyConsents = Given {
            contentType("application/json")
        } When {
            get("/api/v1/consents/party/$unknown")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList<Any>("")
        }
        assertThat(foreignPartyConsents).isEmpty()
    }

    // ---------------------------------------------------------------- helpers

    private fun grantBody(
        party: UUID,
        grantee: String,
        scopes: List<String>,
        ibans: List<String>,
        key: String?,
    ): String {
        val ibanJson = ibans.joinToString(",") { "\"$it\"" }
        val keyJson = key?.let { ",\"tppTransactionId\":\"$it\"" } ?: ""
        return """
        {"partyId":"$party","granteeId":"$grantee","granteeType":"TPP","granteeName":"$GRANTEE_NAME",
        "scopes":[${scopes.joinToString(",") { "\"$it\"" }}],"accountIbans":[$ibanJson],
        "validTo":"$VALID_TO","redirectUri":null$keyJson}
        """.trimIndent()
    }

    private fun grant(
        party: UUID,
        grantee: String,
        scopes: List<String>,
        ibans: List<String>,
        key: String?,
    ): ExtractableResponse<Response> = Given {
        contentType("application/json")
        body(grantBody(party, grantee, scopes, ibans, key))
    } When {
        post("/api/v1/consents")
    } Then {
        statusCode(201)
    } Extract {
        this
    }

    private fun getConsent(id: String): ExtractableResponse<Response> = Given {
        contentType("application/json")
    } When {
        get("/api/v1/consents/$id")
    } Then {
        statusCode(200)
    } Extract {
        this
    }

    private fun validate(id: String, grantee: String, scope: String, iban: String?): ExtractableResponse<Response> {
        val ibanJson = iban?.let { "\"$it\"" } ?: "null"
        return Given {
            contentType("application/json")
            body("""{"granteeId":"$grantee","requiredScope":"$scope","accountIban":$ibanJson}""")
        } When {
            post("/api/v1/consents/$id/validate")
        } Then {
            statusCode(200)
        } Extract {
            this
        }
    }

    private fun storedStatus(id: String): String = dataSource.connection.use { c ->
        c.prepareStatement("select status::text from consents where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "consent $id not stored" }
                rs.getString(1)
            }
        }
    }

    private fun countConsents(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from consents where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun outboxEventRows(aggregateId: String, eventType: String): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from consent_outbox where aggregate_id = ? and event_type = ?")
            .use { ps ->
                ps.setObject(1, UUID.fromString(aggregateId))
                ps.setString(2, eventType)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
    }
}
