// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * E2E suite for the TPP registry lifecycle over real infrastructure (Postgres + Redpanda +
 * Valkey via [TppRegistryE2EProfile]). The gap it closes: every pre-existing test either swaps
 * `tpp-events-out` to the in-memory connector or switches the outbox dispatcher off, so
 * TPP_REGISTERED / TPP_BLACKLISTED had never reached a real broker, and the Redis-backed
 * idempotency reservations on register/blacklist/sync were never observed on the full HTTP path.
 * Here the registry row, the outbox row and the Kafka record are asserted as three independent
 * facts that must agree.
 */
@QuarkusTest
@TestProfile(TppRegistryE2EProfile::class)
class TppRegistryE2E : TppRegistryE2EBase() {

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `registering a TPP commits the row the outbox row and the event on the real broker`() {
        val tppId = "E2E-REG-001"
        val created = register(tppId, listOf("AISP", "PISP"), key = "reg-001")
        assertThat(created.statusCode).isEqualTo(201)
        val body = json(created)
        val entryUuid = body["id"].asText()
        assertThat(body["tppId"].asText()).isEqualTo(tppId)
        assertThat(body["status"].asText()).isEqualTo("ACTIVE")

        val row = entryRow(tppId)
        assertThat(row).isNotNull()
        assertThat(row!!.entryUuid).isEqualTo(entryUuid)
        assertThat(row.status).isEqualTo("ACTIVE")
        assertThat(row.roles).contains("AISP").contains("PISP")

        val envelope = awaitEvent(entryUuid, "TPP_REGISTERED")
        assertThat(envelope["eventType"].asText()).isEqualTo("TPP_REGISTERED")
        assertThat(envelope["entryId"].asText()).isEqualTo(entryUuid)
        assertThat(envelope["tppId"].asText()).isEqualTo(tppId)
        assertThat(envelope["status"].asText()).isEqualTo("ACTIVE")
        assertThat(envelope["roles"].map { it.asText() }).containsExactly("AISP", "PISP")

        // After the record reached the broker the row must be terminal SENT — asserted here, not
        // before the await, so the dispatcher's 1 s cadence cannot race the observation.
        assertThat(outboxRows(entryUuid)).containsExactly(OutboxRow("TPP_REGISTERED", "SENT", true))
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `idempotent replay returns the stored response and never emits a second event`() {
        val tppId = "E2E-REG-002"
        val key = "reg-002"
        val first = register(tppId, listOf("AISP"), key = key)
        assertThat(first.statusCode).isEqualTo(201)
        val entryUuid = json(first)["id"].asText()
        awaitEvent(entryUuid, "TPP_REGISTERED")

        val replay = register(tppId, listOf("AISP"), key = key)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(json(replay)).isEqualTo(json(first))

        assertThat(outboxRows(entryUuid)).hasSize(1)
        assertThat(countEvents(entryUuid)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `the same key with a different body is refused as IDEMPOTENCY_KEY_REUSED`() {
        // The idempotency cache key is namespaced per tppId (tpp:register:<tppId>:<key>), so the
        // collision must reuse the SAME tppId with a different payload to hit Mismatch.
        val tppId = "E2E-REG-003"
        val key = "reg-003"
        val first = register(tppId, listOf("AISP"), key = key)
        assertThat(first.statusCode).isEqualTo(201)

        val conflict = register(tppId, listOf("PISP"), key = key)
        assertThat(conflict.statusCode).isEqualTo(409)
        assertThat(json(conflict)["code"].asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        // The refused replay must not have overwritten the stored entry.
        assertThat(entryRow(tppId)!!.roles).contains("AISP").doesNotContain("PISP")
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `role order does not change the request fingerprint`() {
        val key = "reg-004"
        val first = register("E2E-REG-004", listOf("AISP", "PISP"), key = key)
        assertThat(first.statusCode).isEqualTo(201)

        val reordered = register("E2E-REG-004", listOf("PISP", "AISP"), key = key)
        assertThat(reordered.statusCode).isEqualTo(201)
        assertThat(reordered.header("X-Idempotency-Replayed")).isEqualTo("true")
    }

    @Test
    // OPERATOR (not just API): this test registers its fixture through the same write endpoint.
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `checkAuthorization authorizes an active TPP and fails closed with a reason`() {
        register("E2E-CHK-001", listOf("AISP", "PISP"))

        val allowed = check("E2E-CHK-001", "AISP")
        assertThat(allowed.statusCode).isEqualTo(200)
        assertThat(json(allowed)["authorized"].asBoolean()).isTrue()

        val missingRole = check("E2E-CHK-001", "ASPSP")
        assertThat(missingRole.statusCode).isEqualTo(403)
        assertThat(json(missingRole)["reason"].asText()).contains("does not have role")

        val unknown = check("E2E-UNKNOWN", "AISP")
        assertThat(unknown.statusCode).isEqualTo(403)
        assertThat(json(unknown)["reason"].asText()).contains("not found")

        val missingParam = Given { this } When { get("/api/v1/tpp-registry/check?tppId=E2E-CHK-001") }
        assertThat(missingParam.statusCode).isEqualTo(400)
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `blacklisting broadcasts TPP_BLACKLISTED and flips every read lens`() {
        val tppId = "E2E-BLK-001"
        val key = "blk-001"
        val created = register(tppId, listOf("PISP"), key = "reg-blk-001")
        val entryUuid = json(created)["id"].asText()

        val blacklisted = blacklist(tppId, "fraud investigation", key)
        assertThat(blacklisted.statusCode).isEqualTo(200)
        assertThat(json(blacklisted)["status"].asText()).isEqualTo("BLACKLISTED")

        val row = entryRow(tppId)
        assertThat(row!!.status).isEqualTo("BLACKLISTED")
        assertThat(row.blacklistReason).isEqualTo("fraud investigation")
        assertThat(row.blacklistedAtPresent).isTrue()

        val envelope = awaitEvent(entryUuid, "TPP_BLACKLISTED")
        assertThat(envelope["blacklistReason"].asText()).isEqualTo("fraud investigation")
        assertThat(envelope["status"].asText()).isEqualTo("BLACKLISTED")

        val replay = blacklist(tppId, "fraud investigation", key)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")

        val unknown = blacklist("E2E-BLK-MISSING", "reason", "blk-missing")
        assertThat(unknown.statusCode).isEqualTo(404)
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `list filters getTpp 404s and a duplicate register conflicts`() {
        register("E2E-LST-CZ1", listOf("AISP"), country = "CZ")
        register("E2E-LST-DE1", listOf("PISP"), country = "DE")
        register("E2E-LST-CZ2", listOf("PISP"), country = "CZ")

        // The suite DB ships seeded CZ entries, so positive filter assertions are scoped to the
        // fixtures this test created (contains + explicit negatives), not containsExactly.
        assertThat(tppIds(listOf("countryCode" to "DE"))).containsExactly("E2E-LST-DE1")
        assertThat(tppIds(listOf("role" to "AISP")))
            .contains("E2E-LST-CZ1")
            .doesNotContain("E2E-LST-DE1", "E2E-LST-CZ2")
        assertThat(tppIds(listOf("limit" to "1"))).hasSize(1)

        blacklist("E2E-LST-CZ2", "compliance", "blk-lst-001")
        assertThat(tppIds(listOf("status" to "BLACKLISTED"))).contains("E2E-LST-CZ2")
        assertThat(tppIds(listOf("status" to "ACTIVE"))).doesNotContain("E2E-LST-CZ2")

        val missing = Given { this } When { get("/api/v1/tpp-registry/E2E-LST-MISSING") }
        assertThat(missing.statusCode).isEqualTo(404)
        assertThat(json(missing)["error"].asText()).isEqualTo("NOT_FOUND")

        val duplicate = register("E2E-LST-CZ1", listOf("AISP"))
        assertThat(duplicate.statusCode).isEqualTo(409)
        assertThat(json(duplicate)["error"].asText()).isEqualTo("CONFLICT")
    }

    @Test
    @TestSecurity(user = "e2e-m2m", roles = ["ROLE_API"])
    fun `EBA sync records its state over the real stores and replays under the same key`() {
        val key = "sync-001"
        val synced = Given {
            contentType(ContentType.JSON)
            header("Idempotency-Key", key)
        } When { post("/api/v1/tpp-registry/sync/eba") }
        assertThat(synced.statusCode).isEqualTo(200)
        val body = json(synced)
        assertThat(body["totalEntries"].asInt()).isEqualTo(0)
        assertThat(body["errorMessage"].asText()).contains("not yet implemented")

        val row = syncStateRow()
        assertThat(row).isNotNull()
        assertThat(row!!.first).contains("not yet implemented")
        assertThat(row.second).isEqualTo(0)
        assertThat(row.third).isTrue()

        val state = Given { this } When { get("/api/v1/tpp-registry/sync/state") }
        assertThat(state.statusCode).isEqualTo(200)
        assertThat(json(state)["errorMessage"].asText()).contains("not yet implemented")

        val replay = Given {
            contentType(ContentType.JSON)
            header("Idempotency-Key", key)
        } When { post("/api/v1/tpp-registry/sync/eba") }
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun register(tppId: String, roles: List<String>, key: String? = null, country: String = "CZ"): Response =
        Given {
            contentType(ContentType.JSON)
            key?.let { header("Idempotency-Key", it) }
            body(
                mapOf(
                    "tppId" to tppId,
                    "name" to "E2E TPP $tppId",
                    "countryCode" to country,
                    "nca" to "CNB",
                    "roles" to roles,
                    "qwacSubjectDn" to "CN=$tppId",
                    "qsealSubjectDn" to null,
                ),
            )
        } When { post("/api/v1/tpp-registry") }

    private fun blacklist(tppId: String, reason: String, key: String?): Response = Given {
        contentType(ContentType.JSON)
        key?.let { header("Idempotency-Key", it) }
        body(mapOf("reason" to reason))
    } When { post("/api/v1/tpp-registry/$tppId/blacklist") }

    private fun check(tppId: String, role: String): Response = Given {
        this
    } When { get("/api/v1/tpp-registry/check?tppId=$tppId&role=$role") }

    private fun tppIds(query: List<Pair<String, String>>): List<String> {
        val qs = query.joinToString("&") { "${it.first}=${it.second}" }
        val response = Given { this } When { get("/api/v1/tpp-registry?$qs") }
        assertThat(response.statusCode).isEqualTo(200)
        return json(response)["tpps"].map { it["tppId"].asText() }
    }

    private fun json(response: Response): JsonNode = mapper.readTree(response.then().extract().body().asString())

    companion object {
        @BeforeAll
        @JvmStatic
        fun ensureTopic() = ensureTopicOnce()
    }
}
