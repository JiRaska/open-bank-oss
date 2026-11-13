// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.account

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.account.model.AccountResponse
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.client.ClientBuilder
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
 * The classification rule of [AccountClient], over REAL sockets with a REAL JAX-RS client: a 404, an
 * unreadable 2xx and a refused connection are exactly the cases a mocked Response cannot show.
 */
class AccountClientOutcomeTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val client = ClientBuilder.newBuilder().register(JsonReader(mapper)).build()
    private lateinit var server: HttpServer
    private var status = 200
    private var body: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body?.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
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

    private fun get(port: Int = server.address.port): Uni<Response> = Uni.createFrom().item {
        client.target("http://127.0.0.1:$port/api/v1/accounts/x").request().get()
    }

    private fun lookup() = AccountClient.classify(get()).await().indefinitely()

    @Test
    fun `200 with a readable body is Found with the account`() {
        val id = UUID.randomUUID()
        val party = UUID.randomUUID()
        body = """{"id":"$id","partyId":"$party","status":"ACTIVE"}"""

        val found = lookup() as AccountLookup.Found

        assertThat(found.account.id).isEqualTo(id)
        assertThat(found.account.partyId).isEqualTo(party)
    }

    @Test
    fun `a status value the generated enum does not know still yields Found`() {
        val party = UUID.randomUUID()
        body = """{"id":"${UUID.randomUUID()}","partyId":"$party","status":"SOME_FUTURE_STATUS","newField":true}"""

        val found = lookup() as AccountLookup.Found

        assertThat(found.account.partyId).isEqualTo(party)
        assertThat(found.account.status).isNull()
    }

    @Test
    fun `the tolerance is scoped to this client - a default mapper still rejects the unknown status`() {
        val strict = jacksonObjectMapper().registerModule(JavaTimeModule())
        assertThatThrownBy {
            strict.readValue("""{"status":"SOME_FUTURE_STATUS"}""", AccountResponse::class.java)
        }.isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException::class.java)
    }

    @Test
    fun `404 is NotFound, not a failure`() {
        status = 404
        body = """{"code":"NOT_FOUND"}"""

        assertThat(lookup()).isEqualTo(AccountLookup.NotFound)
    }

    @Test
    fun `200 whose body cannot be read is AccountBodyException`() {
        body = """{"id":"not-a-uuid"}"""

        assertThatThrownBy { lookup() }.isInstanceOf(AccountBodyException::class.java)
    }

    @Test
    fun `204 with no body is AccountBodyException`() {
        status = 204

        assertThatThrownBy { lookup() }.isInstanceOf(AccountBodyException::class.java)
    }

    @Test
    fun `403 and 503 are AccountHttpException carrying the status`() {
        for (s in listOf(403, 503)) {
            status = s
            body = "{}"
            assertThatThrownBy { lookup() }
                .isInstanceOfSatisfying(AccountHttpException::class.java) { assertThat(it.status).isEqualTo(s) }
        }
    }

    @Test
    fun `a refused connection is thrown unchanged`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertThatThrownBy { AccountClient.classify(get(closedPort)).await().indefinitely() }
            .isInstanceOf(ProcessingException::class.java)
    }

    @Test
    fun `a thrown WebApplicationException is classified like a returned Response`() {
        fun thrown(s: Int): Uni<Response> {
            val response = mockk<Response>(relaxed = true)
            every { response.status } returns s
            return Uni.createFrom().failure(WebApplicationException(response))
        }

        assertThat(AccountClient.classify(thrown(404)).await().indefinitely()).isEqualTo(AccountLookup.NotFound)
        assertThatThrownBy { AccountClient.classify(thrown(500)).await().indefinitely() }
            .isInstanceOfSatisfying(AccountHttpException::class.java) {
                assertThat(it.status).isEqualTo(500)
                assertThat(it.cause).isInstanceOf(WebApplicationException::class.java)
            }
    }

    @Test
    fun `every Response is closed, also on the throwing path`() {
        val response = mockk<Response>(relaxed = true)
        every { response.status } returns 500

        assertThatThrownBy { AccountClient.classify(Uni.createFrom().item(response)).await().indefinitely() }
            .isInstanceOf(AccountHttpException::class.java)
        verify { response.close() }
    }

    private class JsonReader(private val mapper: ObjectMapper) : MessageBodyReader<Any> {
        override fun isReadable(
            type: Class<*>,
            genericType: Type?,
            annotations: Array<out Annotation>?,
            mediaType: MediaType?,
        ) = !InputStream::class.java.isAssignableFrom(type)

        override fun readFrom(
            type: Class<Any>,
            genericType: Type?,
            annotations: Array<out Annotation>?,
            mediaType: MediaType?,
            httpHeaders: MultivaluedMap<String, String>?,
            entityStream: InputStream,
        ): Any = mapper.readValue(entityStream, type)
    }
}
