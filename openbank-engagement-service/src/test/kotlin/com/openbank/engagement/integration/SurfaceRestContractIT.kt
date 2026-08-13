// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The HTTP surface of engagement-service, driven as a real request — same reasoning as
 * `CampaignRestContractIT` (#3133): `ResolveSurfaceUseCase`/`RecordEngagementEventUseCase` call
 * Hibernate Reactive Panache repositories, which only get a real Vert.x context from an actual
 * HTTP request, not from a bare `@QuarkusTest` thread calling the use case directly
 * (`No current Vertx context found`, the HR000068 class of bug this repo has hit before).
 *
 * Consent is stubbed at the port (`StubConsentCheckPort`), not over the wire — consent-service
 * does not run in this IT's stack, and stubbing the port proves the path this IT exists to
 * prove without also needing a reachable OIDC token server.
 */
// SurfaceResource carries @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN") since #4054.
// Without a token every request is 401 and the four assertions below never reach the code they
// exist to exercise — same shape, and same fix, as CampaignRestContractIT, which this IT's KDoc
// already names as its model.
@QuarkusTest
@TestSecurity(user = "edge@openbank.test", roles = ["ROLE_OPERATOR"])
@QuarkusTestResource(SurfaceRestContractIT.NoKafkaResource::class)
@QuarkusTestResource(EngagementPostgresTestResource::class)
class SurfaceRestContractIT {

    class NoKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("engagement-outbox-out") +
                // lending-events-in / party-events-in (LendingArrearsEventConsumer,
                // PartyErasureConsumer) — without this, @QuarkusTest boot tries a real Kafka
                // consumer connection with no broker in this IT's stack.
                InMemoryConnector.switchIncomingChannelsToInMemory(
                    "lending-events-in",
                    "party-events-in",
                    "fraud-hold-events-in",
                )

        override fun stop() = InMemoryConnector.clear()
    }

    private fun getBanner(partyId: UUID) = Given {
        queryParam("partyId", partyId.toString())
    } When {
        get("/api/v1/surfaces/HOME_BANNER")
    } Then {
        statusCode(200)
    } Extract {
        body().jsonPath()
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an eligible party sees the catalogued banner`() {
        StubConsentCheckPort.granted.set(true)
        val body = getBanner(UUID.randomUUID())
        assertThat(body.getString("state")).isEqualTo("ok")
        assertThat(body.getList<Map<String, Any>>("content")).extracting("id")
            .containsExactly("SAVINGS_RATE_BANNER")
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a party without marketing consent is not_eligible, not an empty list`() {
        StubConsentCheckPort.granted.set(false)
        val body = getBanner(UUID.randomUUID())
        assertThat(body.getString("state")).isEqualTo("not_eligible")
        StubConsentCheckPort.granted.set(true)
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `three posted dismissals suppress the next resolve for that party and slot`() {
        StubConsentCheckPort.granted.set(true)
        val party = UUID.randomUUID()
        repeat(3) {
            Given {
                contentType("application/json")
                body(
                    """{"partyId":"$party","contentId":"SAVINGS_RATE_BANNER","slot":"HOME_BANNER","type":"DISMISS"}""",
                )
            } When {
                post("/api/v1/surfaces/events")
            } Then {
                statusCode(202)
            }
        }

        val body = getBanner(party)
        assertThat(body.getString("state")).isEqualTo("suppressed")
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an unknown slot is a 400, not a 500 or a silent empty result`() {
        Given {
            queryParam("partyId", UUID.randomUUID().toString())
        } When {
            get("/api/v1/surfaces/NOT_A_REAL_SLOT")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an event for content not in the rendered catalogue is rejected rather than polluting metrics`() {
        Given {
            contentType("application/json")
            body(
                """{"partyId":"${UUID.randomUUID()}","contentId":"NOT_A_CATALOGUE_ITEM","slot":"HOME_BANNER","type":"CLICK"}""",
            )
        } When {
            post("/api/v1/surfaces/events")
        } Then {
            statusCode(400)
        }
    }

    // ── AdverseStateResource (issue #4265 item 1) ──────────────────────────────────────────────
    //
    // These live in THIS class rather than a second @QuarkusTest deliberately. A new IT class would
    // need its own `switchIncomingChannelsToInMemory(...)` list, and that list is exactly the thing
    // that goes stale: #4297 is adding a fourth consumer (`dispute-events-in`) right now, and a
    // second copy would silently start reaching for a real broker the day it lands. One boot, one
    // channel list, one place to update.

    /**
     * The route is SERVED, not merely compiled. A Kotlin annotation binds to the NEXT declaration,
     * so a `@Path` that slid onto something other than its class leaves the resource unregistered
     * and every call 404s while a unit test calling the class directly stays green — the exact
     * failure `McpEndpointRoutingIT` exists for. Only a real HTTP request can tell 200 from 404
     * here, which is why this assertion is a status code and not a returned object.
     */
    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a party with no adverse state gets a served 200 and an empty list, not a 404`() {
        val body = adverseStates(UUID.randomUUID())
        assertThat(body.getList<String>("adverseStates")).isEmpty()
    }

    /**
     * The read reaches the real table. The row is written with plain JDBC because a Hibernate
     * Reactive Panache repository cannot be driven from a bare `@QuarkusTest` thread (`No current
     * Vertx context found`); only the HTTP request carries a Vert.x context, so the write goes
     * around the repository and the read goes through it — which is what makes this a test of the
     * endpoint rather than of a mock.
     */
    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `states materialised in party_adverse_state are visible over HTTP, sorted`() {
        val party = UUID.randomUUID()
        insertAdverseState(party, "FRAUD_HOLD")
        insertAdverseState(party, "ARREARS")

        // Sorted by name, not insertion order: the repository hands back a Set, whose iteration
        // order is an implementation detail no response body may depend on.
        assertThat(adverseStates(party).getList<String>("adverseStates"))
            .containsExactly("ARREARS", "FRAUD_HOLD")
    }

    /**
     * One party's flags never leak into another's. Cheap to assert, and the only thing standing
     * between a per-party read and a fleet-wide one is a single `where` clause.
     */
    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a second party does not see the first party's adverse state`() {
        val flagged = UUID.randomUUID()
        insertAdverseState(flagged, "ERASURE_REQUESTED")

        assertThat(adverseStates(UUID.randomUUID()).getList<String>("adverseStates")).isEmpty()
    }

    /**
     * An absent `partyId` is a 400, not a 500. This is the assertion the repo's nonnull-JAX-RS rule
     * exists for: JAX-RS injects null for an absent query parameter, so a non-nullable `UUID` here
     * would answer 500 — and in a `suspend fun`, which emits no `checkNotNullParameter` intrinsic,
     * the null would flow into the body instead and the `requireNotNull` guard would be the only
     * thing catching it. Declared nullable + `requireNotNull` → libs-runtime's
     * `IllegalArgumentExceptionMapper` → 400.
     */
    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an absent partyId is a 400, not a 500`() {
        When {
            get("/api/v1/eligibility/adverse-states")
        } Then {
            statusCode(400)
        }
    }

    private fun adverseStates(partyId: UUID) = Given {
        queryParam("partyId", partyId.toString())
    } When {
        get("/api/v1/eligibility/adverse-states")
    } Then {
        statusCode(200)
    } Extract {
        body().jsonPath()
    }

    /** The JDBC URL is the one EngagementPostgresTestResource injected — never a second copy. */
    private fun insertAdverseState(partyId: UUID, state: String) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(
                "INSERT INTO party_adverse_state (id, party_id, state, set_at) VALUES (?, ?, ?, ?)",
            ).use { st ->
                st.setObject(1, UUID.randomUUID())
                st.setObject(2, partyId)
                st.setString(3, state)
                st.setTimestamp(4, Timestamp.from(Instant.now()))
                st.executeUpdate()
            }
        }
    }

    private companion object {
        /** Any stable principal id: the endpoints gate on the ROLE, not on this value. */
        const val TEST_OPERATOR = "00000000-0000-0000-0000-000000000099"
    }
}
