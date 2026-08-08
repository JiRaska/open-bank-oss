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
import org.junit.jupiter.api.Test
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
@QuarkusTest
@TestSecurity(user = "edge@openbank.test", roles = ["ROLE_OPERATOR"])
@QuarkusTestResource(SurfaceRestContractIT.NoKafkaResource::class)
@QuarkusTestResource(EngagementPostgresTestResource::class)
// SurfaceResource carries @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN") since #4054.
// Without a token every request is 401 and the four assertions below never reach the code they
// exist to exercise — same shape, and same fix, as CampaignRestContractIT, which this IT's KDoc
// already names as its model.
@TestSecurity(user = "surface-reader@openbank.test", roles = ["ROLE_OPERATOR"])
class SurfaceRestContractIT {

    class NoKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("engagement-outbox-out")

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
    fun `an eligible party sees the catalogued banner`() {
        StubConsentCheckPort.granted.set(true)
        val body = getBanner(UUID.randomUUID())
        assertThat(body.getString("state")).isEqualTo("ok")
        assertThat(body.getList<Map<String, Any>>("content")).extracting("id")
            .containsExactly("SAVINGS_RATE_BANNER")
    }

    @Test
    fun `a party without marketing consent is not_eligible, not an empty list`() {
        StubConsentCheckPort.granted.set(false)
        val body = getBanner(UUID.randomUUID())
        assertThat(body.getString("state")).isEqualTo("not_eligible")
        StubConsentCheckPort.granted.set(true)
    }

    @Test
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
    fun `an unknown slot is a 400, not a 500 or a silent empty result`() {
        Given {
            queryParam("partyId", UUID.randomUUID().toString())
        } When {
            get("/api/v1/surfaces/NOT_A_REAL_SLOT")
        } Then {
            statusCode(400)
        }
    }
}
