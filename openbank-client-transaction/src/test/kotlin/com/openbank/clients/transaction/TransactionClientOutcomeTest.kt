// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.transaction.model.TransactionResponse
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.ProcessingException
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
import java.io.IOException
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
    fun `the Quarkus shape - a WebApplicationException carrying the status - classifies the same`() {
        status = 409
        val real = Uni.createFrom().item<Response> { throw WebApplicationException(post().await().indefinitely()) }

        assertThat(TransactionClient.classify(real, TransactionResponse::class.java).await().indefinitely())
            .isEqualTo(TransactionCallOutcome.Conflict)
    }

    @Test
    fun `the Quarkus shape - a 503 WebApplicationException becomes TransactionHttpException`() {
        status = 503
        val wae = WebApplicationException(post().await().indefinitely())
        val real = Uni.createFrom().item<Response> { throw wae }

        assertThatThrownBy {
            TransactionClient.classify(real, TransactionResponse::class.java).await().indefinitely()
        }.isInstanceOfSatisfying(TransactionHttpException::class.java) {
            assertThat(it.status).isEqualTo(503)
            assertThat(it.cause).isSameAs(wae)
        }
    }

    @Test
    fun `401 404 and 304 are thrown as TransactionHttpException, never decided`() {
        listOf(401, 404, 304).forEach { code ->
            status = code
            assertThatThrownBy { outcome() }
                .describedAs("HTTP %d", code)
                .isInstanceOfSatisfying(TransactionHttpException::class.java) { assertThat(it.status).isEqualTo(code) }
        }
    }

    @Test
    fun `the Quarkus shape - a WebApplicationException carrying a 2xx is Booked with that exception as bodyError`() {
        status = 201
        body = """{"id":"${UUID.randomUUID()}","status":"COMPLETED"}"""
        val wae = WebApplicationException(post().await().indefinitely())
        val real = Uni.createFrom().item<Response> { throw wae }

        val result = TransactionClient.classify(real, TransactionResponse::class.java).await().indefinitely()

        assertThat(result).isEqualTo(TransactionCallOutcome.Booked<TransactionResponse>(201, null, wae))
    }

    @Test
    fun `a 201 with an EMPTY body is booked with no body and no error`() {
        status = 201
        body = null

        val result = outcome() as TransactionCallOutcome.Booked

        assertThat(result.body).isNull()
        assertThat(result.bodyError).isNull()
    }

    @Test
    fun `a reader that fails with a non-Jackson exception still leaves the 2xx Booked`() {
        body = "EXPLODE"

        val result = outcome() as TransactionCallOutcome.Booked

        assertThat(result.body).isNull()
        assertThat(result.bodyError).isNotNull()
    }

    @Test
    fun `a connection dropped before any status is a ProcessingException with an IO cause`() {
        val dead = ServerSocket(0)
        val port = dead.localPort
        val acceptor = Thread {
            dead.accept().close()
            dead.close()
        }.also { it.start() }

        assertThatThrownBy {
            TransactionClient.classify(post(port), TransactionResponse::class.java).await().indefinitely()
        }.isInstanceOf(ProcessingException::class.java).hasRootCauseInstanceOf(IOException::class.java)
        acceptor.join()
    }

    // ---- close discipline and body-read failures on the refusal path, with a Response we can inspect ----

    private fun fake(code: Int, entity: Boolean = false): Response {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns code
        every { response.hasEntity() } returns entity
        return response
    }

    @Test
    fun `an unreadable 422 body leaves problem null and keeps the exception`() {
        val response = fake(422, entity = true)
        val boom = IllegalStateException("garbled")
        every { response.readEntity(String::class.java) } throws boom

        val result = TransactionClient.classify(Uni.createFrom().item(response), TransactionResponse::class.java)
            .await().indefinitely() as TransactionCallOutcome.Rejected

        assertThat(result.problem).isNull()
        assertThat(result.problemError).isSameAs(boom)
    }

    @Test
    fun `every Response is closed exactly once on every path`() {
        val cases = listOf(
            201 to false,
            204 to false,
            409 to false,
            422 to true,
            400 to false,
            503 to false,
            403 to false,
        )
        cases.forEach { (code, entity) ->
            val response = fake(code, entity)
            every { response.readEntity(String::class.java) } returns "problem"
            runCatching {
                TransactionClient.classify(Uni.createFrom().item(response), TransactionResponse::class.java)
                    .await().indefinitely()
            }
            verify(exactly = 1) { response.close() }
        }
    }

    @Test
    fun `the Response inside a WebApplicationException is closed on every path too`() {
        listOf(201, 409, 422, 503).forEach { code ->
            val response = fake(code)
            val wae = WebApplicationException(response)
            runCatching {
                TransactionClient.classify(Uni.createFrom().failure(wae), TransactionResponse::class.java)
                    .await().indefinitely()
            }
            verify(exactly = 1) { response.close() }
        }
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
