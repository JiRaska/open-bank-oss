// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A null element inside the `roles` array must be rejected with 400, not answered with 500.
 *
 * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of a
 * collection, so `{"roles":[null]}` deserialises into a `Set<TppRole>` holding a null and the first
 * dereference downstream fails. Driven over real HTTP on purpose: a unit test constructing
 * `RegisterTppCommand` in Kotlin cannot build that state.
 */
@QuarkusTest
@QuarkusTestResource(TppRegistryNullRoleElementIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.tppregistry.it.PostgresRedisTestResource::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR"])
class TppRegistryNullRoleElementIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("tpp-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `a null element in roles is rejected with 400`() {
        Given {
            contentType("application/json")
            body(
                """{
                    "tppId":"CZ-CNB-${UUID.randomUUID()}",
                    "name":"Null Role Probe",
                    "countryCode":"CZ",
                    "nca":"CNB",
                    "roles":["AISP",null],
                    "qwacSubjectDn":null,
                    "qsealSubjectDn":null
                }""",
            )
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(400)
        }
    }
}
