// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 License.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.UUID

/**
 * The anonymous onboarding surface (ADR-0069) over the real HTTP wire: caller → edge →
 * party-service / document-service stood in by [OnboardingE2EStubs] over real loopback HTTP (the
 * M2M client_credentials token fetch crosses the same wire), with the funnel telemetry proven on
 * the real Redpanda broker the module's global [com.openbank.customeredge.it.RedpandaRedisTestResource]
 * boots — the envelope is READ BACK from `openbank.onboarding.funnel.events`, and the Prometheus
 * counter is asserted through the injected [MeterRegistry] so a refused event is provably never
 * emitted. The upstream request journal is the durable evidence: caller body verbatim, M2M bearer
 * attached, and the exact fan-out (template list once per TTL, render once per version, content
 * on every read).
 *
 * Journeys are independent: every journey re-stubs its upstreams and starts from a cleared
 * journal. The only cross-journey state is the resource's own terms caches, and the assertions
 * are written to hold in either execution order.
 */
@QuarkusTest
@QuarkusTestResource(OnboardingE2EStubs::class, restrictToAnnotatedClass = true)
class OnboardingE2E {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun resetStubs() = OnboardingE2EStubs.reset()

    @Test
    fun `anonymous start mints a pending party over the real wire`() {
        OnboardingE2EStubs.stub(
            "POST",
            "/api/v1/parties",
            201,
            """{"id":"party-e2e-1","status":"PENDING_ACTIVATION"}""",
        )

        val body =
            post(
                "/customer/v1/onboarding/start",
                """{"partyType":"INDIVIDUAL","legalName":"E2E Tester","taxId":"123"}""",
                201,
            )

        assertThat(body.get("partyId").asText()).isEqualTo("party-e2e-1")
        assertThat(body.get("status").asText()).isEqualTo("PENDING_ACTIVATION")
        val ticket = body.get("enrollmentTicket").asText()
        assertThat(ticket).isNotBlank()
        // ADR-0066 F2 ticket shape: base64url(partyId).expiresAt.hmac — the HMAC proves the
        // ticket is integrity-protected, and decoding the first segment ties it to the party.
        val segments = ticket.split(".")
        assertThat(segments).hasSize(3)
        assertThat(String(Base64.getUrlDecoder().decode(segments[0]), Charsets.UTF_8)).isEqualTo("party-e2e-1")

        // The wire evidence: exactly one party mint, the caller's body verbatim, M2M bearer attached.
        val calls = OnboardingE2EStubs.requests("POST", "/api/v1/parties")
        assertThat(calls).hasSize(1)
        assertThat(calls[0].body).contains("E2E Tester")
        assertThat(calls[0].header("authorization")).startsWith("Bearer ")
    }

    @Test
    fun `start validation refuses before any upstream call`() {
        OnboardingE2EStubs.stub("POST", "/api/v1/parties", 201, """{"id":"never-used"}""")

        postText("/customer/v1/onboarding/start", "{not json", 400)
        postText("/customer/v1/onboarding/start", """{"partyType":"INDIVIDUAL"}""", 400)
        postText("/customer/v1/onboarding/start", """{"partyType":"ROBOT","legalName":"E2E"}""", 400)
        postText(
            "/customer/v1/onboarding/start",
            """{"partyType":"INDIVIDUAL","legalName":"${"x".repeat(501)}"}""",
            400,
        )
        postText(
            "/customer/v1/onboarding/start",
            """{"partyType":"INDIVIDUAL","legalName":"${"x".repeat(5000)}"}""",
            413,
        )

        // Every refusal happened at the edge: party-service saw nothing.
        assertThat(OnboardingE2EStubs.requests("POST", "/api/v1/parties")).isEmpty()
    }

    @Test
    fun `start is honest about upstream failures`() {
        OnboardingE2EStubs.stub("POST", "/api/v1/parties", 409, """{"error":"duplicate tax id"}""")
        val conflict =
            postText("/customer/v1/onboarding/start", """{"partyType":"INDIVIDUAL","legalName":"E2E"}""", 409)
        assertThat(conflict).contains("duplicate tax id")

        // party-service answering 201 WITHOUT an id is a broken contract — the edge must not
        // invent a party: 502, and the caller learns the platform is broken, not that it worked.
        OnboardingE2EStubs.stub("POST", "/api/v1/parties", 201, """{"nope":"no id here"}""")
        val broken = postText("/customer/v1/onboarding/start", """{"partyType":"INDIVIDUAL","legalName":"E2E"}""", 502)
        assertThat(broken).contains("did not return an id")
    }

    @Test
    fun `funnel telemetry lands on the real broker`() {
        val sessionId = UUID.randomUUID().toString()
        val counter = meterRegistry.counter("onboarding_funnel_events", "step", "AGREEMENT", "action", "HOLD_ABANDONED")
        val before = counter.count()

        post(
            "/customer/v1/onboarding/events",
            """{"sessionId":"$sessionId","step":"AGREEMENT","action":"HOLD_ABANDONED","kycMethod":"BANKID","platform":"ios","appVersion":"9.9.9"}""",
            202,
        )

        // Durable evidence 1: the envelope is read back from the real broker.
        val envelope = awaitFunnelEvent(sessionId)
        assertThat(envelope.get("eventType").asText()).isEqualTo("onboarding.funnel.step")
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(sessionId)
        assertThat(envelope.get("sourceService").asText()).isEqualTo("customer-edge")
        val payload = envelope.get("payload")
        assertThat(payload.get("step").asText()).isEqualTo("AGREEMENT")
        assertThat(payload.get("action").asText()).isEqualTo("HOLD_ABANDONED")
        assertThat(payload.get("kycMethod").asText()).isEqualTo("BANKID")
        assertThat(payload.get("appVersion").asText()).isEqualTo("9.9.9")

        // Durable evidence 2: the Prometheus counter the alerting rules reason about.
        assertThat(counter.count()).isEqualTo(before + 1)
    }

    @Test
    fun `funnel validation refuses junk without emission`() {
        val counter = meterRegistry.counter("onboarding_funnel_events", "step", "WELCOME", "action", "STEP_VIEWED")
        val before = counter.count()
        val sessionId = UUID.randomUUID().toString()

        postText(
            "/customer/v1/onboarding/events",
            """{"sessionId":"not-a-uuid","step":"WELCOME","action":"STEP_VIEWED"}""",
            400,
        )
        postText(
            "/customer/v1/onboarding/events",
            """{"sessionId":"$sessionId","step":"BOGUS","action":"STEP_VIEWED"}""",
            400,
        )
        postText(
            "/customer/v1/onboarding/events",
            """{"sessionId":"$sessionId","step":"WELCOME","action":"STEP_VIEWED","reason":"${"x".repeat(1100)}"}""",
            413,
        )

        // Refusals never reached the emitter: the counter is untouched and the broker holds no
        // envelope for the session.
        assertThat(counter.count()).isEqualTo(before)
        assertThat(awaitFunnelEventQuietly(sessionId)).isNull()
    }

    @Test
    fun `terms list composes published VOP and caches for the TTL`() {
        stubTemplates()

        val first = get("/customer/v1/onboarding/terms", 200)
        val codes = first.get("documents").map { it.get("code").asText() }
        assertThat(codes).containsExactly("VOP_CS")
        assertThat(first.get("documents")[0].get("version").asText()).isEqualTo("1.2")

        // The second read within the TTL is served from the cache: same body, and the journal
        // shows the template list crossed the wire exactly once for both calls.
        val second = get("/customer/v1/onboarding/terms", 200)
        assertThat(second).isEqualTo(first)
        assertThat(OnboardingE2EStubs.requests("GET", "/api/v1/documents/templates")).hasSize(1)

        // lang=en wants VOP_EN, which the stub keeps DRAFT — published-only is the contract.
        val english = get("/customer/v1/onboarding/terms?lang=en", 200)
        assertThat(english.get("documents")).isEmpty()
    }

    @Test
    fun `terms content renders once per version and serves the bytes`() {
        stubTemplates()
        OnboardingE2EStubs.stub("POST", "/api/v1/documents/render", 201, """{"id":"doc-e2e-1"}""")
        OnboardingE2EStubs.stub(
            "GET",
            "/api/v1/documents/doc-e2e-1/content",
            200,
            "",
            "%PDF-e2e-terms".toByteArray(Charsets.UTF_8),
        )

        val first = getBytes("/customer/v1/onboarding/terms/VOP_CS/content", 200)
        assertThat(String(first, Charsets.UTF_8)).isEqualTo("%PDF-e2e-terms")
        val second = getBytes("/customer/v1/onboarding/terms/VOP_CS/content", 200)
        assertThat(second).isEqualTo(first)

        // Render once per immutable (code, version); the content bytes re-read every time.
        assertThat(OnboardingE2EStubs.requests("POST", "/api/v1/documents/render")).hasSize(1)
        assertThat(OnboardingE2EStubs.requests("GET", "/api/v1/documents/doc-e2e-1/content")).hasSize(2)

        // VOP_EN exists only as a DRAFT — no published version, 404, and no render attempted.
        getText("/customer/v1/onboarding/terms/VOP_EN/content", 404)
        // The framework agreement is never servable through the anonymous route, guessing works.
        getText("/customer/v1/onboarding/terms/RAM_CI/content", 404)
        assertThat(OnboardingE2EStubs.requests("POST", "/api/v1/documents/render")).hasSize(1)
    }

    @Test
    fun `register stays behind the customer role`() {
        // No @TestSecurity: the anonymous caller must hit the OIDC wall, never the register flow.
        given().contentType("application/json")
            .body("""{"partyId":"party-e2e-1"}""")
            .`when`().post("/customer/v1/onboarding/register")
            .then().statusCode(401)

        assertThat(OnboardingE2EStubs.requests("POST", "/api/v1/parties")).isEmpty()
    }

    private fun stubTemplates() = OnboardingE2EStubs.stub(
        "GET",
        "/api/v1/documents/templates",
        200,
        """[
            {"code":"VOP_CS","name":"Obchodní podmínky","version":"1.2","status":"PUBLISHED","createdAt":"2026-01-01T00:00:00Z"},
            {"code":"VOP_EN","name":"General Terms","version":"3.0","status":"DRAFT","createdAt":"2026-02-01T00:00:00Z"},
            {"code":"RAM_CI","name":"Rámcová smlouva","version":"7.1","status":"PUBLISHED","createdAt":"2026-03-01T00:00:00Z"}
        ]""",
    )

    private fun awaitFunnelEvent(sessionId: String): JsonNode = awaitFunnelEventQuietly(sessionId)
        ?: throw AssertionError("no funnel envelope for session $sessionId on the broker")

    private fun awaitFunnelEventQuietly(sessionId: String): JsonNode? {
        val props = Properties()
        props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] =
            com.openbank.customeredge.it.RedpandaRedisTestResource.lastBootstrapServers
        props[ConsumerConfig.GROUP_ID_CONFIG] = "e2e-funnel-${UUID.randomUUID()}"
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf("openbank.onboarding.funnel.events"))
            return pollFor(consumer, sessionId)
        }
    }

    private fun pollFor(consumer: KafkaConsumer<String, String>, sessionId: String): JsonNode? {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            val hit = consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == sessionId }
            if (hit != null) {
                return objectMapper.readTree(hit.value())
            }
        }
        return null
    }

    private fun post(path: String, body: String, expectedStatus: Int): JsonNode =
        objectMapper.readTree(postText(path, body, expectedStatus))

    private fun postText(path: String, body: String, expectedStatus: Int): String =
        given().contentType("application/json").body(body)
            .`when`().post(path)
            .then().statusCode(expectedStatus)
            .extract().response().asString()

    private fun get(path: String, expectedStatus: Int): JsonNode {
        val response: Response = given().`when`().get(path)
            .then().statusCode(expectedStatus)
            .extract().response()
        return objectMapper.readTree(response.asString())
    }

    private fun getText(path: String, expectedStatus: Int): String = given().`when`().get(path)
        .then().statusCode(expectedStatus)
        .extract().response().asString()

    private fun getBytes(path: String, expectedStatus: Int): ByteArray = given().`when`().get(path)
        .then().statusCode(expectedStatus)
        .extract().response().asByteArray()
}
