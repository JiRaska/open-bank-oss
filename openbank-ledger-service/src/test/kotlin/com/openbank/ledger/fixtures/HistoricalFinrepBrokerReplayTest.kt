// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class HistoricalFinrepBrokerReplayTest {
    private val mapper = ObjectMapper()
    private val consumerSha = "1".repeat(40)
    private val providerSha = "2".repeat(40)
    private val bytes = Files.readAllBytes(Path.of("../pacts/openbank-finrep-service-openbank-ledger-service.json"))
    private val expected = HistoricalFinrepPactExpectation(
        consumerSha,
        providerSha,
        HistoricalFinrepPactArtifact.sha256(bytes),
        mapper.readTree(bytes).path("interactions").size(),
    )

    private fun fixture(block: (URI, HttpServer, HistoricalFinrepBrokerTransport) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = URI("http://127.0.0.1:${server.address.port}")
        server.start()
        try {
            HistoricalFinrepBrokerTransport(origin).use { block(origin, server, it) }
        } finally {
            server.stop(0)
        }
    }

    private fun document(origin: URI): ObjectNode = (mapper.readTree(bytes) as ObjectNode).apply {
        val links = putObject("_links")
        links.putObject("self").put("href", HistoricalFinrepPactArtifact.publicationUri(origin, consumerSha).toString())
        links.putObject("pb:consumer-version").put("name", consumerSha)
            .put("href", "$origin/pacticipants/openbank-finrep-service/versions/$consumerSha")
        links.putObject("pb:pact-version").put("href", "$origin/pact-content")
        links.putObject("pb:publish-verification-results").put("href", "$origin/verification-results")
    }

    private fun serve(server: HttpServer, path: String, body: String, status: Int = 200) {
        server.createContext(path) { exchange ->
            val response = body.toByteArray()
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
    }

    @Test
    fun `broker source retains original content and publication lineage`() = fixture { origin, server, transport ->
        val body = document(origin).toString()
        serve(server, "/pacts/", body)
        val replay = HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport)
        assertThat(replay.originalArtifactSha256).isEqualTo(expected.contentSha256)
        assertThat(replay.brokerResponseSha256).isEqualTo(HistoricalFinrepPactArtifact.sha256(body.toByteArray()))
        assertThat(replay.pact.source).isInstanceOf(au.com.dius.pact.core.model.BrokerUrlSource::class.java)
        assertThat(replay.pact.interactions).hasSize(expected.interactionCount)
    }

    @Test
    fun `publication defaults off and incomplete failed skipped or repeated results block it`() = fixture {
            origin,
            server,
            transport,
        ->
        serve(server, "/pacts/", document(origin).toString())
        val replay = HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport)
        val never = object : HistoricalFinrepPublisher {
            override val buildUrl = URI("https://github.com/JiRaska/open-bank-oss/actions/runs/1")
            override fun publish(replay: HistoricalFinrepBrokerReplay): URI = error("Publisher must not run")
        }
        assertThatThrownBy { replay.publish(never, providerSha) }.hasMessageContaining("disabled")
        assertThatThrownBy { replay.publish(never, providerSha, true) }.hasMessageContaining("Full Pact")
        replay.record(0, true)
        assertThatThrownBy { replay.record(0, true) }.hasMessageContaining("repeated")
        replay.record(1, true, skipped = true)
        for (index in 2 until expected.interactionCount) replay.record(index, true)
        assertThatThrownBy { replay.publish(never, providerSha, true) }.hasMessageContaining("Full Pact")
    }

    @Test
    fun `supported publisher posts once then reads exact provider and Pact receipt`() = fixture {
            origin,
            server,
            transport,
        ->
        serve(server, "/pacts/", document(origin).toString())
        val posts = AtomicInteger()
        server.createContext("/verification-results") { exchange ->
            posts.incrementAndGet()
            val payload = mapper.readTree(exchange.requestBody.readAllBytes())
            assertThat(payload.path("success").booleanValue()).isTrue()
            assertThat(payload.path("providerApplicationVersion").asText()).isEqualTo(providerSha)
            assertThat(
                payload.path("buildUrl").asText(),
            ).isEqualTo("https://github.com/JiRaska/open-bank-oss/actions/runs/1")
            exchange.responseHeaders.add("Location", "$origin/receipt")
            exchange.sendResponseHeaders(201, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        val receipt = mapper.createObjectNode().put("success", true).put("providerApplicationVersion", providerSha)
        receipt.putObject("_links").putObject("pb:pact-version")
            .put("href", HistoricalFinrepPactArtifact.publicationUri(origin, consumerSha).toString())
        receipt.put("providerName", HistoricalFinrepPactArtifact.PROVIDER)
        receipt.put("buildUrl", "https://github.com/JiRaska/open-bank-oss/actions/runs/1")
        serve(server, "/receipt", receipt.toString())
        val replay = HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport)
        replay.pact.interactions.indices.forEach { replay.record(it, true) }
        val publisher = HistoricalFinrepBrokerPublisher(
            origin,
            transport,
            URI("https://github.com/JiRaska/open-bank-oss/actions/runs/1"),
        )
        assertThat(replay.publish(publisher, providerSha, true).buildUrl)
            .isEqualTo("https://github.com/JiRaska/open-bank-oss/actions/runs/1")
        assertThatThrownBy { replay.publish(publisher, providerSha, true) }.hasMessageContaining("already attempted")
        assertThat(posts.get()).isEqualTo(1)
    }

    @Test
    fun `cross origin and ambiguous links fail closed`() = fixture { origin, server, transport ->
        val document = document(origin)
        document.withObject("/_links/pb:publish-verification-results").put("href", "https://other.invalid/write")
        serve(server, "/pacts/", document.toString())
        assertThatThrownBy { HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport) }
            .hasMessageContaining("trusted origin")
        for (uri in listOf("$origin/path?query=1", "$origin/path#fragment", "$origin/%2e%2e/path", "$origin/a/../b")) {
            assertThatThrownBy { transport.validateLink(uri) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { HistoricalFinrepBrokerTransport(origin, "test-only-secret") }
            .hasMessageContaining("secure broker")
    }

    @Test
    fun `altered contract HAL bodies matching rules duplicate keys and versions are refused`() = fixture {
            origin,
            server,
            transport,
        ->
        val bodyChanged = document(origin).apply {
            withObject("/interactions/0/response/body/0").putObject("_links").put("business", "changed")
        }
        val matcherChanged = document(origin).apply {
            withObject("/interactions/0/response/matchingRules").put("unexpected", "changed")
        }
        val versionChanged = document(origin).apply {
            withObject("/_links/pb:consumer-version").put("name", "3".repeat(40))
        }
        val duplicate = document(origin).toString().replaceFirst("{", "{\"consumer\":{},")
        for (body in listOf(bodyChanged.toString(), matcherChanged.toString(), versionChanged.toString(), duplicate)) {
            serve(server, "/pacts/", body)
            assertThatThrownBy { HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport) }
                .isInstanceOf(Exception::class.java)
            server.removeContext("/pacts/")
        }
    }

    private fun captureLibraryLogs(verboseApache: Boolean = false, block: () -> String): String {
        val root = Logger.getLogger("")
        val apache = Logger.getLogger("org.apache.hc")
        val pact = Logger.getLogger("au.com.dius.pact")
        val oldApache = apache.level
        val oldPact = pact.level
        val stdout = System.out
        val stderr = System.err
        val output = ByteArrayOutputStream()
        val captured = StringBuilder()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                captured.append(record.message).append('\n')
                record.parameters?.forEach { captured.append(it).append('\n') }
                record.thrown?.let { captured.append(it.stackTraceToString()) }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        handler.level = Level.ALL
        root.addHandler(handler)
        try {
            apache.level = if (verboseApache) Level.ALL else Level.INFO
            pact.level = Level.ALL
            System.setOut(PrintStream(output))
            System.setErr(PrintStream(output))
            pact.fine("controlled-log-capture-ready")
            val traces = block()
            return traces + captured + output.toString(Charsets.UTF_8)
        } finally {
            System.setOut(stdout)
            System.setErr(stderr)
            apache.level = oldApache
            pact.level = oldPact
            root.removeHandler(handler)
            handler.close()
        }
    }

    private fun invalidModelFailure(
        origin: URI,
        server: HttpServer,
        transport: HistoricalFinrepBrokerTransport,
        version: String,
    ): IllegalArgumentException {
        server.removeContext("/pacts/")
        val invalidModel = mapper.readTree(bytes) as ObjectNode
        invalidModel.withObject("/metadata/pactSpecification").put("version", version)
        val modelBytes = mapper.writeValueAsBytes(invalidModel)
        val modelBroker = invalidModel.deepCopy()
        modelBroker.set<ObjectNode>("_links", document(origin).path("_links"))
        serve(server, "/pacts/", modelBroker.toString())
        return assertThrows<IllegalArgumentException> {
            HistoricalFinrepBrokerReplay.fetch(
                origin,
                expected.copy(contentSha256 = HistoricalFinrepPactArtifact.sha256(modelBytes)),
                modelBytes,
                transport,
            )
        }
    }

    @Test
    fun `URI JSON HTTP and credential errors leak no sentinel in complete traces or library logs`() {
        val secretHost = "private-transport-sentinel.invalid"
        val secretToken = "synthetic-sensitive-token-12501"
        val captured = captureLibraryLogs {
            val traces = StringBuilder()
            fixture { origin, server, transport ->
                traces.append(
                    assertThrows<IllegalArgumentException> {
                        transport.validateLink("https://$secretHost/path $secretToken")
                    }.stackTraceToString(),
                )
                traces.append(
                    assertThrows<IllegalArgumentException> {
                        HistoricalFinrepBrokerTransport(origin, secretToken)
                    }.stackTraceToString(),
                )
                serve(server, "/pacts/", "{ \"$secretHost\": \"$secretToken\", invalid")
                traces.append(
                    assertThrows<IllegalArgumentException> {
                        HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport)
                    }.stackTraceToString(),
                )
                traces.append(
                    invalidModelFailure(origin, server, transport, "$secretHost-$secretToken").stackTraceToString(),
                )
                val secureOrigin = URI("https://127.0.0.1:${server.address.port}")
                HistoricalFinrepBrokerTransport(secureOrigin, secretToken).use { secure ->
                    traces.append(
                        assertThrows<IllegalStateException> {
                            secure.get(URI("$secureOrigin/$secretHost/$secretToken"))
                        }.stackTraceToString(),
                    )
                }
                server.createContext("/broken") { exchange -> exchange.close() }
                traces.append(
                    assertThrows<IllegalStateException> {
                        transport.get(URI("$origin/broken/$secretHost/$secretToken"))
                    }.stackTraceToString(),
                )
                server.stop(0)
                traces.append(
                    assertThrows<IllegalStateException> {
                        transport.get(URI("$origin/$secretHost/$secretToken"))
                    }.stackTraceToString(),
                )
            }
            traces.toString()
        }
        assertThat(captured).contains("controlled-log-capture-ready")
            .doesNotContain(secretHost, secretToken, "Caused by:", "Suppressed:")
    }

    @Test
    fun `Pact debug logs and exported receipt retain no broker sentinel links`() {
        val sentinel = "private-transport-sentinel.invalid-synthetic-sensitive-token-12501"
        val captured = captureLibraryLogs {
            var summary = ""
            fixture { origin, server, transport ->
                val broker = document(origin)
                broker.withObject("/_links/pb:publish-verification-results")
                    .put("href", "$origin/verification-results/$sentinel")
                server.createContext("/pacts/") { exchange ->
                    val body = broker.toString().toByteArray()
                    exchange.responseHeaders.add(
                        "Set-Cookie",
                        "$sentinel=value; Domain=reserved.invalid; Expires=invalid",
                    )
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                server.createContext("/verification-results") { exchange ->
                    exchange.requestBody.readAllBytes()
                    exchange.responseHeaders.add("Location", "$origin/receipt/$sentinel")
                    exchange.sendResponseHeaders(201, 2)
                    exchange.responseBody.use { it.write("{}".toByteArray()) }
                }
                val receipt = mapper.createObjectNode().put("success", true)
                    .put("providerName", HistoricalFinrepPactArtifact.PROVIDER)
                    .put("providerApplicationVersion", providerSha)
                    .put("buildUrl", "https://github.com/JiRaska/open-bank-oss/actions/runs/1")
                receipt.putObject("_links").putObject("pb:pact-version").put("href", "$origin/readback")
                serve(server, "/receipt", receipt.toString())
                serve(server, "/readback", broker.toString())
                val replay = HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport)
                replay.pact.interactions.indices.forEach { replay.record(it, true) }
                val publisher = HistoricalFinrepBrokerPublisher(
                    origin,
                    transport,
                    URI("https://github.com/JiRaska/open-bank-oss/actions/runs/1"),
                )
                summary = replay.publish(publisher, providerSha, true).toString()
                assertThat(summary).doesNotContain(origin.toString(), sentinel)
            }
            summary
        }
        assertThat(captured).contains("controlled-log-capture-ready").doesNotContain(sentinel)
    }

    @Test
    fun `verbose Apache diagnostics refuse request before sentinel target can reach library logs`() {
        val sentinel = "private-transport-sentinel.invalid-synthetic-sensitive-token-12501"
        val calls = AtomicInteger()
        val captured = captureLibraryLogs(verboseApache = true) {
            var trace = ""
            fixture { origin, server, transport ->
                server.createContext("/") { exchange ->
                    calls.incrementAndGet()
                    exchange.close()
                }
                trace = assertThrows<IllegalArgumentException> {
                    transport.get(URI("$origin/$sentinel"))
                }.stackTraceToString()
            }
            trace
        }
        assertThat(calls.get()).isZero()
        assertThat(captured).contains("Verbose broker transport diagnostics").doesNotContain(sentinel)
    }

    @Test
    fun `negative receipt and Pact readback never yield proof and cannot retry publication`() {
        for (failure in listOf(
            "success",
            "provider",
            "version",
            "build",
            "cross-origin",
            "content",
            "consumer",
            "json",
        )) {
            fixture { origin, server, transport ->
                serve(server, "/pacts/", document(origin).toString())
                val receipt = mapper.createObjectNode().put("success", true)
                    .put("providerName", HistoricalFinrepPactArtifact.PROVIDER)
                    .put("providerApplicationVersion", providerSha)
                    .put("buildUrl", "https://github.com/JiRaska/open-bank-oss/actions/runs/1")
                val readback = document(origin)
                receipt.putObject("_links").putObject("pb:pact-version").put("href", "$origin/readback")
                when (failure) {
                    "success" -> receipt.put("success", false)
                    "provider" -> receipt.put("providerName", "another-provider")
                    "version" -> receipt.put("providerApplicationVersion", "3".repeat(40))
                    "build" -> receipt.put("buildUrl", "https://github.com/JiRaska/open-bank-oss/actions/runs/2")
                    "cross-origin" -> receipt.withObject("/_links/pb:pact-version")
                        .put("href", "https://reserved.invalid/readback")
                    "content" -> readback.withObject("/interactions/0/response/body/0").put("status", "CHANGED")
                    "consumer" -> readback.withObject("/_links/pb:consumer-version").put("name", "3".repeat(40))
                }
                serve(server, "/receipt", if (failure == "json") "{ invalid" else receipt.toString())
                serve(server, "/readback", readback.toString())
                val replay = HistoricalFinrepBrokerReplay.fetch(origin, expected, bytes, transport)
                replay.pact.interactions.indices.forEach { replay.record(it, true) }
                val calls = AtomicInteger()
                val publisher = object : HistoricalFinrepPublisher {
                    override val buildUrl = URI("https://github.com/JiRaska/open-bank-oss/actions/runs/1")
                    override fun publish(replay: HistoricalFinrepBrokerReplay): URI {
                        calls.incrementAndGet()
                        return URI("$origin/receipt")
                    }
                }
                assertThrows<IllegalArgumentException> { replay.publish(publisher, providerSha, true) }
                assertThrows<IllegalStateException> { replay.publish(publisher, providerSha, true) }
                assertThat(calls.get()).isEqualTo(1)
            }
        }
    }

    @Test
    fun `redirect and service unavailable are each requested once without following or retrying`() = fixture {
            origin,
            server,
            transport,
        ->
        val first = AtomicInteger()
        val redirected = AtomicInteger()
        server.createContext("/redirect") { exchange ->
            first.incrementAndGet()
            exchange.responseHeaders.add("Location", "$origin/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            redirected.incrementAndGet()
            exchange.close()
        }
        assertThatThrownBy { transport.get(URI("$origin/redirect")) }.hasMessageContaining("not accepted")
        assertThat(first.get()).isEqualTo(1)
        assertThat(redirected.get()).isZero()
        val unavailable = AtomicInteger()
        server.createContext("/unavailable") { exchange ->
            unavailable.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        assertThatThrownBy { transport.get(URI("$origin/unavailable")) }.hasMessageContaining("not accepted")
        assertThat(unavailable.get()).isEqualTo(1)
    }
}
