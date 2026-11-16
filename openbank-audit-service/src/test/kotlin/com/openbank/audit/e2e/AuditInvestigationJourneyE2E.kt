// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.libs.testing.containers.PostgresTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

/**
 * The auditor investigation journey over the real HTTP query surface: entries flow in through
 * the same repository write path the Kafka consumers commit (the transport half is proven by
 * `AgentAuditKafkaPersistenceIT`; this journey is about the operator-visible read routes), and
 * an auditor then walks trail → evidence bundle → actor trail → integrity walk (full and
 * tail-anchored) → the two customer transparency projections → the role gate.
 *
 * The module's ITs prove one property each; this proves the route sequence serves a whole
 * investigation, so a wiring regression that deregisters a route (#3371), drops the OPA action
 * from a route, or breaks the chain-hashed read models fails here even when every property-level
 * assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(AuditInvestigationJourneyE2E.EnforcedAuthzProfile::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditInvestigationJourneyE2E {

    /** Enforced authz against a stub OPA that models the auditor grant (rest.rego is unit-tested). */
    class EnforcedAuthzProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "true")
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(StubPolicyServer::class.java))
    }

    /**
     * Faithful-enough model of the audit rules for this journey: the auditor role holds
     * `audit.trail.inspect` / `audit.evidence.reconstruct` / `audit.verify`; the edge-proxied
     * customer-transparency action `audit.customerRead` is granted to any authenticated
     * principal (the edge is what narrows it); everything else is denied.
     */
    class StubPolicyServer : QuarkusTestResourceLifecycleManager {
        private lateinit var server: HttpServer

        override fun start(): Map<String, String> {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/data/openbank/rest/allow") { exchange ->
                exchange.use {
                    val input = ObjectMapper().readTree(exchange.requestBody)["input"]
                    val action = input["action"].asText()
                    val roles = input["principal"]["roles"].map { it.asText() }
                    val allowed = when {
                        action == "audit.customerRead" -> true
                        action in AUDITOR_ACTIONS -> "ROLE_AUDITOR" in roles
                        else -> false
                    }
                    val response = "{\"result\":{\"allow\":$allowed}}".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.write(response)
                }
            }
            server.start()
            return mapOf("opa.url" to "http://127.0.0.1:${server.address.port}")
        }

        override fun stop() = server.stop(0)

        private companion object {
            val AUDITOR_ACTIONS = setOf("audit.trail.inspect", "audit.evidence.reconstruct", "audit.verify")
        }
    }

    @Inject
    lateinit var repository: AuditRepository

    private val applicationId = "loan-application-${UUID.randomUUID()}"
    private val grantorId = "party-grantor-${UUID.randomUUID()}"
    private val delegateId = "delegate-ops-${UUID.randomUUID()}"
    private val delegationId = "deleg-${UUID.randomUUID()}"
    private lateinit var seeds: List<AuditEntry>

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun entry(
        aggregateId: String,
        aggregateType: String,
        eventType: String,
        actorId: String,
        occurredAt: String,
        operation: String,
        channel: String? = null,
    ) = AuditEntry(
        id = UUID.randomUUID(),
        eventType = eventType,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        actorId = actorId,
        actorType = "HUMAN",
        payload = """{"aggregateId":"$aggregateId","operation":"$operation"}""",
        sourceService = "lending-service",
        correlationId = aggregateId,
        occurredAt = Instant.parse(occurredAt),
        recordedAt = Instant.parse(occurredAt).plusSeconds(1),
        occurredAtSource = OccurredAtSource.EVENT,
        channel = channel,
    )

    private fun delegatedEntry(eventType: String, occurredAt: String, operation: String, channel: String) =
        entry(grantorId, "PARTY", eventType, delegateId, occurredAt, operation, channel).copy(
            onBehalfOf = grantorId,
            delegationId = delegationId,
        )

    /** The write path every assertion below reads back through. */
    private fun seedChain() {
        val submitted = entry(
            applicationId,
            "LOAN_APPLICATION",
            "lending.application.submitted",
            "officer-1",
            "2026-10-01T10:00:00Z",
            "lending.apply",
        )
        val decided = entry(
            applicationId,
            "LOAN_APPLICATION",
            "lending.application.decided",
            "officer-1",
            "2026-10-03T10:00:00Z",
            "lending.decide",
        )
        val disbursed = entry(
            "account-${UUID.randomUUID()}",
            "ACCOUNT",
            "account.funds.disbursed",
            delegateId,
            "2026-10-04T09:00:00Z",
            "payments.domestic",
            "ingress:ui",
        )
        val payment = delegatedEntry(
            "payment.domestic.executed",
            "2026-10-05T08:00:00Z",
            "payments.domestic",
            "ingress:mcp",
        )
        val cardViewed = delegatedEntry("card.details.viewed", "2026-10-05T09:00:00Z", "cards.view", "ingress:ui")
        seeds = listOf(submitted, decided, disbursed, payment, cardViewed)
        seeds.forEach { e -> onEventLoop { repository.save(e) } }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "auditor-1", roles = ["ROLE_AUDITOR"])
    fun `1 - evidence files into the chain and the aggregate trail reads back newest first`() {
        seedChain()

        given().get("/api/v1/audit/entries/$applicationId").then()
            .statusCode(200)
            .body("", hasSize<Any>(2))
            .body("[0].eventType", equalTo("lending.application.decided"))
            .body("[1].eventType", equalTo("lending.application.submitted"))
            .body("[0].payload", containsString("lending.decide"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "auditor-1", roles = ["ROLE_AUDITOR"])
    fun `2 - the evidence bundle reconstructs the application oldest first with every hash verified`() {
        given().get("/api/v1/audit/evidence/$applicationId").then()
            .statusCode(200)
            .body("attestation", equalTo("audit-chain"))
            .body("entryCount", equalTo(2))
            .body("truncated", equalTo(false))
            .body("tampered", equalTo(false))
            .body("hashStatusCounts.VERIFIED", equalTo(2))
            .body("entries[0].eventType", equalTo("lending.application.submitted"))
            .body("entries[1].eventType", equalTo("lending.application.decided"))
            .body("fullChainVerification", equalTo("/api/v1/audit/integrity"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "auditor-1", roles = ["ROLE_AUDITOR"])
    fun `3 - the actor trail answers what one person did across aggregates and channels`() {
        given().get("/api/v1/audit/entries/by-actor/$delegateId").then()
            .statusCode(200)
            .body("", hasSize<Any>(3))
            .body("[0].eventType", equalTo("card.details.viewed"))
            .body("[1].eventType", equalTo("payment.domestic.executed"))
            .body("[2].eventType", equalTo("account.funds.disbursed"))

        given().queryParam("channel", "ingress:mcp").get("/api/v1/audit/entries/by-actor/$delegateId").then()
            .statusCode(200)
            .body("", hasSize<Any>(1))
            .body("[0].eventType", equalTo("payment.domestic.executed"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "auditor-1", roles = ["ROLE_AUDITOR"])
    fun `4 - the integrity walk confirms the whole chain is intact`() {
        given().get("/api/v1/audit/integrity").then()
            .statusCode(200)
            .body("chainStatus", equalTo("INTACT"))
            .body("checkedCount", equalTo(seeds.size))
            .body("unchainedCount", equalTo(0))
            .body("firstBrokenAt", nullValue())
    }

    @Test
    @Order(5)
    @TestSecurity(user = "auditor-1", roles = ["ROLE_AUDITOR"])
    fun `5 - a tail-anchored walk verifies from a known-good entry and rejects a bad anchor`() {
        given().queryParam("fromEventId", seeds[1].id.toString()).get("/api/v1/audit/integrity").then()
            .statusCode(200)
            .body("chainStatus", equalTo("INTACT"))
            .body("checkedCount", equalTo(seeds.size - 1))

        given().queryParam("fromEventId", "not-a-uuid").get("/api/v1/audit/integrity").then()
            .statusCode(400)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "edge-proxied-customer", roles = ["ROLE_OPERATOR"])
    fun `6 - the grantor's own access log shows metadata only, never the payload`() {
        given().get("/api/v1/audit/customer/$grantorId").then()
            .statusCode(200)
            .body("", hasSize<Any>(2))
            .body("[0].eventType", equalTo("card.details.viewed"))
            .body("[0].actorType", equalTo("HUMAN"))
            .body("[0].payload", nullValue())
            .body("[0].correlationId", nullValue())
    }

    @Test
    @Order(7)
    @TestSecurity(user = "edge-proxied-grantor", roles = ["ROLE_OPERATOR"])
    fun `7 - the on-behalf-of view exposes the delegate's actions under the grant, filterable`() {
        given().get("/api/v1/audit/on-behalf-of/$grantorId").then()
            .statusCode(200)
            .body("", hasSize<Any>(2))
            .body("[0].delegatePartyId", equalTo(delegateId))
            .body("[0].delegationId", equalTo(delegationId))
            .body("[0].operation", equalTo("cards.view"))
            .body("[1].operation", equalTo("payments.domestic"))

        given().queryParam("delegatePartyId", delegateId).get("/api/v1/audit/on-behalf-of/$grantorId").then()
            .statusCode(200)
            .body("", hasSize<Any>(2))

        given().queryParam("delegatePartyId", "officer-1").get("/api/v1/audit/on-behalf-of/$grantorId").then()
            .statusCode(200)
            .body("", hasSize<Any>(0))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "op-1", roles = ["ROLE_OPERATOR"])
    fun `8 - an operator without the audit role is refused the trail`() {
        given().get("/api/v1/audit/entries/$applicationId").then().statusCode(403)
    }
}
