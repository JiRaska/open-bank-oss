// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.integration

import com.openbank.campaign.infrastructure.incentive.LiveIncentiveOfferRegistry
import com.openbank.campaign.infrastructure.referral.LiveReferralProgramCatalogAdapter
import com.openbank.campaign.infrastructure.segment.SilverSegmentEvaluator
import com.openbank.campaign.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the draft below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that persisted the campaign
 * draft happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace
 * of its own. [CampaignRestContractIT] proves the HTTP surface behaves; this is the half a
 * later "why was this draft ever persisted" investigation in Tempo actually sees.
 *
 * The same mocks as [CampaignRestContractIT] are installed: the JWT and segment/incentive/
 * referral beans the resource injects are mocked there so the HTTP layer is exercised without
 * live upstreams, and the draft-under-test takes the same path.
 */
@QuarkusTest
@QuarkusTestResource(CampaignTraceContractIT.NoBrokerNoWorkerResource::class)
@QuarkusTestResource(com.openbank.campaign.it.CampaignPostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
class CampaignTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    private val audienceJwt = mockk<JsonWebToken>()
    private val segmentEvaluator = mockk<SilverSegmentEvaluator>()
    private val incentiveRegistry = mockk<LiveIncentiveOfferRegistry>()
    private val referralPrograms = mockk<LiveReferralProgramCatalogAdapter>()

    @BeforeEach
    fun installMocks() {
        every { audienceJwt.name } returns "maker@openbank.test"
        every { audienceJwt.subject } returns "maker@openbank.test"
        QuarkusMock.installMockForType(audienceJwt, JsonWebToken::class.java)
        coEvery { segmentEvaluator.evaluate(any()) } returns emptyList()
        QuarkusMock.installMockForType(segmentEvaluator, SilverSegmentEvaluator::class.java)
        QuarkusMock.installMockForType(incentiveRegistry, LiveIncentiveOfferRegistry::class.java)
        coEvery { referralPrograms.resolvePublished(any()) } returns null
        QuarkusMock.installMockForType(referralPrograms, LiveReferralProgramCatalogAdapter::class.java)
    }

    @Test
    fun `creating a campaign draft keeps the request and its row INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"name":"trace-${UUID.randomUUID()}","goal":"trace contract","productKind":"NONE",
                     "segmentName":"actives","segmentVersion":1,
                     "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                               "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},"delaySeconds":0}]}""",
            )
        } When {
            post("/api/v1/campaigns")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/campaigns")
            .requiresAttribute("POST /api/v1/campaigns", "http.response.status_code")
            .requiresAttribute("INSERT campaigns", "db.sql.table")
            .requiresSameTrace("POST /api/v1/campaigns", "INSERT campaigns")
            .hasNoErrorSpan()
            .verifiedAs("campaign-create-draft")
    }

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
}
