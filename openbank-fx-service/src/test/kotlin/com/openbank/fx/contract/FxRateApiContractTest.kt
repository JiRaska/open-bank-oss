// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.fx.integration.FxBootSmokeIT
import com.openbank.fx.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.restassured.RestAssured.given
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Spec-conformance contract test for the FX quote surface — the identity half of
 * `ExchangeRateResponse` (issue #3374).
 *
 * ## Why this is not a Pact
 *
 * The one committed pact for this provider, `openbank-transaction-service-openbank-fx-service`,
 * covers `GET /api/v1/fx/rates/EUR/CZK` — the STORED direction — and pins four fields, none of them
 * `id`. The defect here lives in the DERIVED direction, which no in-repo consumer calls, so writing
 * a pact for it would be writing a contract against a fictional consumer: it would prove only that
 * a test agrees with itself. The counterparty for this property is the committed `openapi.yaml`,
 * which is what the mobile app and any external integrator code against, so that document is what
 * the assertions are derived from. (The existing pact is untouched and still replayed on every PR
 * by [FxPactFolderProviderVerificationTest]; the two new response properties are additive, and pact
 * ignores fields it does not name, so it cannot be affected either way.)
 *
 * ## The spec is PARSED, never grepped
 *
 * Every expectation below is navigated to by JSON-Pointer, so the test cannot pass by matching
 * prose — the failure mode #2291 documents, where a `contains(...)` over YAML text scored services
 * as covered on a comment. If `id` or `derivedFrom` is deleted from `ExchangeRateResponse`, the
 * pointer resolves to null and this test fails before it ever issues a request.
 *
 * ## What it pins, and why each half is needed
 *
 *  - **Both directions carry an `id`, and the two ids DIFFER.** This is the reported defect: before
 *    #3374 `EUR/CZK` and `CZK/EUR` answered under one identifier with inverted numbers, so the id
 *    did not identify what it named and an audit record citing it could not be replayed to a
 *    direction.
 *  - **The derived quote's `derivedFrom` is the stored quote's `id`.** Provenance has to be
 *    readable by the caller, not inferred from which way they happened to ask.
 *  - **The stored quote declares no derivation.** A discriminator that is always set discriminates
 *    nothing.
 *  - **The derived `id` is stable across calls.** A per-request id would make the field useless as
 *    a cache key or an audit reference — the same defect in a different shape.
 *
 * ## Driven for real
 *
 * `@QuarkusTest` + RestAssured rather than calling the use case directly, because the wire JSON is
 * what the contract is about: the endpoint serialises the domain [FxRate], so Jackson property
 * naming and null handling only exist after serialisation. [com.openbank.fx.domain.model.FxRateTest]
 * and [com.openbank.fx.application.usecase.FxServiceTest] cover the derivation and the money-path
 * `rateId` rule in-process; this covers the wire format.
 *
 * ## Known residual drift, deliberately NOT asserted here
 *
 * `ExchangeRateResponse` also declares `rate` and `validAt`, which the endpoint has never served,
 * and omits nine properties it does serve (`bidRate`, `askRate`, `rateType`, `source`, `validFrom`,
 * `validTo`, `createdAt`, `pair`, `midRate`). Exact set equality in both directions — the shape
 * `Ap2ApiContractTest` uses — is the right end state, but reaching it means REMOVING two declared
 * response properties, which `oasdiff` classifies as breaking and would force a major bump, i.e.
 * `/api/v2` (ADR-0048 ties the spec major to the URL). That is a separate decision from fixing an
 * id, so this test asserts the identity contract exactly and the type of every declared property it
 * does find, and the wider realignment is left to its own change.
 */
@QuarkusTest
@QuarkusTestResource(FxBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
class FxRateApiContractTest {

    @Inject
    lateinit var rateRepo: FxRateRepository

    @Inject
    lateinit var vertx: Vertx

    private val spec: JsonNode = YAMLMapper().readTree(File(SPEC_PATH))

    private val rateSchema: JsonNode
        get() = spec.at(RATE_SCHEMA_POINTER).also {
            assertThat(it.isMissingNode)
                .describedAs("openapi.yaml must declare an ExchangeRateResponse schema")
                .isFalse()
        }

    private lateinit var storedId: UUID

    @BeforeEach
    fun seedStoredQuote() {
        // Only ONE direction is stored, mirroring how a fixing publishes it: the
        // reverse answer must therefore be derived, which is the case under test.
        runOnVertxContext {
            val existing = rateRepo.findLatest(BASE, QUOTE, RateType.SPOT)
            if (existing != null) {
                storedId = existing.id
                return@runOnVertxContext
            }
            val now = Instant.now()
            val id = UUID.randomUUID()
            rateRepo.save(
                FxRate(
                    id = id,
                    baseCurrency = BASE,
                    quoteCurrency = QUOTE,
                    bidRate = BigDecimal("24.80"),
                    askRate = BigDecimal("25.30"),
                    rateType = RateType.SPOT,
                    source = RateSource.CNB,
                    validFrom = now.minusSeconds(HOUR_SECONDS),
                    validTo = now.plusSeconds(DAY_SECONDS),
                    createdAt = now,
                ),
            )
            storedId = id
        }
    }

    @Test
    fun `the spec declares both identity properties on the quote schema`() {
        // Read from the document rather than assumed, so deleting either property from
        // openapi.yaml fails here instead of silently narrowing every assertion below.
        listOf(ID_PROPERTY, DERIVED_FROM_PROPERTY).forEach { name ->
            val property = rateSchema.at("/properties/$name")
            assertThat(property.isMissingNode)
                .describedAs("ExchangeRateResponse must declare '%s'", name)
                .isFalse()
            assertThat(property.path("type").asText()).isEqualTo("string")
            assertThat(property.path("format").asText()).isEqualTo("uuid")
        }
    }

    @Test
    fun `the two directions of one pair answer under different ids`() {
        val direct = quote(BASE, QUOTE)
        val derived = quote(QUOTE, BASE)

        assertThat(direct.path(ID_PROPERTY).asText()).isNotBlank()
        assertThat(derived.path(ID_PROPERTY).asText()).isNotBlank()
        assertThat(derived.path(ID_PROPERTY).asText())
            .describedAs("a derived quote must not answer under the stored row's id (#3374)")
            .isNotEqualTo(direct.path(ID_PROPERTY).asText())
    }

    @Test
    fun `the derived quote names the stored row it came from`() {
        val derived = quote(QUOTE, BASE)

        assertThat(derived.path(DERIVED_FROM_PROPERTY).asText()).isEqualTo(storedId.toString())
        assertThat(derived.path("baseCurrency").asText()).isEqualTo(QUOTE)
        assertThat(derived.path("quoteCurrency").asText()).isEqualTo(BASE)
    }

    @Test
    fun `the stored quote declares no derivation`() {
        val direct = quote(BASE, QUOTE)

        assertThat(direct.path(DERIVED_FROM_PROPERTY).isNull || direct.path(DERIVED_FROM_PROPERTY).isMissingNode)
            .describedAs("a stored quote must not claim to be derived, or the field discriminates nothing")
            .isTrue()
        assertThat(direct.path(ID_PROPERTY).asText()).isEqualTo(storedId.toString())
    }

    @Test
    fun `the derived id is stable across calls`() {
        assertThat(quote(QUOTE, BASE).path(ID_PROPERTY).asText())
            .isEqualTo(quote(QUOTE, BASE).path(ID_PROPERTY).asText())
    }

    @Test
    fun `every declared property the endpoint serves carries the declared JSON type`() {
        val derived = quote(QUOTE, BASE)

        rateSchema.path("properties").fields().forEach { (name, declared) ->
            val served = derived.path(name)
            if (served.isMissingNode || served.isNull) return@forEach
            when (declared.path("type").asText()) {
                "string" -> assertThat(served.isTextual).describedAs("%s is a string", name).isTrue()
                "number" -> assertThat(served.isNumber).describedAs("%s is a number", name).isTrue()
                else -> Unit
            }
        }
    }

    private fun quote(base: String, quote: String): JsonNode {
        val body = given().`when`().get("/api/v1/fx/rates/$base/$quote")
            .then().statusCode(HTTP_OK)
            .extract().body().asString()
        return YAMLMapper().readTree(body)
    }

    /**
     * Bridges a reactive-Panache block into a plain JUnit thread. [rateRepo] uses
     * `Panache.withTransaction`/`withSession`, which needs a Vert.x context that a bare
     * `@QuarkusTest` thread does not carry — same pattern and same reason as
     * [FxPactFolderProviderVerificationTest.runOnVertxContext].
     */
    private fun runOnVertxContext(block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val SPEC_PATH = "src/main/resources/openapi.yaml"
        const val RATE_SCHEMA_POINTER = "/components/schemas/ExchangeRateResponse"
        const val ID_PROPERTY = "id"
        const val DERIVED_FROM_PROPERTY = "derivedFrom"

        // A SYNTHETIC pair, not EUR/CZK, and the reason matters beyond this test.
        //
        // `V1__create_fx.sql:39` seeds a stored `CZK/EUR` row. `FxService.getRate` tries the direct
        // direction first, so in a fresh database `GET /rates/CZK/EUR` answers from that row and
        // never derives anything — the very path under test does not run, and the assertion below
        // sees a legitimate null `derivedFrom`. Using EUR/CZK here failed for exactly that reason.
        //
        // It also explains why #3374 was observable in sandbox and not locally: the seeded row's
        // `validTo` is `NOW() + INTERVAL '1 day'` fixed at migration time, and `findLatest` filters
        // `validTo > now`, so the seed ages out after a day. A long-lived environment therefore
        // derives CZK/EUR; a freshly migrated one serves the stored row.
        //
        // Synthetic ISO 4217 codes in the user-assigned XA-XZ range, so no migration seed, no other
        // test and no real fixing can supply the reverse direction and quietly make this vacuous.
        const val BASE = "XQA"
        const val QUOTE = "XQB"
        const val HTTP_OK = 200
        const val HOUR_SECONDS = 3600L
        const val DAY_SECONDS = 86400L
        const val AWAIT_SECONDS = 10L
    }
}
