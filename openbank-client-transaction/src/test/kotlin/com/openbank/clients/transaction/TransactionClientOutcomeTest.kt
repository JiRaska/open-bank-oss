// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.transaction.model.TransactionResponse
import com.sun.net.httpserver.HttpServer
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.client.ClientBuilder
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.MessageBodyReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.lang.reflect.Type
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * The classification rule of [TransactionClient], exercised over REAL sockets with a REAL JAX-RS
 * client: the cases that matter on a money path (a 2xx whose body cannot be read, a 204, a refusal,
 * an outage, a dropped connection) are exactly the ones a mocked Response cannot show.
 */
class TransactionClientOutcomeTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val client = ClientBuilder.newBuilder().register(JsonReader(mapper)).build()
    private lateinit var server: HttpServer
    private var status = 201
    private var body: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = body?.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            // -1 = no body (a 204 must not carry one).
            exchange.sendResponseHeaders(status, if (bytes == null) -1 else bytes.size.toLong())
            bytes?.let { exchange.responseBody.write(it) }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        client.close()
    }

    private fun post(port: Int = server.address.port): Uni<Response> = Uni.createFrom().item {
        client.target("http://127.0.0.1:$port/api/v1/transactions").request()
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON))
    }

    private fun outcome() = TransactionClient.classify(post(), TransactionResponse::class.java).await().indefinitely()

    @Test
    fun `201 with a readable body is Booked with the body`() {
        val id = UUID.randomUUID()
        body = """{"id":"$id","status":"COMPLETED"}"""

        val result = outcome() as TransactionCallOutcome.Booked

        assertThat(result.status).isEqualTo(201)
        assertThat(result.body?.id).isEqualTo(id)
        assertThat(result.bodyError).isNull()
    }

    @Test
    fun `201 whose body cannot be read is STILL Booked - the status was proven first`() {
        body = """{"id":"not-a-uuid","status":"COMPLETED"}"""

        val result = outcome() as TransactionCallOutcome.Booked

        assertThat(result.status).isEqualTo(201)
        assertThat(result.body).isNull()
        assertThat(result.bodyError).isNotNull()
    }

    @Test
    fun `204 is Booked with no body and no body error`() {
        status = 204

        val result = outcome() as TransactionCallOutcome.Booked

        assertThat(result.status).isEqualTo(204)
        assertThat(result.body).isNull()
        assertThat(result.bodyError).isNull()
    }

    @Test
    fun `409 is Conflict`() {
        status = 409
        body = """{"code":"CONFLICT"}"""

        assertThat(outcome()).isEqualTo(TransactionCallOutcome.Conflict)
    }

    @Test
    fun `422 is Rejected and carries the problem text`() {
        status = 422
        body = """{"code":"NOT_REVERSIBLE"}"""

        val result = outcome() as TransactionCallOutcome.Rejected

        assertThat(result.status).isEqualTo(422)
        assertThat(result.problem).contains("NOT_REVERSIBLE")
    }

    @Test
    fun `400 is Rejected`() {
        status = 400

        assertThat((outcome() as TransactionCallOutcome.Rejected).status).isEqualTo(400)
    }

    @Test
    fun `403 is thrown, never decided`() {
        status = 403

        assertThatThrownBy { outcome() }.isInstanceOfSatisfying(TransactionHttpException::class.java) {
            assertThat(it.status).isEqualTo(403)
        }
    }

    @Test
    fun `503 is thrown so the caller retries`() {
        status = 503

        assertThatThrownBy { outcome() }.isInstanceOfSatisfying(TransactionHttpException::class.java) {
            assertThat(it.status).isEqualTo(503)
        }
    }

    @Test
    fun `a connection dropped before any status is thrown, not classified`() {
        val dead = ServerSocket(0)
        val port = dead.localPort
        val acceptor = Thread {
            dead.accept().close()
            dead.close()
        }.also { it.start() }

        assertThatThrownBy {
            TransactionClient.classify(post(port), TransactionResponse::class.java).await().indefinitely()
        }.isNotInstanceOf(WebApplicationException::class.java)
        acceptor.join()
    }

    @Test
    fun `the Quarkus shape - a WebApplicationException carrying the status - classifies the same`() {
        status = 409
        val real = Uni.createFrom().item<Response> { throw WebApplicationException(post().await().indefinitely()) }

        assertThat(TransactionClient.classify(real, TransactionResponse::class.java).await().indefinitely())
            .isEqualTo(TransactionCallOutcome.Conflict)
    }

    @Test
    fun `the Quarkus shape - a 503 WebApplicationException is rethrown unchanged`() {
        status = 503
        val real = Uni.createFrom().item<Response> { throw WebApplicationException(post().await().indefinitely()) }

        assertThatThrownBy {
            TransactionClient.classify(real, TransactionResponse::class.java).await().indefinitely()
        }.isInstanceOfSatisfying(WebApplicationException::class.java) { assertThat(it.response.status).isEqualTo(503) }
    }

    /** Minimal JSON reader: the facade reads bodies through whatever the caller's client registers. */
    private class JsonReader(private val mapper: ObjectMapper) : MessageBodyReader<Any> {
        override fun isReadable(
            type: Class<*>,
            genericType: Type?,
            annotations: Array<out Annotation>?,
            mediaType: MediaType?,
        ) = true

        override fun readFrom(
            type: Class<Any>,
            genericType: Type?,
            annotations: Array<out Annotation>?,
            mediaType: MediaType?,
            httpHeaders: MultivaluedMap<String, String>?,
            entityStream: InputStream,
        ): Any = if (type ==
            String::class.java
        ) {
            String(entityStream.readAllBytes())
        } else {
            mapper.readValue(entityStream, type)
        }
    }
}
