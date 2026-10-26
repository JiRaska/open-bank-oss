// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Shared profile for the campaign trigger-consumer real-broker E2E suite: real Postgres + real
 * Redpanda (the shared [PostgresRedpandaTestResource]), plus a fake ClickHouse answering the
 * silver-layer segment queries (see [FakeClickHouseResource]).
 *
 * Channels to close: `account-triggers-in` and `card-triggers-in` — the ADR push path where a
 * product event enrols a party into a waiting campaign within seconds. The consumer had only
 * mocked unit coverage, so the real deserializer, the ce-type-header-first type matching, the
 * segment gate on the enrolment path and the ack/nack contract were never on the wire together.
 * Like [CampaignConversionsE2EProfile], campaign's %test block never pins incoming channels to
 * in-memory — every channel already runs smallrye-kafka in tests.
 */
class CampaignTriggersE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_campaign_e2e"),
        ),
        TestResourceEntry(FakeClickHouseResource::class.java),
        TestResourceEntry(CampaignTriggersBrokerResource::class.java),
    )

    /**
     * The same recording [com.openbank.campaign.application.port.out.JourneySignaller] the
     * conversions suite uses: a triggered enrolment starts a journey, and the suite asserts that
     * side-effect — the real signaller would dial the Temporal frontend synchronously and block
     * the consumer's event loop without a server.
     */
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(RecordingJourneySignaller::class.java)
}

/**
 * Membership the fake ClickHouse answers with. The test seeds it per scenario; the HTTP handler
 * reads it on every segment-membership query. Static because the handler runs on the server's
 * dispatcher thread while the test thread mutates it.
 */
object FakeSilverLayer {
    val members: MutableSet<UUID> = CopyOnWriteArraySet()

    fun clear() = members.clear()
}

/**
 * A stand-in for the ADR-0210 analytics silver layer, answering the three query shapes
 * [com.openbank.campaign.infrastructure.segment.SilverSegmentEvaluator] generates:
 *
 * - one-party membership (`matches`) — the query carries `param_p_party`; a non-blank body means
 *   the party is in the segment, blank means not. Membership comes from [FakeSilverLayer], so a
 *   scenario can pin both the positive (party in segment ⇒ enrolled) and the negative (party
 *   outside segment ⇒ not enrolled, event acked) against the SAME generated SQL.
 * - cohort `count` (the bulk-admission scheduler probes it) — answered 0 so no admission runs.
 * - `stream`/`page` — answered empty; nothing in this suite consumes them.
 *
 * The evaluator's HTTP round trip, query-parameter binding and fail-on-non-200 behaviour are all
 * real; only the database is fake.
 */
class FakeClickHouseResource : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val query = exchange.requestURI.rawQuery ?: ""
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val response = when {
                    "cohort_size" in body -> "{\"cohort_size\":\"${FakeSilverLayer.members.size}\"}"
                    "param_p_party" in query -> {
                        val party = Regex("param_p_party=([0-9a-fA-F-]{36})").find(query)
                            ?.groupValues?.get(1)
                            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        if (party != null && party in FakeSilverLayer.members) "{}\n" else ""
                    }
                    else -> ""
                }
                exchange.sendResponseHeaders(HTTP_OK, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } catch (e: Exception) {
                val message = e.message?.toByteArray() ?: byteArrayOf()
                exchange.sendResponseHeaders(HTTP_ERROR, message.size.toLong())
                exchange.responseBody.use { it.write(message) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        return mapOf("analytics.clickhouse-url" to "http://127.0.0.1:${server.address.port}")
    }

    override fun stop() {
        server.stop(0)
    }

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_ERROR = 500
    }
}

/**
 * The campaign-broker config the trigger suite needs, supplied through a test resource.
 *
 * - No enrolment worker and no Temporal connection at boot: CampaignWorkerRegistrar observes
 *   StartupEvent and, when enabled, polls `openbank.temporal.server-url` (localhost:7233, nothing
 *   listening) — the StatusRuntimeException kills the whole boot.
 * - `auto.offset.reset=earliest` on both trigger channels: the suite publishes right after boot,
 *   before the consumer groups' first assignment, and main deliberately sets `latest` (the same
 *   reasoning as the conversion channels — history is never replayed into campaigns); the
 *   override is a test-only relaxation, measured on this suite.
 */
class CampaignTriggersBrokerResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "openbank.campaign.worker.enabled" to "false",
        "campaign.worker.enabled" to "false",
        "openbank.temporal.enabled" to "false",
        "mp.messaging.incoming.account-triggers-in.auto.offset.reset" to "earliest",
        "mp.messaging.incoming.card-triggers-in.auto.offset.reset" to "earliest",
    )

    override fun stop() = Unit
}
