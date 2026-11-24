// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.integration

import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.domain.model.ReferralProgramRef
import com.openbank.campaign.infrastructure.incentive.LiveIncentiveOfferRegistry
import com.openbank.campaign.infrastructure.referral.LiveReferralProgramCatalogAdapter
import com.openbank.campaign.infrastructure.segment.SilverSegmentEvaluator
import com.openbank.campaign.it.CampaignPostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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
 * The observable distributed shape of creating a campaign.
 *
 * `POST /api/v1/campaigns` persists the draft campaign (`campaigns`) the whole segment → step →
 * send lifecycle hangs off. The spans asserted here are the service's OWN — Quarkus'
 * OpenTelemetry auto-instrumentation produces them for the real HTTP request and the real
 * Postgres write; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract
 * can read what an OTLP collector would receive. Nothing asserted here is created by the test,
 * so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * campaign happened *inside* the request's trace. A write that escaped the request context —
 * an un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); campaign creation is the service's most important write, so it is the right first
 * contract here.
 */
@QuarkusTest
@QuarkusTestResource(CampaignTraceContractIT.NoBrokerNoWorkerResource::class)
@QuarkusTestResource(CampaignPostgresRedisTestResource::class)
@TestProfile(CampaignTraceContractIT.CampaignTraceProfile::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
class CampaignTraceContractIT {

    /** The libs baseline gives this class its own boot (own recorder, dispatcher off). */
    class CampaignTraceProfile : TraceContractProfile() {

        // The OIDC-client keys live HERE and not in [NoBrokerNoWorkerResource] because
        // `quarkus.oidc-client.enabled` is build-time fixed: a QuarkusTestResource only supplies
        // runtime config, so overriding it there dies at boot with 'Build time property cannot be
        // changed at runtime'. Profile overrides join the profile's augmentation, where a
        // build-time key is allowed to differ from the sibling tests' boot.
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // Pin the sampler so the contract is hermetic: a parent-based sampler makes it a
            // function of an incoming traceparent's sampled flag the test does not control, and
            // an unsampled parent legitimately yields no spans (observed as 'observed span names
            // only: []'). Sampling policy is the deployment's concern, not the contract's.
            "quarkus.otel.traces.sampler" to "always_on",
            "quarkus.oidc-client.enabled" to "false",
            "quarkus.oidc-client.m2m.enabled" to "false",
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/protocol/openid-connect/token",
            "quarkus.oidc-client.m2m.discovery-enabled" to "false",
            "quarkus.oidc-client.m2m.token-path" to "/protocol/openid-connect/token",
        )
    }

    /**
     * Same broker-free topology as [CampaignRestContractIT.NoBrokerNoWorkerResource]: every Kafka
     * channel in memory, the worker and Temporal off. The trace contract needs no real broker —
     * the write under test is the campaign row, not the fan-out.
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
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    private val audienceJwt = mockk<JsonWebToken>()
    private val segmentEvaluator = mockk<SilverSegmentEvaluator>()
    private val incentiveRegistry = mockk<LiveIncentiveOfferRegistry>()
    private val referralPrograms = mockk<LiveReferralProgramCatalogAdapter>()

    @BeforeEach
    fun installAudiencePrincipal() {
        every { audienceJwt.name } returns "maker@openbank.test"
        every { audienceJwt.subject } returns "maker@openbank.test"
        QuarkusMock.installMockForType(audienceJwt, JsonWebToken::class.java)
        coEvery { segmentEvaluator.count(any()) } returns 0L
        coEvery { segmentEvaluator.page(any(), null, any()) } returns
            SegmentPage(emptyList(), null)
        QuarkusMock.installMockForType(segmentEvaluator, SilverSegmentEvaluator::class.java)
        QuarkusMock.installMockForType(incentiveRegistry, LiveIncentiveOfferRegistry::class.java)
        coEvery { referralPrograms.resolvePublished(any()) } returns
            ReferralProgramRef(UUID.randomUUID(), "friends-get-friends", 3)
        QuarkusMock.installMockForType(referralPrograms, LiveReferralProgramCatalogAdapter::class.java)
    }

    @Test
    fun `creating a campaign keeps the request and its campaign INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {"name":"trace-${UUID.randomUUID()}","goal":"trace the campaign write",
                 "productKind":"NONE","segmentName":"actives","segmentVersion":1,
                 "steps":[{"order":1,"template":"MARKETING_PRODUCT_OFFER",
                           "variables":{"offerTitle":"T","offerText":"X","ctaText":"Go"},
                           "delaySeconds":0}]}
                """.trimIndent(),
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
            .verifiedAs("campaign-create")
    }
}
