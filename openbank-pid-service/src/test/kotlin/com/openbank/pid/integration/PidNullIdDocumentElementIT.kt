// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.integration

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
 * A null element inside the `idDocuments` array must be rejected with 400, not answered with 500.
 *
 * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of a
 * collection, so `{"idDocuments":[null]}` deserialises into a `List<IdDocumentDto>` holding a null
 * and the first dereference downstream fails. Driven over real HTTP on purpose: a unit test calling
 * the handler with a hand-built DTO cannot construct that state.
 */
@QuarkusTest
@QuarkusTestResource(PidNullIdDocumentElementIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.pid.it.PostgresTestResource::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR"])
class PidNullIdDocumentElementIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out", "pid-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `a null element in idDocuments is rejected with 400`() {
        Given {
            contentType("application/json")
            body(
                """{
                    "bankIdSub":"sub-1",
                    "givenName":"Jan",
                    "familyName":"Novak",
                    "birthdate":"1980-01-01",
                    "idDocuments":[null]
                }""",
            )
        } When {
            post("/api/v1/parties/${UUID.randomUUID()}/sync/bankid")
        } Then {
            statusCode(400)
        }
    }
}
