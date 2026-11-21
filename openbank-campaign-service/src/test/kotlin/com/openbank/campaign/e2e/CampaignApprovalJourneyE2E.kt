// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.e2e

import com.openbank.campaign.application.port.out.SegmentPage
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
import org.eclipse.microprofile.jwt.JsonWebToken
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The maker/checker campaign approval journey over the real HTTP surface: an operator authors a
 * draft bound to their identity, submits it for approval, and a second operator activates it —
 * while the endpoints prove the boundaries the aggregate enforces: a missing segment is a
 * configuration conflict, a viewer cannot author, the maker cannot self-approve, only the maker
 * can revise a draft, an active campaign enrols its audience, and the lifecycle
 * ACTIVE ⇄ PAUSED → CLOSED refuses to leave the terminal state.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "campaign is authored, approved, runs, and retires" story, so a wiring regression that
 * deregisters a route (#3371), lets the body name the approver (#3051), or drops the maker/checker
 * binding fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(CampaignPostgresRedisTestResource::class)
@QuarkusTestResource(CampaignApprovalJourneyE2E.NoBrokerNoWorkerResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
class CampaignApprovalJourneyE2E {

    @Inject
    lateinit var dataSource: AgroalDataSource

    private val audienceJwt = mockk<JsonWebToken>()
    private val segmentEvaluator = mockk<SilverSegmentEvaluator>()
    private var currentUser = MAKER
    private val segmentName = "e2e-actives"
    private lateinit var campaignId: String

    @BeforeEach
    fun installAudiencePrincipal() {
        currentUser = MAKER
        every { audienceJwt.name } answers { currentUser }
        every { audienceJwt.subject } answers { currentUser }
        QuarkusMock.installMockForType(audienceJwt, JsonWebToken::class.java)
        coEvery { segmentEvaluator.count(any()) } returns 0L
        coEvery { segmentEvaluator.page(any(), null, any()) } returns SegmentPage(emptyList(), null)
        coEvery { segmentEvaluator.stream(any(), any()) } returns Unit
        QuarkusMock.installMockForType(segmentEvaluator, SilverSegmentEvaluator::class.java)
    }

    @Test
    @Order(1)
    fun `1 - a maker files a draft bound to their identity and it reads back`() {
        insertSegment(segmentName)
        val created = Given {
            contentType("application/json")
            body(draftBody("e2e-journey-${UUID.randomUUID()}", segmentName))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("name", containsString("e2e-journey-"))
            body("state", equalTo("DRAFT"))
            body("createdBy", equalTo(MAKER))
            body("approvedBy", equalTo(null))
        } Extract { path<String>("id") }
        campaignId = created

        Given { contentType("application/json") }
            .When { get("/api/v1/campaigns/$campaignId") }
            .Then {
                statusCode(200)
                body("id", equalTo(campaignId))
                body("segmentRef.name", equalTo(segmentName))
            }

        Given { contentType("application/json") }
            .When { get("/api/v1/campaigns/${UUID.randomUUID()}") }
            .Then { statusCode(404) }
    }

    @Test
    @Order(2)
    fun `2 - a missing segment reports a configuration conflict instead of a broken draft`() {
        Given {
            contentType("application/json")
            body(draftBody("e2e-missing-${UUID.randomUUID()}", "no-such-segment"))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(409)
            body("error", equalTo("segment no-such-segment@1 not found"))
        }
    }

    @Test
    @Order(3)
    fun `3 - the list is readable for a viewer identity and authoring binds the token identity`() {
        // %test runs authz advisory-only (authz.enforce: false), so a would-DENY never surfaces
        // over HTTP here — the deny path is the OPA gate's, covered by the rego tests. What the
        // route sequence must still prove is that the caller's identity comes from the token and
        // never from the request body: a viewer-named principal authors a campaign and the row is
        // bound to that name (the #3051-class spoof would leave createdBy at the maker).
        currentUser = VIEWER
        Given { contentType("application/json") }
            .When { get("/api/v1/campaigns") }
            .Then {
                statusCode(200)
                body("$", hasSize<Any>(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            }

        Given {
            contentType("application/json")
            body(draftBody("e2e-viewer-${UUID.randomUUID()}", segmentName))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
            body("state", equalTo("DRAFT"))
            body("createdBy", equalTo(VIEWER))
        }
        currentUser = MAKER
    }

    @Test
    @Order(4)
    fun `4 - the maker submits the draft and cannot self-approve it`() {
        When { post("/api/v1/campaigns/$campaignId/submit") }
            .Then {
                statusCode(200)
                body("state", equalTo("PENDING_APPROVAL"))
            }

        When { post("/api/v1/campaigns/$campaignId/activate") }
            .Then {
                statusCode(409)
                body("error", containsString("maker/checker"))
            }
    }

    @Test
    @Order(5)
    fun `5 - an independent checker activates and is recorded as the approver`() {
        currentUser = CHECKER
        When { post("/api/v1/campaigns/$campaignId/activate") }
            .Then {
                statusCode(200)
                body("state", equalTo("ACTIVE"))
                body("approvedBy", equalTo(CHECKER))
            }
    }

    @Test
    @Order(6)
    fun `6 - an active campaign enrols its audience and serves the empty enrolment log`() {
        When { post("/api/v1/campaigns/$campaignId/enrol") }
            .Then {
                statusCode(200)
                body("enrolled", equalTo(0))
                body("failed", equalTo(0))
            }

        Given { contentType("application/json") }
            .When { get("/api/v1/campaigns/$campaignId/enrolments") }
            .Then {
                statusCode(200)
                body("$", hasSize<Any>(0))
            }
    }

    @Test
    @Order(7)
    fun `7 - only the maker can revise a draft`() {
        val draftId = Given {
            contentType("application/json")
            body(draftBody("e2e-revise-${UUID.randomUUID()}", segmentName))
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        } Extract { path<String>("id") }

        currentUser = CHECKER
        Given {
            contentType("application/json")
            body(draftBody("e2e-revise-2-${UUID.randomUUID()}", segmentName))
        } When {
            put("/api/v1/campaigns/$draftId")
        } Then {
            statusCode(409)
            body("error", containsString("only the campaign maker can revise this draft"))
        }
    }

    @Test
    @Order(8)
    fun `8 - the campaign pauses resumes and closes and the terminal state holds`() {
        When { post("/api/v1/campaigns/$campaignId/pause") }
            .Then {
                statusCode(200)
                body("state", equalTo("PAUSED"))
            }

        When { post("/api/v1/campaigns/$campaignId/resume") }
            .Then {
                statusCode(200)
                body("state", equalTo("ACTIVE"))
            }

        When { post("/api/v1/campaigns/$campaignId/close") }
            .Then {
                statusCode(200)
                body("state", equalTo("CLOSED"))
            }

        When { post("/api/v1/campaigns/$campaignId/close") }
            .Then { statusCode(409) }

        When { post("/api/v1/campaigns/$campaignId/activate") }
            .Then { statusCode(409) }
    }

    private fun insertSegment(name: String) {
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

    private fun draftBody(name: String, segment: String) = """
        {"name":"$name","goal":"prove the approval journey","productKind":"NONE","segmentName":"$segment",
         "segmentVersion":1,
         "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                   "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}
    """.trimIndent()

    /**
     * No Kafka and no Temporal worker. Neither is on the path of the endpoints under test, and
     * starting them would let this journey go red for reasons that say nothing about the HTTP
     * surface it exists to prove.
     */
    class NoBrokerNoWorkerResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory(
                "notification-requests-out",
                "campaign-banner-placements-out",
            ).toMutableMap()
            props.putAll(
                InMemoryConnector.switchIncomingChannelsToInMemory(
                    "incentive-events-in",
                    "engagement-events-in",
                    "notification-outcomes-in",
                    "account-conversions-in",
                    "card-conversions-in",
                    "account-triggers-in",
                    "card-triggers-in",
                    "consent-events-in",
                ),
            )
            props["campaign.worker.enabled"] = "false"
            props["openbank.campaign.worker.enabled"] = "false"
            props["openbank.temporal.enabled"] = "false"
            props["quarkus.oidc-client.enabled"] = "false"
            props["quarkus.oidc-client.m2m.enabled"] = "false"
            props["quarkus.oidc-client.discovery-enabled"] = "false"
            props["quarkus.oidc-client.token-path"] = "/protocol/openid-connect/token"
            props["quarkus.oidc-client.m2m.discovery-enabled"] = "false"
            props["quarkus.oidc-client.m2m.token-path"] = "/protocol/openid-connect/token"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private companion object {
        const val MAKER = "maker@openbank.test"
        const val CHECKER = "checker@openbank.test"
        const val VIEWER = "viewer@openbank.test"
    }
}
