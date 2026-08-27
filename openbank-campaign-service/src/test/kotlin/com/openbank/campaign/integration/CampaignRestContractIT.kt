// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.integration

import com.openbank.campaign.domain.model.IncentiveOfferRef
import com.openbank.campaign.infrastructure.incentive.LiveIncentiveOfferRegistry
import com.openbank.campaign.infrastructure.segment.SilverSegmentEvaluator
import com.openbank.campaign.it.CampaignPostgresRedisTestResource
import io.agroal.api.AgroalDataSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The HTTP surface of campaign-service, driven as a real request (#3133).
 *
 * Every other test in this module calls a class directly, which makes an entire family of defects
 * structurally invisible: media-type negotiation, `@Consumes`/`@Produces`, DTO (de)serialisation,
 * response headers, and the Vert.x context a reactive Panache call only gets from a real request.
 *
 * The bug that prompted this: `activate` answered **415** to any caller that sent no body — which is
 * exactly what the console sends — so the "Approve and activate" button could not work. The unit
 * tests call `activate(id, null)` directly and passed against it, because a direct call supplies
 * what the HTTP layer does not. Same shape as the `@Scheduled`/Vert.x lesson in CLAUDE.md.
 *
 * Scope is deliberately the endpoints whose failure mode is HTTP-shaped rather than logic-shaped;
 * the domain rules themselves are already covered by fast unit tests and are not re-litigated here.
 */
@QuarkusTest
@QuarkusTestResource(CampaignRestContractIT.NoBrokerNoWorkerResource::class)
@QuarkusTestResource(CampaignPostgresRedisTestResource::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
@Suppress("LargeClass") // one HTTP contract class intentionally exercises the served campaign resource
class CampaignRestContractIT {

    @Inject
    lateinit var dataSource: AgroalDataSource

    private val audienceJwt = mockk<JsonWebToken>()
    private val segmentEvaluator = mockk<SilverSegmentEvaluator>()
    private val incentiveRegistry = mockk<LiveIncentiveOfferRegistry>()

    @BeforeEach
    fun installAudiencePrincipal() {
        every { audienceJwt.name } returns "maker@openbank.test"
        every { audienceJwt.subject } returns "maker@openbank.test"
        QuarkusMock.installMockForType(audienceJwt, JsonWebToken::class.java)
        coEvery { segmentEvaluator.evaluate(any()) } returns emptyList()
        QuarkusMock.installMockForType(segmentEvaluator, SilverSegmentEvaluator::class.java)
        QuarkusMock.installMockForType(incentiveRegistry, LiveIncentiveOfferRegistry::class.java)
    }

    /**
     * No Kafka and no Temporal worker. Neither is on the path of these endpoints, and starting them
     * would let this test go red for reasons that say nothing about the HTTP contract.
     */
    class NoBrokerNoWorkerResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("notification-requests-out").toMutableMap()
            props.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("consent-events-in"))
            props["campaign.worker.enabled"] = "false"
            props["openbank.campaign.worker.enabled"] = "false"
            props["openbank.temporal.enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private fun draftBody(name: String, segmentName: String = "actives") = """
        {"name":"$name","goal":"prove the HTTP contract","segmentName":"$segmentName","segmentVersion":1,
         "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                   "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
    """.trimIndent()

    private fun draftBodyWithIncentive(name: String, ref: IncentiveOfferRef) = """
        {"name":"$name","goal":"prove immutable incentive selection","segmentName":"actives","segmentVersion":1,
         "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                   "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}],
         "incentiveOfferRef":{"id":"${ref.id}","name":"${ref.name}","version":${ref.version}}}
    """.trimIndent()

    private fun audienceBody(name: String) = """
        {"name":"$name","rules":[{"type":"PARTY_STATUS_IS","status":"ACTIVE"}]}
    """.trimIndent()

    private fun createDraft(name: String = "it-${UUID.randomUUID()}"): String = Given {
        contentType("application/json")
        body(draftBody(name))
    } When {
        post("/api/v1/campaigns")
    } Then {
        statusCode(201)
    } Extract {
        path<String>("id")
    }

    private fun assertAudienceCannotBeCampaignTargeted(name: String) {
        Given {
            contentType("application/json")
            body(draftBody("before-approval-${UUID.randomUUID()}", name))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(409)
            body("error", equalTo("segment $name@1 not found"))
        }
    }

    private fun approveAudienceAsIndependentChecker(name: String) {
        When {
            post("/api/v1/audiences/$name/1/approve")
        } Then {
            statusCode(409)
            body("error", equalTo("maker/checker: the approver must differ from the creator"))
        }

        every { audienceJwt.name } returns "checker@openbank.test"
        every { audienceJwt.subject } returns "checker@openbank.test"

        When {
            post("/api/v1/audiences/$name/1/approve")
        } Then {
            statusCode(200)
            body("state", equalTo("APPROVED"))
            body("approvedBy", equalTo("checker@openbank.test"))
        }
    }

    private fun submit(id: String) = When { post("/api/v1/campaigns/$id/submit") } Then { statusCode(200) }

    private fun insertLegacySegment(name: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO segments (id, name, version, rules_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, name)
                statement.setInt(3, 1)
                statement.setString(4, """[{"type":"PartyStatusIs","status":"ACTIVE"}]""")
                statement.setObject(5, OffsetDateTime.now())
                statement.executeUpdate()
            }
        }
    }

    private fun deleteSegment(name: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM segments WHERE name = ? AND version = 1").use { statement ->
                statement.setString(1, name)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `create reports a missing segment as a configuration conflict`() {
        val missingSegment = "missing-${UUID.randomUUID()}"

        Given {
            contentType("application/json")
            body(draftBody("missing-segment-${UUID.randomUUID()}", missingSegment))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(409)
            body("error", equalTo("segment $missingSegment@1 not found"))
        }
    }

    @Test
    fun `maker can revise an unsubmitted draft through the HTTP contract`() {
        val id = createDraft()
        val revisedName = "revised-${UUID.randomUUID()}"

        Given {
            contentType("application/json")
            body(draftBody(revisedName))
        } When {
            put("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("name", equalTo(revisedName))
            body("state", equalTo("DRAFT"))
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("name", equalTo(revisedName))
        }
    }

    @Test
    fun `campaign pins and reloads the exact published incentive revision`() {
        val ref = IncentiveOfferRef(UUID.randomUUID(), "summer-current-account", 3)
        coEvery { incentiveRegistry.resolvePublished(ref) } returns ref

        val id = Given {
            contentType("application/json")
            body(draftBodyWithIncentive("incentive-${UUID.randomUUID()}", ref))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("incentiveOfferRef.id", equalTo(ref.id.toString()))
            body("incentiveOfferRef.name", equalTo(ref.name))
            body("incentiveOfferRef.version", equalTo(ref.version))
        } Extract {
            path<String>("id")
        }

        When { get("/api/v1/campaigns/$id") } Then {
            statusCode(200)
            body("incentiveOfferRef.id", equalTo(ref.id.toString()))
            body("incentiveOfferRef.name", equalTo(ref.name))
            body("incentiveOfferRef.version", equalTo(ref.version))
        }
    }

    @Test
    fun `campaign rejects an incentive revision that is not published exactly`() {
        val ref = IncentiveOfferRef(UUID.randomUUID(), "retired-reward", 1)
        coEvery { incentiveRegistry.resolvePublished(ref) } returns null

        Given {
            contentType("application/json")
            body(draftBodyWithIncentive("rejected-${UUID.randomUUID()}", ref))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(409)
            body("error", equalTo("published incentive offer ${ref.name}@${ref.version} (${ref.id}) not found"))
        }
    }

    @Test
    fun `operator can reuse a reviewed definition as an independent draft through the HTTP contract`() {
        val sourceName = "source-${UUID.randomUUID()}"
        val sourceId = createDraft(sourceName)
        submit(sourceId)

        val copiedId = When {
            post("/api/v1/campaigns/$sourceId/duplicate")
        } Then {
            statusCode(201)
            body("name", equalTo("Copy of $sourceName"))
            body("state", equalTo("DRAFT"))
            body("createdBy", equalTo("maker@openbank.test"))
            body("approvedBy", nullValue())
        } Extract {
            path<String>("id")
        }

        assertThat(copiedId).isNotEqualTo(sourceId)
        When {
            get("/api/v1/campaigns/$sourceId")
        } Then {
            statusCode(200)
            body("name", equalTo(sourceName))
            body("state", equalTo("PENDING_APPROVAL"))
        }
    }

    @Test
    fun `reuse reports a stale source definition as conflict rather than source not found`() {
        val staleSegment = "retired-${UUID.randomUUID()}"
        insertLegacySegment(staleSegment)
        val sourceId = Given {
            contentType("application/json")
            body(draftBody("stale-${UUID.randomUUID()}", staleSegment))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }
        deleteSegment(staleSegment)

        When {
            post("/api/v1/campaigns/$sourceId/duplicate")
        } Then {
            statusCode(409)
            body("error", equalTo("segment $staleSegment@1 not found"))
        }
    }

    @Test
    fun `approved audience lifecycle is a campaign source only after an independent HTTP approval`() {
        val audienceName = "audience-${UUID.randomUUID()}"

        Given {
            contentType("application/json")
            body(audienceBody(audienceName))
        } When {
            post("/api/v1/audiences")
        } Then {
            statusCode(201)
            body("name", equalTo(audienceName))
            body("version", equalTo(1))
            body("state", equalTo("DRAFT"))
            body("createdBy", equalTo("maker@openbank.test"))
        }

        When {
            post("/api/v1/audiences/$audienceName/1/submit")
        } Then {
            statusCode(200)
            body("state", equalTo("PENDING_APPROVAL"))
        }

        assertAudienceCannotBeCampaignTargeted(audienceName)
        approveAudienceAsIndependentChecker(audienceName)

        When {
            get("/api/v1/audiences/$audienceName/1/preview")
        } Then {
            statusCode(200)
            body("name", equalTo(audienceName))
            body("version", equalTo(1))
        }

        Given {
            contentType("application/json")
            body(draftBody("after-approval-${UUID.randomUUID()}", audienceName))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("segmentRef.name", equalTo(audienceName))
            body("segmentRef.version", equalTo(1))
        }
    }

    private fun insertEnrolment(
        campaignId: UUID,
        state: String,
        cohort: String = "TREATMENT",
        partyId: UUID = UUID.randomUUID(),
    ): UUID {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO enrolments (id, campaign_id, party_id, state, current_step, started_at, experiment_cohort)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, campaignId)
                statement.setObject(3, partyId)
                statement.setString(4, state)
                statement.setInt(5, 1)
                statement.setObject(6, OffsetDateTime.now())
                statement.setString(7, cohort)
                statement.executeUpdate()
            }
        }
        return partyId
    }

    private fun insertConversion(campaignId: UUID, partyId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO send_log (id, campaign_id, party_id, step_order, outcome, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, campaignId)
                statement.setObject(3, partyId)
                statement.setInt(4, 1)
                statement.setString(5, "CONVERTED")
                statement.setObject(6, OffsetDateTime.now())
                statement.executeUpdate()
            }
        }
    }

    private fun insertPushSend(campaignId: UUID, partyId: UUID): UUID {
        val interactionRef = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO send_log (id, campaign_id, party_id, step_order, outcome, occurred_at, delivery_status, channel)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, interactionRef)
                statement.setObject(2, campaignId)
                statement.setObject(3, partyId)
                statement.setInt(4, 0)
                statement.setString(5, "SENT")
                statement.setObject(6, OffsetDateTime.now())
                statement.setString(7, "PENDING")
                statement.setString(8, "PUSH")
                statement.executeUpdate()
            }
        }
        return interactionRef
    }

    /**
     * A campaign row written straight through JDBC so a send-log / engagement row has something to
     * hang off. It must still be a campaign the aggregate can hydrate: `steps_json` of `[]` violates
     * `require(steps.isNotEmpty())`, and every read that loads the whole table — `GET
     * /api/v1/campaigns/planning` — then answers 400 for the entire portfolio because of one row the
     * HTTP API could never have created (#4825).
     */
    private fun insertCampaignForSendLog(campaignId: UUID, incentiveOfferRef: IncentiveOfferRef? = null) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO campaigns (
                    id, name, goal, segment_name, segment_version, steps_json, holdout_percent,
                    state, created_by, created_at, updated_at,
                    incentive_offer_id, incentive_offer_name, incentive_offer_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, campaignId)
                statement.setString(2, "interaction validation fixture")
                statement.setString(3, "prove private ownership validation")
                statement.setString(4, "actives")
                statement.setInt(5, 1)
                statement.setString(6, FIXTURE_STEPS_JSON)
                statement.setInt(7, 0)
                statement.setString(8, "ACTIVE")
                statement.setString(9, "fixture")
                statement.setObject(10, OffsetDateTime.now())
                statement.setObject(11, OffsetDateTime.now())
                statement.setObject(12, incentiveOfferRef?.id)
                statement.setString(13, incentiveOfferRef?.name)
                if (incentiveOfferRef == null) {
                    statement.setNull(14, java.sql.Types.INTEGER)
                } else {
                    statement.setInt(14, incentiveOfferRef.version)
                }
                statement.executeUpdate()
            }
        }
    }

    /**
     * Real Flyway-backed projection fixture.  This deliberately writes the closed `STORIES` value
     * through PostgreSQL rather than only mocking the Kafka consumer: a stale CHECK constraint
     * otherwise keeps unit tests green while every live story event retries at the consumer.
     */
    private fun insertStoryEngagement(campaignId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO campaign_engagement_event
                    (event_id, campaign_id, step_order, channel, surface, type, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, campaignId)
                statement.setInt(3, 0)
                statement.setString(4, "BANNER")
                statement.setString(5, "STORIES")
                statement.setString(6, "IMPRESSION")
                statement.setObject(7, OffsetDateTime.now())
                statement.executeUpdate()
            }
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `interaction validation resolves only server-owned context for the owning party`() {
        // The validation route intentionally needs no campaign lifecycle read. Seed the minimal
        // durable parent directly, so this test runs entirely under the edge's ROLE_API identity
        // rather than borrowing an operator token to create a draft.
        val campaignId = UUID.randomUUID()
        val offer = IncentiveOfferRef(UUID.randomUUID(), "term-deposit-welcome", 2)
        insertCampaignForSendLog(campaignId, offer)
        val owner = UUID.randomUUID()
        val interactionRef = insertPushSend(campaignId, owner)

        Given {
            header("X-Customer-Party-Id", owner.toString())
        } When {
            get("/api/v1/campaigns/interactions/$interactionRef")
        } Then {
            statusCode(204)
        }

        Given {
            header("X-Customer-Party-Id", owner.toString())
        } When {
            get("/api/v1/campaigns/interactions/$interactionRef/attribution")
        } Then {
            statusCode(200)
            body("campaignId", equalTo(campaignId.toString()))
            body("stepOrder", equalTo(0))
            body("channel", equalTo("PUSH"))
            body("incentiveOfferRef.id", equalTo(offer.id.toString()))
            body("incentiveOfferRef.name", equalTo(offer.name))
            body("incentiveOfferRef.version", equalTo(offer.version))
            body("partyId", nullValue())
        }

        Given {
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
        } When {
            get("/api/v1/campaigns/interactions/$interactionRef")
        } Then {
            statusCode(404)
        }
    }

    @Test
    fun `campaign engagement reports attributable story attention through the HTTP contract`() {
        val campaignId = UUID.randomUUID()
        insertCampaignForSendLog(campaignId)
        insertStoryEngagement(campaignId)

        When {
            get("/api/v1/campaigns/$campaignId/engagement")
        } Then {
            statusCode(200)
            body("[0].stepOrder", equalTo(0))
            body("[0].channel", equalTo("BANNER"))
            body("[0].surface", equalTo("STORIES"))
            body("[0].type", equalTo("IMPRESSION"))
            body("[0].count", equalTo(1))
        }
    }

    /**
     * The regression this file exists for, and the reason a manual check was not enough.
     *
     * An entity parameter — even nullable, even with `@Consumes(WILDCARD)` — makes RESTEasy look for
     * a reader for the request's media type. RestAssured defaults a bodyless POST to `text/plain`
     * and gets 415; curl sends no Content-Type at all and gets through. So the first fix passed a
     * hand-run curl and still failed here, which is exactly the gap this file closes: `activate`
     * now declares no entity parameter, so nothing is negotiated and every shape works.
     *
     * All three must reach the maker/checker check — a 415 means the console's approve button is
     * dead again.
     */
    @Test
    fun `activate accepts a request with no body at all`() {
        val id = createDraft()
        submit(id)

        When {
            post("/api/v1/campaigns/$id/activate")
        } Then {
            // 409 = it reached the domain and maker == checker (the test principal created it).
            // Any 415 means negotiation rejected it before the gate was ever consulted.
            statusCode(409)
            body("error", org.hamcrest.Matchers.containsString("approver must differ"))
        }
    }

    @Test
    fun `activate accepts an empty JSON body`() {
        val id = createDraft()
        submit(id)

        Given {
            contentType("application/json")
            body("{}")
        } When {
            post("/api/v1/campaigns/$id/activate")
        } Then {
            statusCode(409)
        }
    }

    /**
     * The legacy shape #3051 kept the parameter for. The value is ignored — what matters is that a
     * caller still sending it is not rejected, which is the whole reason the parameter was retained
     * instead of deleted.
     */
    @Test
    fun `activate still accepts a legacy approver body, and ignores it`() {
        val id = createDraft()
        submit(id)

        Given {
            contentType("application/json")
            body("""{"approver":"someone-else@openbank.test"}""")
        } When {
            post("/api/v1/campaigns/$id/activate")
        } Then {
            // Still 409: the approver comes from the token, so naming a different person in the
            // body cannot satisfy maker != checker. A 200 here would mean the body was believed.
            statusCode(409)
        }
    }

    /**
     * PUSH must be selectable by a caller, not just representable in the domain.
     *
     * #3584 added `Channel.PUSH` and the openapi enum lists it, but `StepRequest` had no `channel`
     * field and the resource hardcoded `Channel.EMAIL` — so the capability was documented and
     * unreachable, and nothing went red: the domain tests construct `CampaignStep` directly and the
     * spec is not executed. Only a real create-then-read over HTTP can tell "the enum has PUSH in
     * it" from "a client can ask for PUSH".
     */
    @Test
    fun `a step can be created on PUSH and reads back as PUSH`() {
        val body = """
            {"name":"push-${UUID.randomUUID()}","goal":"prove PUSH is reachable over HTTP",
             "segmentName":"actives","segmentVersion":1,
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER_PUSH","channel":"PUSH",
                       "variables":{"offerTitle":"T"},"delaySeconds":0}]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("steps[0].channel", org.hamcrest.Matchers.equalTo("PUSH"))
        } Extract {
            path<String>("id")
        }

        // Read back through a second request: the create response could be echoing the request DTO
        // rather than what was persisted, which would pass the assertion above and still mean the
        // step runs on EMAIL.
        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("steps[0].channel", org.hamcrest.Matchers.equalTo("PUSH"))
        }
    }

    /**
     * The other half: making the channel caller-supplied must not make it caller-*chosen*. The
     * `CampaignStep` init invariant is the validation — an email template on a PUSH step would put
     * offer body copy into an APNs payload, the leak #1182 closed — and it has to survive the trip
     * through the REST layer, where a failed `require()` becomes a 400 via libs' common mappers.
     */
    @Test
    fun `a template that renders on EMAIL is rejected on a PUSH step`() {
        val body = """
            {"name":"mismatch-${UUID.randomUUID()}","goal":"prove the invariant reaches HTTP",
             "segmentName":"actives","segmentVersion":1,
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER","channel":"PUSH",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
        """.trimIndent()

        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(400)
        }
    }

    /**
     * A body written before `channel` existed must keep its meaning — the field is optional with an
     * EMAIL default, so every stored campaign and every existing client is unaffected.
     */
    @Test
    fun `a step with no channel field still defaults to EMAIL`() {
        val id = createDraft()

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("steps[0].channel", org.hamcrest.Matchers.equalTo("EMAIL"))
        }
    }

    @Test
    fun `two decision paths can name the same explicit source step`() {
        val body = """
            {"name":"decision-${UUID.randomUUID()}","goal":"prove an explicit decision source survives HTTP",
             "segmentName":"actives","segmentVersion":1,
             "steps":[
               {"order":1,"template":"MARKETING_PRODUCT_OFFER",
                "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0},
               {"order":2,"template":"MARKETING_PRODUCT_OFFER",
                "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0,
                "condition":"IF_PREVIOUS_CONFIRMED","conditionSourceOrder":1},
               {"order":3,"template":"MARKETING_PRODUCT_OFFER",
                "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0,
                "condition":"IF_PREVIOUS_NOT_CONFIRMED","conditionSourceOrder":1}
             ]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("steps[1].conditionSourceOrder", org.hamcrest.Matchers.equalTo(1))
            body("steps[2].conditionSourceOrder", org.hamcrest.Matchers.equalTo(1))
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("steps[1].conditionSourceOrder", org.hamcrest.Matchers.equalTo(1))
            body("steps[2].conditionSourceOrder", org.hamcrest.Matchers.equalTo(1))
        }
    }

    @Test
    fun `an explicit decision graph survives the campaign HTTP contract`() {
        val body = """
            {"name":"graph-${UUID.randomUUID()}","goal":"prove an explicit graph survives HTTP",
             "segmentName":"actives","segmentVersion":1,
             "steps":[
               {"order":1,"template":"MARKETING_PRODUCT_OFFER",
                "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0},
               {"order":2,"template":"MARKETING_PRODUCT_OFFER",
                "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0},
               {"order":3,"template":"MARKETING_PRODUCT_OFFER",
                "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}
             ],
             "decisions":[{"sourceStepOrder":1,"evaluationDelaySeconds":60,
               "confirmedStepOrder":2,"notConfirmedStepOrder":3}]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("decisions[0].sourceStepOrder", org.hamcrest.Matchers.equalTo(1))
            body("decisions[0].evaluationDelaySeconds", org.hamcrest.Matchers.equalTo(60))
            body("decisions[0].confirmedStepOrder", org.hamcrest.Matchers.equalTo(2))
            body("decisions[0].notConfirmedStepOrder", org.hamcrest.Matchers.equalTo(3))
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("decisions[0].sourceStepOrder", org.hamcrest.Matchers.equalTo(1))
            body("decisions[0].notConfirmedStepOrder", org.hamcrest.Matchers.equalTo(3))
        }
    }

    /**
     * These terminal reasons are operator-visible contract values, not internal workflow detail.
     * Drive the real endpoint over rows that can only be produced by live journey control: a test
     * of the enum alone would pass while REST serialization or the repository mapping rejected it.
     */
    @Test
    fun `journey control terminal reasons survive persistence and the HTTP contract`() {
        val campaignId = UUID.fromString(createDraft())
        insertEnrolment(campaignId, "TERMINATED_CAMPAIGN_CLOSED")
        insertEnrolment(campaignId, "COMPLETED_GOAL_REACHED")

        When {
            get("/api/v1/campaigns/$campaignId/enrolments")
        } Then {
            statusCode(200)
            body(
                "state",
                containsInAnyOrder("TERMINATED_CAMPAIGN_CLOSED", "COMPLETED_GOAL_REACHED"),
            )
        }
    }

    @Test
    fun `holdout is durable and its experiment compares two independently counted cohorts`() {
        val body = """
            {"name":"experiment-${UUID.randomUUID()}","goal":"measure incremental account opening",
             "segmentName":"actives","segmentVersion":1,"conversionRule":"ACCOUNT_OPENED","holdoutPercent":20,
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("holdoutPercent", org.hamcrest.Matchers.equalTo(20))
        } Extract {
            path<String>("id")
        }
        val campaignId = UUID.fromString(id)
        val treatmentParty = insertEnrolment(campaignId, "ACTIVE")
        insertEnrolment(campaignId, "HOLDOUT", cohort = "HOLDOUT")
        insertConversion(campaignId, treatmentParty)

        When {
            get("/api/v1/campaigns/$id/experiment")
        } Then {
            statusCode(200)
            body("treatment.assigned", org.hamcrest.Matchers.equalTo(1))
            body("treatment.converted", org.hamcrest.Matchers.equalTo(1))
            body("holdout.assigned", org.hamcrest.Matchers.equalTo(1))
            body("decision.state", org.hamcrest.Matchers.equalTo("COLLECTING_DATA"))
            body("decision.minimumAssignedPerCohort", org.hamcrest.Matchers.equalTo(100))
            body("holdout.converted", org.hamcrest.Matchers.equalTo(0))
            body("observedLiftPercentagePoints", org.hamcrest.Matchers.equalTo(100.0f))
        }
    }

    /**
     * A cadence must survive the create-and-read round trip, and be readable back as itself.
     *
     * Asserted over a second request rather than the create response, for the same reason the PUSH
     * channel test does: the 201 body could be echoing the request DTO, which would pass while the
     * campaign was stored with no cadence at all and never enrolled anyone again.
     */
    @Test
    fun `a campaign can be created with a cadence and reads it back`() {
        val body = """
            {"name":"cron-${UUID.randomUUID()}","goal":"prove the cadence round trip",
             "segmentName":"actives","segmentVersion":1,
             "schedule":{"cadence":"WEEKLY_MONDAY_MORNING","endAt":"2026-12-31T00:00:00Z"},
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("schedule.cadence", org.hamcrest.Matchers.equalTo("WEEKLY_MONDAY_MORNING"))
            body("schedule.endAt", org.hamcrest.Matchers.notNullValue())
        }
    }

    /**
     * A cadence outside the catalogue is a 400, not a stored campaign.
     *
     * This is the whole argument for a catalogue over a cron string: the rejected value here is a
     * perfectly well-formed cron, and accepting it would produce a campaign that looks scheduled in
     * the console and never fires — a failure with no error anywhere to notice it by.
     */
    @Test
    fun `an unknown cadence is rejected rather than stored`() {
        val body = """
            {"name":"badcron-${UUID.randomUUID()}","goal":"prove the catalogue rejects free text",
             "segmentName":"actives","segmentVersion":1,
             "schedule":{"cadence":"*/5 * * * *"},
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
        """.trimIndent()

        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(400)
        }
    }

    /** A body written before cadences existed keeps its meaning: one-shot, no schedule. */
    @Test
    fun `a campaign with no schedule field reads back without one`() {
        val id = createDraft()

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("schedule", org.hamcrest.Matchers.nullValue())
        }
    }

    /**
     * The console builds its cadence picker from this list, so it must be served and must agree
     * with what create accepts — an option the authoring screen offers and the service rejects is
     * a dead control.
     */
    @Test
    fun `the cadence catalogue is served with its human form and zone`() {
        When {
            get("/api/v1/campaigns/cadences")
        } Then {
            statusCode(200)
            body("cadence", org.hamcrest.Matchers.hasItem("DAILY_MORNING"))
            body("find { it.cadence == 'DAILY_MORNING' }.humanForm", org.hamcrest.Matchers.containsString("09:00"))
            // Never UTC: a cron without a zone fires an hour or two off the customer's morning.
            body("find { it.cadence == 'DAILY_MORNING' }.zone", org.hamcrest.Matchers.equalTo("Europe/Prague"))
        }
    }

    @Test
    fun `planning keeps a submitted recurring campaign visibly unplanned until activation`() {
        val name = "planned-${UUID.randomUUID()}"
        val id = Given {
            contentType("application/json")
            body(
                draftBody(name).replace(
                    "\"steps\":[",
                    "\"schedule\":{\"cadence\":\"DAILY_MORNING\"},\"steps\":[",
                ),
            )
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }
        submit(id)

        When {
            get("/api/v1/campaigns/planning")
        } Then {
            statusCode(200)
            body("find { it.campaignId == '$id' }.entry", equalTo("SCHEDULED"))
            body("find { it.campaignId == '$id' }.cadence", equalTo("DAILY_MORNING"))
            body("find { it.campaignId == '$id' }.zone", equalTo("Europe/Prague"))
            // A schedule is created only after four-eyes activation. Rendering a date here would
            // make an unapproved campaign look operationally live.
            body("find { it.campaignId == '$id' }.nextScheduledWindowAt", nullValue())
        }
    }

    /** Studio reads the exact catalogue the aggregate validates, including authenticated app placement. */
    @Test
    fun `the template catalogue serves channels declared variables and in-app surfaces`() {
        When {
            get("/api/v1/campaigns/templates")
        } Then {
            statusCode(200)
            body(
                "template",
                org.hamcrest.Matchers.hasItems("MARKETING_PRODUCT_OFFER", "MARKETING_PRODUCT_OFFER_BANNER"),
            )
            body(
                "find { it.template == 'MARKETING_PRODUCT_OFFER_PUSH' }.channel",
                org.hamcrest.Matchers.equalTo("PUSH"),
            )
            body(
                "find { it.template == 'MARKETING_PRODUCT_OFFER_PUSH' }.variables",
                org.hamcrest.Matchers.contains("offerTitle"),
            )
            body(
                "find { it.template == 'MARKETING_PRODUCT_OFFER_BANNER' }.inAppSurface",
                org.hamcrest.Matchers.equalTo("HOME_BANNER"),
            )
            body(
                "find { it.template == 'MARKETING_PRODUCT_OFFER_STORY' }.inAppSurface",
                org.hamcrest.Matchers.equalTo("STORIES"),
            )
        }
    }

    /** Studio explains the live policy values but never turns them into a person-level delivery promise. */
    @Test
    fun `the contact guardrails are served with the active platform values`() {
        When {
            get("/api/v1/campaigns/guardrails")
        } Then {
            statusCode(200)
            body("maxSendsPerParty", org.hamcrest.Matchers.equalTo(2))
            body("sendWindowHours", org.hamcrest.Matchers.equalTo(168))
            body("quietHoursStart", org.hamcrest.Matchers.equalTo(21))
            body("quietHoursEnd", org.hamcrest.Matchers.equalTo(8))
            body("timeZone", org.hamcrest.Matchers.equalTo("Europe/Prague"))
        }
    }

    /** A trigger key survives the round trip, so a campaign can declare what wakes it. */
    @Test
    fun `a campaign can declare a trigger and reads it back`() {
        val body = """
            {"name":"trig-${UUID.randomUUID()}","goal":"prove the trigger round trip",
             "segmentName":"actives","segmentVersion":1,"trigger":"ACCOUNT_OPENED",
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("trigger", org.hamcrest.Matchers.equalTo("ACCOUNT_OPENED"))
        }
    }

    /**
     * An uncatalogued trigger is a 400.
     *
     * Stored, it would be a campaign that looks event-driven and waits forever, because no consumer
     * watches a topic nobody named — the same dead-capability shape as an unknown cadence.
     */
    @Test
    fun `an unknown trigger is rejected rather than stored`() {
        val body = """
            {"name":"badtrig-${UUID.randomUUID()}","goal":"prove the catalogue rejects invented triggers",
             "segmentName":"actives","segmentVersion":1,"trigger":"CUSTOMER_SNEEZED",
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
        """.trimIndent()

        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(400)
        }
    }

    /** Studio reads these labels from the service instead of duplicating an executable event list. */
    @Test
    fun `the trigger catalogue is served with its operator-facing meaning`() {
        When {
            get("/api/v1/campaigns/triggers")
        } Then {
            statusCode(200)
            body("trigger", org.hamcrest.Matchers.hasItem("ACCOUNT_OPENED"))
            body(
                "find { it.trigger == 'ACCOUNT_OPENED' }.humanForm",
                org.hamcrest.Matchers.equalTo("when an account is opened"),
            )
        }
    }

    /** A/B paths survive HTTP and have a measured endpoint even before either arm has enrolled. */
    @Test
    fun `a path experiment keeps both declared arms and exposes its empty measurement`() {
        val body = """
            {"name":"ab-${UUID.randomUUID()}","goal":"prove A/B content round trip",
             "segmentName":"actives","segmentVersion":1,"conversionRule":"ACCOUNT_OPENED",
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"A","offerText":"A copy","ctaText":"Go"},
                       "variantBVariables":{"offerTitle":"B"},
                       "variantBTemplate":"MARKETING_PRODUCT_OFFER_PUSH","variantBChannel":"PUSH",
                       "variantBDelaySeconds":86400,
                       "delaySeconds":0}]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("steps[0].variantBVariables.offerTitle", org.hamcrest.Matchers.equalTo("B"))
            body("steps[0].variantBTemplate", org.hamcrest.Matchers.equalTo("MARKETING_PRODUCT_OFFER_PUSH"))
            body("steps[0].variantBChannel", org.hamcrest.Matchers.equalTo("PUSH"))
            body("steps[0].variantBDelaySeconds", org.hamcrest.Matchers.equalTo(86400))
        }
        When {
            get("/api/v1/campaigns/$id/content-experiment")
        } Then {
            statusCode(200)
            body("a.assigned", org.hamcrest.Matchers.equalTo(0))
            body("b.assigned", org.hamcrest.Matchers.equalTo(0))
            body("decision.state", org.hamcrest.Matchers.equalTo("COLLECTING_DATA"))
        }
    }

    @Test
    fun `a mobile-first push step keeps its closed app destination over the HTTP contract`() {
        val body = """
            {"name":"push-${UUID.randomUUID()}","goal":"savings activation",
             "segmentName":"actives","segmentVersion":1,
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER_PUSH","channel":"PUSH",
                       "variables":{"offerTitle":"Savings"},"mobileDestination":"SAVINGS","delaySeconds":0}]}
        """.trimIndent()

        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("steps[0].channel", org.hamcrest.Matchers.equalTo("PUSH"))
            body("steps[0].mobileDestination", org.hamcrest.Matchers.equalTo("SAVINGS"))
        }
    }

    @Test
    fun `the segment catalogue is served over HTTP with its rules in words`() {
        When {
            get("/api/v1/segments")
        } Then {
            statusCode(200)
            body("name", org.hamcrest.Matchers.hasItem("actives"))
            body("find { it.name == 'actives' }.rules[0]", org.hamcrest.Matchers.equalTo("party status is ACTIVE"))
        }
    }

    /**
     * The 404 must not echo the requested name back: reflecting caller input into a response body is
     * the reflected-XSS shape CodeQL flagged as `java/xss` (high) on #3055.
     */
    @Test
    fun `an unknown segment 404s without echoing what was asked for`() {
        val body = When {
            get("/api/v1/segments/%3Cscript%3Ealert(1)%3C%2Fscript%3E/1/preview")
        } Then {
            statusCode(404)
        } Extract {
            asString()
        }

        assertThat(body).doesNotContain("script")
    }

    /**
     * The console builds its page shape from these headers, so a silently dropped header degrades
     * the UI — a page with no total renders as "this is everything" whether or not it is — without
     * failing anything. The body must stay an array: wrapping it would be a breaking contract
     * change (ADR-0048 D5).
     */
    @Test
    fun `the send log answers with an array plus its pagination headers`() {
        val id = createDraft()

        When {
            get("/api/v1/campaigns/$id/sends?page=0&size=2")
        } Then {
            statusCode(200)
            contentType(org.hamcrest.Matchers.containsString("json"))
            header("X-Total-Count", "0")
            header("X-Page", "0")
            header("X-Page-Size", "2")
            // A JSON array, not an object: `size()` only resolves against an array, so this
            // assertion fails outright if the body were ever wrapped in a page object.
            body("size()", org.hamcrest.Matchers.equalTo(0))
        }
    }

    @Test
    fun `an unrecognised outcome is a 400 listing what is allowed, never an unfiltered page`() {
        val id = createDraft()

        When {
            get("/api/v1/campaigns/$id/sends?outcome=NOPE")
        } Then {
            // Answering a filter the caller did not ask for with every row reads as
            // "no suppressions here", which is the wrong conclusion to hand an operator.
            statusCode(400)
            body("allowed", org.hamcrest.Matchers.hasItem("SUPPRESSED_CONSENT"))
        }
    }

    @Test
    fun `the send summary is keyed by outcome name so the console can label it`() {
        val id = createDraft()

        When {
            get("/api/v1/campaigns/$id/sends/summary")
        } Then {
            statusCode(200)
            body("SENT", org.hamcrest.Matchers.equalTo(0))
            body("SUPPRESSED_CONSENT", org.hamcrest.Matchers.equalTo(0))
        }
    }

    /**
     * The template catalogue rejects by construction, but the operator only benefits if the reason
     * survives the trip out through the exception mapper — "400" alone does not say what to change.
     */
    @Test
    fun `an unknown template is a 400 whose message names the template`() {
        val body = """
            {"name":"it-bad-template","goal":"must fail","segmentName":"actives","segmentVersion":1,
             "steps":[{"order":1,"template":"WINBACK_CS","variables":{},"delaySeconds":0}]}
        """.trimIndent()

        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(400)
            body("message", org.hamcrest.Matchers.containsString("WINBACK_CS"))
        }
    }

    @Test
    fun `a variable the template does not declare is a 400 that names the variable`() {
        val body = """
            {"name":"it-bad-variable","goal":"must fail","segmentName":"actives","segmentVersion":1,
             "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                       "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go","bodyHtml":"<b>no</b>"},
                       "delaySeconds":0}]}
        """.trimIndent()

        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(400)
            body("message", org.hamcrest.Matchers.containsString("bodyHtml"))
        }
    }

    @Test
    fun `a stop condition survives the create-and-read round trip`() {
        val id = Given {
            contentType("application/json")
            body(
                """
                {"name":"it-stop-condition","goal":"prove the HTTP contract","segmentName":"actives","segmentVersion":1,
                 "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                           "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}],
                 "stopCondition":{"maxSendsPerParty":2}}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("stopCondition.maxSendsPerParty", org.hamcrest.Matchers.equalTo(2))
        } Extract {
            path<String>("id")
        }

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("stopCondition.maxSendsPerParty", org.hamcrest.Matchers.equalTo(2))
        }
    }

    @Test
    fun `a draft without a stop condition reads back with none — absent never invents a cap`() {
        val id = createDraft("it-no-stop-condition")

        When {
            get("/api/v1/campaigns/$id")
        } Then {
            statusCode(200)
            body("stopCondition", org.hamcrest.Matchers.nullValue())
        }
    }

    companion object {
        /**
         * The same single step [draftBody] posts. A JDBC-written fixture has to be a campaign the
         * aggregate can hydrate, or it poisons every endpoint that lists the whole table.
         */
        private const val FIXTURE_STEPS_JSON =
            "[{\"order\":1,\"template\":\"MARKETING_PRODUCT_OFFER\",\"channel\":\"EMAIL\"," +
                "\"variables\":{\"offerTitle\":\"T\",\"offerText\":\"X\",\"ctaText\":\"Go\"},\"delaySeconds\":0}]"
    }
}
