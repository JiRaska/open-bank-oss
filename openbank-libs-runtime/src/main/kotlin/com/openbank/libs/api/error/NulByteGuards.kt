// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.std.StringDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider

/**
 * Rejects the **NUL character (U+0000) in any JSON string** with 400, fleet-wide.
 *
 * ## The defect
 *
 * PostgreSQL cannot store U+0000 in a `text`/`varchar` column at all — the driver reports
 * `invalid byte sequence for encoding "UTF8": 0x00`. That is thrown at flush, far past every
 * handler, so it renders as a 500 through [GenericExceptionMapper].
 *
 * The first fuzz run to reach past authentication found this on **five money-path services** at
 * once — dispute, interest, sdd, transaction, fx (#5913). Nothing about it is service-specific:
 * the same character, the same driver, the same storage complaint.
 *
 * ## Why here, and why exactly one place
 *
 * The alternative was per-endpoint `require` calls, i.e. eleven judgement calls about a question
 * that has only one answer. Unlike a field-length limit — which is genuinely per-column and
 * per-domain — U+0000 is decidable with no domain knowledge whatsoever:
 *
 * - no valid request can carry it (it is a string terminator, not text), and
 * - Postgres can never accept it, so the request cannot succeed whatever the handler does.
 *
 * So the input is unambiguously a client error, and the boundary belongs where every service
 * shares it. This sits beside [RequiredBody] for the same reason and by the same precedent: a
 * fleet-wide 500-to-400 correction no individual handler was in a position to make.
 *
 * ## Why a Jackson deserializer rather than a scan of the raw body
 *
 * The wire form matters. Hypothesis/schemathesis emits the character inside a JSON string, so it
 * arrives **escaped** — legal JSON that Jackson decodes happily. A raw scan of the entity stream
 * for byte `0x00` therefore finds nothing and would be a guard that reports clean; scanning for
 * the escape sequence as literal text instead false-positives on a doubly-escaped backslash,
 * which is four characters of text and contains no NUL. Only the **decoded** value answers the
 * question, which is what a deserializer sees.
 *
 * It also reaches places a parameter-shaped check structurally cannot: strings nested in arrays,
 * maps and sub-objects — exactly where the collection-element blind spot documented on
 * [RequiredBody] lives.
 *
 * ## Why two classes: the body is not the only carrier
 *
 * This was measured, not assumed. Reading the six failing operations out of the fuzz run's own
 * artifacts (#5913), **two of the six carry the NUL in a query parameter, not in a body** —
 * `GET /api/v1/interest/rates?productId=%00...` and
 * `GET /api/v1/transactions/search?referenceNumber=...%00...`, both percent-encoded on the URI and
 * both landing as a bind parameter in a SELECT predicate. A Jackson-only guard would have been
 * green about them: those requests carry no entity at all, so no deserializer ever runs.
 *
 * So the boundary is one decision expressed in the two places a string can enter — the decoded
 * body ([NulByteRejectingStringDeserializer]) and the decoded URI ([NulByteParameterFilter]) —
 * the same split, for the same reason, as [AbsentBodyRequestFilter] and [NullBodyReaderInterceptor].
 *
 * ## Status
 *
 * [DeserializationContext.reportInputMismatch] raises `MismatchedInputException`, which the
 * RESTEasy Reactive Jackson reader already renders as **400**. Deliberately used in preference to
 * throwing a bare `IllegalArgumentException`: Jackson's `WRAP_EXCEPTIONS` (on by default) re-wraps
 * that into a `JsonMappingException`, so the resulting status would depend on wrapping behaviour
 * rather than on an explicit decision.
 */
internal object NulByte {

    const val NUL: Char = '\u0000'

    const val MESSAGE: String = "contains the NUL character (U+0000), which is not accepted"

    fun contains(value: String): Boolean = value.indexOf(NUL) >= 0
}

/**
 * A [StringDeserializer] that rejects any decoded string containing [NulByte.NUL].
 *
 * Delegating to the standard deserializer first is deliberate: it keeps Jackson's coercion rules
 * intact, so this only ever *adds* a rejection and never changes what an accepted request decodes
 * to.
 */
internal class NulByteRejectingStringDeserializer : StringDeserializer() {

    override fun deserialize(parser: JsonParser, context: DeserializationContext): String? {
        val value = super.deserialize(parser, context)
        if (value != null && NulByte.contains(value)) {
            context.reportInputMismatch<Any>(String::class.java, "string ${NulByte.MESSAGE}")
        }
        return value
    }
}

/** The Jackson module carrying [NulByteRejectingStringDeserializer]. Registered by [NulByteObjectMapperCustomizer]. */
internal class NulByteModule : SimpleModule("openbank-nul-byte") {
    init {
        addDeserializer(String::class.java, NulByteRejectingStringDeserializer())
    }
}

/**
 * Installs [NulByteModule] on every service's `ObjectMapper`.
 *
 * `ObjectMapperCustomizer` is the mechanism Quarkus documents for this, and every Quarkus service
 * in the fleet carries `quarkus-rest-jackson` (which brings `quarkus-jackson`). The only
 * `openbank-libs-runtime` consumers that do not are `openbank-libs*` and `openbank-simulation`,
 * none of which is a Quarkus application — so none of them runs CDI discovery over this class, and
 * the `ClassNotFoundException`-at-ArC-init hazard that keeps `ConstraintViolationExceptionMapper`
 * out of [CommonExceptionMappers] does not arise here.
 */
@Singleton
class NulByteObjectMapperCustomizer : ObjectMapperCustomizer {
    override fun customize(objectMapper: ObjectMapper) {
        objectMapper.registerModule(NulByteModule())
    }
}

/**
 * Rejects [NulByte.NUL] in any **query or path parameter**.
 *
 * Reads only [jakarta.ws.rs.core.UriInfo], which is already decoded and already parsed — no entity
 * stream is touched, so this stays non-blocking on the reactive stack.
 *
 * Not `@PreMatching`: path parameters are only populated once a resource has been matched, and
 * `interest`'s and `transaction`'s failures came through the query string of matched operations.
 * Path parameters carry no measured occurrence in the #5913 run and are covered here as defence in
 * depth — the character is exactly as impossible there, and excluding them would be a distinction
 * with no reason behind it.
 *
 * Throws `BadRequestException` rather than `IllegalArgumentException` purely for symmetry with
 * [AbsentBodyRequestFilter]; both render as 400 through [WebApplicationExceptionMapper].
 */
@Provider
class NulByteParameterFilter : ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        val uriInfo = requestContext.uriInfo
        reject(uriInfo.queryParameters, "query parameter")
        reject(uriInfo.pathParameters, "path parameter")
    }

    private fun reject(parameters: Map<String, List<String?>>, kind: String) {
        for ((name, values) in parameters) {
            if (values.any { it != null && NulByte.contains(it) }) {
                throw BadRequestException("$kind '$name' ${NulByte.MESSAGE}")
            }
        }
    }
}
