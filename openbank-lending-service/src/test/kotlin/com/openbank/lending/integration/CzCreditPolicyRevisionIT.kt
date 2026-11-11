// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.libs.util.Hex
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset

@QuarkusTest
@TestProfile(CzCreditPolicyRevisionIT.IsolatedProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CzCreditPolicyRevisionIT {
    class IsolatedProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_lending_policy_it"),
            ),
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = "policy-maker", roles = ["ROLE_CREDIT_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "policy-maker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "policy-maker"),
        ],
    )
    fun `a proposal is immutable and a maker cannot approve it`() {
        given().contentType(ContentType.JSON).body(bundle(903, adverseBureauCheck = false))
            .post(PROPOSALS).then().statusCode(400)
        val id = propose(901)
        assertThat(propose(901)).isEqualTo(id)
        propose(902)
        given().contentType(ContentType.JSON).body("{}").post("$PROPOSALS/$id/decide").then().statusCode(400)
        decide(id, approve = true, expectedStatus = 422)
        assertThat(activeAsOf(EFFECTIVE_DAY).getBoolean("codeSeeded")).isTrue()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "policy-checker", roles = ["ROLE_CREDIT_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "policy-checker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "policy-checker"),
        ],
    )
    fun `the other actor approves the exact version and cannot approve a conflicting effective day`() {
        val pending = given().get(PROPOSALS).then().statusCode(200).header("Cache-Control", "no-store")
            .extract().jsonPath()
        val id = pending.getString("find { it.revision == 901 }.id")
        val originalHash = pending.getString("find { it.revision == 901 }.contentHash")
        val exactJson = pending.getString("find { it.revision == 901 }.policyJson")
        val computedHash = Hex.lower(MessageDigest.getInstance("SHA-256").digest(exactJson.toByteArray(Charsets.UTF_8)))
        assertThat(originalHash).isEqualTo(computedHash)
        val decided = decide(id, approve = true, expectedStatus = 200)
        assertThat(decided.getString("policyJson")).isEqualTo(exactJson)
        assertThat(decided.getString("state")).isEqualTo("EXECUTED")
        assertThat(decided.getString("proposedBy")).isEqualTo("policy-maker-subject")
        assertThat(decided.getString("decidedBy")).isEqualTo("policy-checker-subject")
        assertThat(decided.getString("contentHash")).isEqualTo(originalHash).matches("[a-f0-9]{64}")
        decide(id, approve = false, expectedStatus = 409)
        assertThat(activeAsOf(EFFECTIVE_DAY.minusDays(1)).getBoolean("codeSeeded")).isTrue()
        val active = activeAsOf(EFFECTIVE_DAY)
        assertThat(active.getBoolean("codeSeeded")).isFalse()
        assertThat(active.getList("tables.version", Int::class.javaObjectType)).containsOnly(901)
        assertThat(active.getString("tables.find { it.kind == 'PRICING_BAND' }.rules[0].band")).isEqualTo("TEST_BAND")

        val conflict = pending.getString("find { it.revision == 902 }.id")
        decide(conflict, approve = true, expectedStatus = 409)
        assertThat(decide(conflict, approve = false, expectedStatus = 200).getString("state")).isEqualTo("REJECTED")
        assertThat(given().get(PROPOSALS).then().statusCode(200).extract().jsonPath().getList<Any>("$")).isEmpty()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_CREDIT_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "credit-policy-machine-subject"),
            Claim(key = "azp", value = "openbank-lending"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun `a machine identity with the credit risk role cannot author policy`() {
        given().get(PROPOSALS).then().statusCode(403)
        given().contentType(ContentType.JSON).body(bundle(903)).post(PROPOSALS).then().statusCode(403)
    }

    private fun propose(version: Int): String = given().contentType(ContentType.JSON).body(bundle(version))
        .post(PROPOSALS).then().statusCode(201).extract().path("id")

    private fun decide(id: String, approve: Boolean, expectedStatus: Int) = given().contentType(ContentType.JSON)
        .body(mapOf("approve" to approve, "reason" to "Synthetic policy test"))
        .post("$PROPOSALS/$id/decide").then().statusCode(expectedStatus).extract().jsonPath()

    private fun activeAsOf(day: LocalDate) = given().queryParam("asOf", day.toString()).get(POLICY)
        .then().statusCode(200).extract().jsonPath()

    private fun bundle(version: Int, adverseBureauCheck: Boolean = true): Map<String, Any> {
        val rule = mapOf("attribute" to "AGE_YEARS", "operator" to "GTE", "threshold" to 18)
        val pricing = mapOf("attribute" to "DSTI", "operator" to "LTE", "threshold" to 0.5, "band" to "TEST_BAND")
        val tables = listOf("EXCLUSION", "ELIGIBILITY", "AFFORDABILITY", "PRICING_BAND").map { kind ->
            val rules = when (kind) {
                "EXCLUSION" -> if (adverseBureauCheck) {
                    listOf(
                        mapOf(
                            "id" to "bureau",
                            "attribute" to "CUSTOMER_TYPE",
                            "operator" to "EQ",
                            "values" to listOf("ADVERSE_BUREAU"),
                        ),
                    )
                } else {
                    emptyList()
                }
                "AFFORDABILITY" -> listOf(
                    mapOf("id" to "dsti", "attribute" to "DSTI", "operator" to "LTE", "threshold" to 0.45),
                    mapOf("id" to "dti", "attribute" to "DTI", "operator" to "LTE", "threshold" to 8),
                )
                "PRICING_BAND" -> listOf(pricing + ("id" to "pricing"))
                else -> listOf(rule + ("id" to kind.lowercase()))
            }
            mapOf(
                "kind" to kind,
                "name" to kind.lowercase(),
                "version" to version,
                "effectiveFrom" to EFFECTIVE_DAY.toString(),
                "rules" to rules,
            )
        }
        return mapOf("tables" to tables)
    }

    private companion object {
        const val POLICY = "/api/v1/lending/risk/policy"
        const val PROPOSALS = "$POLICY/proposals"
        val EFFECTIVE_DAY: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(7)
    }
}
