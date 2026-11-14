// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The outcome vocabulary is served over real HTTP, to the compliance role only, and lists exactly
 * the codes the service accepts — the same file `DecisionOutcomes.label` resolves against — so a
 * client never keeps its own copy.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class MonitoringOutcomeVocabularyIT {

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `the compliance role reads the vocabulary the service resolves outcome codes against`() {
        val body = given().get("/api/v1/aml/monitoring/outcomes").then().statusCode(200).extract().asString()
        val served = ObjectMapper().readTree(body)
        val source = ObjectMapper().readTree(
            javaClass.classLoader.getResourceAsStream("aml/decision-outcomes.provisional.json"),
        )
        assertThat(served["vocabularyId"].asText()).isEqualTo(source["id"].asText())
        assertThat(served["vocabularyRevision"].asInt()).isEqualTo(source["revision"].asInt())
        assertThat(served["entries"].map { it["code"].asText() }.sorted())
            .isEqualTo(source["entries"].map { it["code"].asText() }.sorted())
        assertThat(served["entries"].all { it["label"].asText().isNotBlank() }).isTrue()
    }

    @Test
    @TestSecurity(user = "bob", roles = ["ROLE_OPERATOR"])
    fun `a non-compliance role is refused`() {
        given().get("/api/v1/aml/monitoring/outcomes").then().statusCode(403)
    }
}
