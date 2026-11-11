// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.util.Hex
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@QuarkusTest
@TestProfile(CzCreditPolicyRevisionIT.IsolatedProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CzCreditPolicyRevisionIT {
    @Inject
    lateinit var clock: PolicyTestClock

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var mapper: ObjectMapper

    class IsolatedProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun getEnabledAlternatives() = setOf(PolicyClockProducer::class.java)

        override fun getConfigOverrides() = mapOf("quarkus.scheduler.enabled" to "false")

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(
                PostgresRedisTestResource::class.java,
                mapOf("db" to "openbank_lending_policy_it"),
            ),
            QuarkusTestProfile.TestResourceEntry(CreditRiskConsoleIT.InMemoryKafkaResource::class.java),
        )
    }

    @Alternative
    @ApplicationScoped
    class PolicyClockProducer {
        @Produces
        @Singleton
        fun clock(): PolicyTestClock = PolicyTestClock()
    }

    class PolicyTestClock : Clock() {
        private val time = AtomicReference(Instant.parse("2026-10-24T22:30:00Z"))

        override fun instant(): Instant = time.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)

        fun whileAt(instant: Instant, test: () -> Unit) {
            val previous = time.getAndSet(instant)
            try {
                test()
            } finally {
                time.set(previous)
            }
        }
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
        given().contentType(ContentType.JSON).body(bundle(903)).post(PROPOSALS).then().statusCode(400)
        given().contentType(ContentType.JSON).header("Idempotency-Key", "invalid-evidence")
            .body(bundle(903, adverseBureauCheck = false))
            .post(PROPOSALS).then().statusCode(400)
        val defaultDay = given().get(POLICY).then().statusCode(200).extract().path<String>("asOf")
        assertThat(defaultDay).isEqualTo(PRAGUE_DAY.toString())
        proposalRequest(903, day = PRAGUE_DAY).post(PROPOSALS).then().statusCode(400)
        val id = propose(901)
        val replay = proposalRequest(901).post(PROPOSALS).then().statusCode(201)
            .header("X-Idempotency-Replayed", "true").extract().jsonPath()
        assertThat(replay.getString("id")).isEqualTo(id)
        proposalRequest(902, key = "policy-proposal-901").post(PROPOSALS).then().statusCode(409)
        propose(902)
        given().contentType(ContentType.JSON).header("Idempotency-Key", "policy-proposal-901")
            .body(mapOf("approve" to true)).post("$PROPOSALS/$id/decide").then().statusCode(409)
        given().contentType(ContentType.JSON).header("Idempotency-Key", "missing-decision").body("{}")
            .post("$PROPOSALS/$id/decide").then().statusCode(400)
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
        // The maker used this key, but the checker must get a content conflict, never the maker's receipt.
        val duplicate = proposalRequest(901).post(PROPOSALS).then().statusCode(409).extract().jsonPath()
        assertThat(duplicate.getString("code")).isEqualTo("CREDIT_POLICY_DUPLICATE_CONTENT")
        val originalDecision = decisionRequest(id, approve = true).post("$PROPOSALS/$id/decide").then().statusCode(200)
            .extract().response()
        val decided = originalDecision.jsonPath()
        val replay = decisionRequest(id, approve = true).post("$PROPOSALS/$id/decide").then().statusCode(200)
            .header("X-Idempotency-Replayed", "true").extract().asString()
        assertThat(replay).isEqualTo(originalDecision.asString())
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
    @Order(3)
    @TestSecurity(user = "policy-maker", roles = ["ROLE_CREDIT_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "policy-maker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "policy-maker"),
        ],
    )
    fun `proposal replay survives its decision and effective date and rejection permits a new proposal`() {
        val rejected = proposalRequest(902).post(PROPOSALS).then().statusCode(201)
            .header("X-Idempotency-Replayed", "true").extract().jsonPath()
        val reproposed = proposalRequest(902, key = "policy-proposal-902-again").post(PROPOSALS).then()
            .statusCode(201).extract().jsonPath()
        assertThat(reproposed.getString("id")).isNotEqualTo(rejected.getString("id"))
        clock.whileAt(EFFECTIVE_DAY.atStartOfDay(PRAGUE).toInstant()) {
            val original = proposalRequest(901).post(PROPOSALS).then().statusCode(201)
                .header("X-Idempotency-Replayed", "true").extract().jsonPath()
            assertThat(original.getString("state")).isEqualTo("PROPOSED")
            assertThat(original.getString("decidedBy")).isNull()
            assertThat(original.getString("decisionReason")).isNull()
            proposalRequest(901, key = "policy-proposal-901-new").post(PROPOSALS).then().statusCode(400)
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "policy-maker", roles = ["ROLE_CREDIT_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "policy-maker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "policy-maker"),
        ],
    )
    fun `concurrent exact retries replay a response larger than the cache limit`() {
        val payload = bundle(905, detail = "a".repeat(270_000))
        val first = requestAsync { largeProposal(payload) }
        val second = largeProposal(payload)
        val response = first.get(10, TimeUnit.SECONDS)
        assertThat(response.asString()).isEqualTo(second.asString())
        assertThat(listOf(response, second).count { it.getHeader("X-Idempotency-Replayed") == "true" }).isEqualTo(1)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "policy-checker", roles = ["ROLE_CREDIT_RISK"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "policy-checker-subject"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "policy-checker"),
        ],
    )
    fun `a checker delayed by a row lock cannot backdate approval across Prague midnight`() {
        val effectiveDay = EFFECTIVE_DAY.plusDays(1)
        val id = seedProposal(904, effectiveDay)
        setPragueDay(effectiveDay.minusDays(1))
        try {
            val approval = dataSource.connection.use { lock ->
                lock.autoCommit = false
                lock.prepareStatement("SELECT id FROM cz_credit_policy_revision WHERE id = ? FOR UPDATE").use {
                    it.setObject(1, id)
                    it.executeQuery().close()
                }
                val waiting = requestAsync {
                    decisionRequest(id.toString(), approve = true).post("$PROPOSALS/$id/decide")
                }
                waitForApprovalLock()
                setPragueDay(effectiveDay)
                lock.commit()
                waiting
            }
            approval.get(10, TimeUnit.SECONDS).then().statusCode(400)
            assertThat(proposalState(id)).isEqualTo("PROPOSED")
        } finally {
            restorePragueDay()
        }
        // The rejected transaction did not consume the key. The actual database day is before effectiveDay.
        decide(id.toString(), approve = true, expectedStatus = 200)
        assertThat(proposalState(id)).isEqualTo("EXECUTED")
        assertThatThrownBy { sql("UPDATE cz_credit_policy_revision SET payload = '{}' WHERE id = '$id'") }
            .hasMessageContaining("immutable")

        val alreadyEffective = seedProposal(906, LocalDate.parse(scalar("SELECT cz_credit_policy_prague_day()")))
        decisionRequest(alreadyEffective.toString(), approve = true).post("$PROPOSALS/$alreadyEffective/decide")
            .then().statusCode(400)
        assertThat(proposalState(alreadyEffective)).isEqualTo("PROPOSED")
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

    private fun <T> requestAsync(request: () -> T): CompletableFuture<T> {
        val loader = Thread.currentThread().contextClassLoader
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "policy-fixture-request").apply { contextClassLoader = loader }
        }
        return CompletableFuture.supplyAsync(request, executor).whenComplete { _, _ -> executor.shutdown() }
    }

    private fun largeProposal(payload: Map<String, Any>) = given().contentType(ContentType.JSON)
        .header("Idempotency-Key", "large-policy-replay").body(payload).post(PROPOSALS)
        .then().statusCode(201).extract().response()

    private fun seedProposal(version: Int, day: LocalDate): UUID {
        val id = Ids.newId()
        val payload = mapper.writeValueAsString(bundle(version, effectiveDay = day))
        val hash = Hex.lower(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO cz_credit_policy_revision
                    (id, revision, effective_from, payload, content_hash, state, proposed_by, proposed_at)
                VALUES (?, ?, ?, ?, ?, 'PROPOSED', 'synthetic-policy-maker', clock_timestamp())
                """.trimIndent(),
            ).use {
                it.setObject(1, id)
                it.setInt(2, version)
                it.setObject(3, day)
                it.setString(4, payload)
                it.setString(5, hash)
                it.executeUpdate()
            }
        }
        return id
    }

    private fun waitForApprovalLock() {
        val query = """
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database() AND wait_event_type = 'Lock'
                AND query LIKE '%cz_credit_policy_revision%' AND pid <> pg_backend_pid()
        """.trimIndent()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (scalar(query) == "0" && System.nanoTime() < deadline) Thread.sleep(25)
        assertThat(scalar(query).toInt()).isGreaterThan(0)
    }

    private fun setPragueDay(day: LocalDate) = sql(
        "CREATE OR REPLACE FUNCTION cz_credit_policy_prague_day() RETURNS date LANGUAGE sql VOLATILE " +
            "AS $$ SELECT DATE '$day' $$",
    )

    private fun restorePragueDay() = sql(
        "CREATE OR REPLACE FUNCTION cz_credit_policy_prague_day() RETURNS date LANGUAGE sql VOLATILE " +
            "AS $$ SELECT (clock_timestamp() AT TIME ZONE 'Europe/Prague')::date $$",
    )

    private fun proposalState(id: UUID) = scalar("SELECT state FROM cz_credit_policy_revision WHERE id = '$id'")

    private fun sql(query: String) = dataSource.connection.use { connection ->
        connection.createStatement().use { it.execute(query) }
    }

    private fun scalar(query: String) = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun propose(version: Int): String = proposalRequest(version)
        .post(PROPOSALS).then().statusCode(201).extract().path("id")

    private fun proposalRequest(
        version: Int,
        key: String = "policy-proposal-$version",
        day: LocalDate = EFFECTIVE_DAY,
    ) = given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(bundle(version, effectiveDay = day))

    private fun decide(id: String, approve: Boolean, expectedStatus: Int) = decisionRequest(id, approve)
        .post("$PROPOSALS/$id/decide").then().statusCode(expectedStatus).extract().jsonPath()

    private fun decisionRequest(id: String, approve: Boolean) = given().contentType(ContentType.JSON)
        .header("Idempotency-Key", "$id-$approve")
        .body(mapOf("approve" to approve, "reason" to "Synthetic policy test"))

    private fun activeAsOf(day: LocalDate) = given().queryParam("asOf", day.toString()).get(POLICY)
        .then().statusCode(200).extract().jsonPath()

    private fun bundle(
        version: Int,
        adverseBureauCheck: Boolean = true,
        effectiveDay: LocalDate = EFFECTIVE_DAY,
        detail: String = "",
    ): Map<String, Any> {
        val rule = mapOf("attribute" to "AGE_YEARS", "operator" to "GTE", "threshold" to 18, "detail" to detail)
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
                "effectiveFrom" to effectiveDay.toString(),
                "rules" to rules,
            )
        }
        return mapOf("tables" to tables)
    }

    private companion object {
        const val POLICY = "/api/v1/lending/risk/policy"
        const val PROPOSALS = "$POLICY/proposals"
        val PRAGUE_DAY: LocalDate = LocalDate.of(2026, 10, 25)
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
        val EFFECTIVE_DAY: LocalDate = maxOf(PRAGUE_DAY, LocalDate.now(PRAGUE)).plusDays(7)
    }
}
