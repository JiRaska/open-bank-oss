// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Drives the REAL customizer over a REAL `ObjectMapper`, so both halves are exercised: that the
 * module is actually registered, and that the deserializer rejects what it claims to.
 *
 * The escaped form is what matters and is spelled out here. Every case sends the six ASCII
 * characters `\u0000` on the wire — legal JSON, which Jackson decodes to a one-character string.
 * That is the shape schemathesis produced against the five services in #5913, and the shape a raw
 * `0x00` byte scan of the entity stream cannot see.
 *
 * [nulIsAcceptedWithoutTheCustomizer] is the falsification control: it is the same payload against
 * a mapper with the guard absent, and it must PASS deserialization. Without it, every assertion
 * below could be green for a reason that has nothing to do with this class.
 */
class NulByteGuardsTest {

    private data class Payload(val name: String)

    private data class Nested(val notes: List<String>, val tags: Map<String, String>)

    /** The JSON escape for U+0000, written as source text so no control character enters this file. */
    private val escapedNul = "\\u0000"

    private fun guarded(): ObjectMapper =
        ObjectMapper().registerKotlinModule().also { NulByteObjectMapperCustomizer().customize(it) }

    private fun unguarded(): ObjectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `a NUL in a top-level string field is rejected`() {
        assertThatThrownBy { guarded().readValue("""{"name":"a${escapedNul}b"}""", Payload::class.java) }
            .isInstanceOf(MismatchedInputException::class.java)
            .hasMessageContaining("NUL")
    }

    @Test
    fun `a NUL as the entire field value is rejected`() {
        assertThatThrownBy { guarded().readValue("""{"name":"$escapedNul"}""", Payload::class.java) }
            .isInstanceOf(MismatchedInputException::class.java)
    }

    /**
     * The collection-element case. `RequiredBodyGuards` documents that reading nullability off the
     * handler parameter structurally cannot see inside a collection; a deserializer-level guard can,
     * and this is the assertion that says so.
     */
    @Test
    fun `a NUL inside an array element is rejected`() {
        assertThatThrownBy {
            guarded().readValue("""{"notes":["ok","$escapedNul"],"tags":{}}""", Nested::class.java)
        }.isInstanceOf(MismatchedInputException::class.java)
    }

    @Test
    fun `a NUL inside a map value is rejected`() {
        assertThatThrownBy {
            guarded().readValue("""{"notes":[],"tags":{"k":"$escapedNul"}}""", Nested::class.java)
        }.isInstanceOf(MismatchedInputException::class.java)
    }

    /**
     * A doubly-escaped backslash is the false positive a raw-text scan for the escape sequence
     * would produce: this string is the six literal characters `\u0000` and contains no NUL at all.
     */
    @Test
    fun `a literal backslash-u-0000 text is accepted and decodes to six characters`() {
        val decoded = guarded().readValue("""{"name":"\\u0000"}""", Payload::class.java)
        assertThat(decoded.name).isEqualTo("""\u0000""")
        assertThat(decoded.name).doesNotContain(NulByte.NUL.toString())
    }

    @Test
    fun `ordinary strings are untouched`() {
        assertThat(guarded().readValue("""{"name":"Jan Novak"}""", Payload::class.java).name)
            .isEqualTo("Jan Novak")
    }

    /**
     * FALSIFICATION CONTROL — remove the guard and the same payload must be accepted.
     *
     * This is the assertion that makes the four rejections above mean something: it fails the day
     * the customizer stops registering the module, or the day some other Jackson default starts
     * rejecting the payload for an unrelated reason.
     */
    @Test
    fun nulIsAcceptedWithoutTheCustomizer() {
        assertThatCode {
            val decoded = unguarded().readValue("""{"name":"a${escapedNul}b"}""", Payload::class.java)
            assertThat(decoded.name).hasSize(3)
            assertThat(decoded.name).contains(NulByte.NUL.toString())
        }.doesNotThrowAnyException()
    }

    // ---- NulByteParameterFilter ------------------------------------------------------------
    //
    // Two of the six operations in #5913 carried the NUL in a QUERY STRING, not a body:
    // `GET /api/v1/interest/rates?productId=%00...` and
    // `GET /api/v1/transactions/search?referenceNumber=...%00...`. Those requests have no entity,
    // so no deserializer runs and every assertion above is silent about them. These are the cases
    // that make the filter non-redundant.

    private fun request(
        query: Map<String, String> = emptyMap(),
        path: Map<String, String> = emptyMap(),
    ): ContainerRequestContext {
        fun mv(m: Map<String, String>): MultivaluedMap<String, String> =
            MultivaluedHashMap<String, String>().apply { m.forEach { (k, v) -> add(k, v) } }
        val uriInfo = mockk<UriInfo>()
        every { uriInfo.queryParameters } returns mv(query)
        every { uriInfo.pathParameters } returns mv(path)
        val context = mockk<ContainerRequestContext>()
        every { context.uriInfo } returns uriInfo
        return context
    }

    /** interest: `GET /api/v1/interest/rates?productId=%00...` — decoded, the value starts with NUL. */
    @Test
    fun `a NUL in a query parameter is rejected`() {
        assertThatThrownBy {
            NulByteParameterFilter().filter(request(query = mapOf("productId" to "${NulByte.NUL}x")))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("productId")
            .hasMessageContaining("NUL")
    }

    /** transaction: `GET /api/v1/transactions/search?referenceNumber=...%00...` — NUL mid-value. */
    @Test
    fun `a NUL mid-value in a query parameter is rejected`() {
        assertThatThrownBy {
            NulByteParameterFilter().filter(
                request(query = mapOf("referenceNumber" to "+ab${NulByte.NUL}cd", "type" to "DEBIT")),
            )
        }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("referenceNumber")
    }

    @Test
    fun `a NUL in a path parameter is rejected`() {
        assertThatThrownBy {
            NulByteParameterFilter().filter(request(path = mapOf("transactionId" to "a${NulByte.NUL}")))
        }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("transactionId")
    }

    /**
     * FALSIFICATION CONTROL for the filter — an ordinary request must pass through untouched.
     *
     * Without this, "the filter rejects things" would be equally true of a filter that rejects
     * every request, which would take the whole fleet down rather than fix five services.
     */
    @Test
    fun `ordinary query and path parameters pass through`() {
        assertThatCode {
            NulByteParameterFilter().filter(
                request(
                    query = mapOf("productId" to "SAVINGS-01", "from" to "2026-01-01"),
                    path = mapOf("transactionId" to "9f1c2d3e-0000-4a5b-8c9d-1e2f3a4b5c6d"),
                ),
            )
        }.doesNotThrowAnyException()
    }
}
