// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY
import com.openbank.libs.web.SyntheticTaintContext
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.path.json.JsonPath
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-0252 phase 1 (#8615): the synthetic taint must reach the ledger's own state, and the trial
 * balance must be able to leave it out.
 *
 * ## What this test can detect that nothing before it could
 *
 * `V24` put `synthetic` on `ledger_outbox`, so the taint survived onto Kafka and died in the book
 * of record. The regulatory returns are built from the JOURNAL — finrep-service reads the trial
 * balance, not the event stream — so a canary posting was already summed into the same balances as
 * real customer money, and no consumer had anything to filter on. Both halves are asserted here:
 * the column is written (JDBC, straight at the row), and the aggregate separates the populations.
 *
 * ## Why the assertions are DELTAS
 *
 * The trial balance is cumulative over the whole GL, and other ITs in this module post into it. An
 * absolute expectation would be a test about which classes ran first. Each population is therefore
 * measured before and after this test's own two postings, and the delta is what must equal the
 * amount posted — which is also what makes the test fail if the scope filter stops filtering:
 * REAL_ONLY would then move by the synthetic amount too.
 *
 * ## Why it drives real HTTP
 *
 * The taint is decided by `SyntheticTaintRequestFilter` from a header plus a trusted principal;
 * only a real request carries either. A direct use-case call would supply `synthetic = true` by
 * hand and prove nothing about the path a canary actually takes. A reactive Panache repo also
 * cannot be called from a bare `@QuarkusTest` thread, so the row is read back over plain JDBC.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestProfile(SyntheticLedgerDimensionIT.TrustedCanaryProfile::class)
class SyntheticLedgerDimensionIT {

    @Inject
    lateinit var propertyLoss: SyntheticPropertyLossFixture

    /**
     * Names the canary principal as trusted. The filter's list is empty by default and an untrusted
     * caller's header is ignored, so without this override the "synthetic" posting below would be
     * recorded as REAL and every assertion here would pass for the wrong reason — the taint would
     * simply never be asserted. That is the fail-to-real direction the filter argues for, and it is
     * exactly why the trusted principal has to be configured for the test to mean anything.
     */
    class TrustedCanaryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.synthetic.trusted-principals" to CANARY_PRINCIPAL,
            // Force the missing-property condition only in this HTTP test profile. The real
            // authentication/filter decision remains the sole writer of the CDI holder.
            "openbank.test.synthetic-taint.drop-request-property" to "true",
        )
    }

    @Test
    @TestSecurity(user = CANARY_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = CANARY_PRINCIPAL),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `a synthetic posting is recorded on the journal and excluded from the default trial balance`() {
        val realBefore = netFor(scope = null)
        val syntheticBefore = netFor(scope = "SYNTHETIC_ONLY")
        val allBefore = netFor(scope = "ALL")

        val realJournalId = postJournal(TODAY, REAL_AMOUNT, tainted = false)
        val syntheticJournalId = postJournal(TODAY, SYNTHETIC_AMOUNT, tainted = true)

        // Half one: the dimension exists on the book of record. Read straight off the row, so a
        // mapper that quietly dropped the flag cannot hide behind the API that reports it.
        assertThat(syntheticColumnOf(realJournalId)).`as`("real journal row").isFalse()
        assertThat(syntheticColumnOf(syntheticJournalId)).`as`("synthetic journal row").isTrue()

        // Half two: the aggregate can leave it out — and does so for a caller who said nothing.
        assertThat(netFor(scope = null) - realBefore)
            .`as`("default (REAL_ONLY) trial balance moves by the REAL posting only")
            .isEqualTo(REAL_AMOUNT, WITHIN)
        assertThat(netFor(scope = "REAL_ONLY") - realBefore)
            .`as`("explicit REAL_ONLY agrees with the default")
            .isEqualTo(REAL_AMOUNT, WITHIN)
        assertThat(netFor(scope = "SYNTHETIC_ONLY") - syntheticBefore)
            .`as`("SYNTHETIC_ONLY sees the canary posting and only it")
            .isEqualTo(SYNTHETIC_AMOUNT, WITHIN)
        assertThat(netFor(scope = "ALL") - allBefore)
            .`as`("ALL sees both")
            .isEqualTo(REAL_AMOUNT + SYNTHETIC_AMOUNT, WITHIN)
    }

    @Test
    @TestSecurity(user = CANARY_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = CANARY_PRINCIPAL),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `every scope stays balanced and names itself`() {
        // Excluding a journal must remove BOTH its legs. A per-line filter would keep one and leave
        // the GL unbalanced in that scope while still being green on any single-account assertion.
        for (scope in listOf(null, "REAL_ONLY", "SYNTHETIC_ONLY", "ALL")) {
            val body = trialBalance(scope)
            assertThat(body.getBoolean("balanced")).`as`("balanced in scope=$scope").isTrue()
            assertThat(body.getString("scope"))
                .`as`("the response says which population it counted")
                .isEqualTo(scope ?: "REAL_ONLY")
        }
    }

    @Test
    @TestSecurity(user = CANARY_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = CANARY_PRINCIPAL),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `an unrecognised scope is refused rather than silently answered as real`() {
        Given {
            queryParam("scope", "rael")
        } When {
            get("/api/v1/journals/trial-balance")
        } Then {
            statusCode(400)
        }
    }

    /**
     * A caller that is NOT in `openbank.synthetic.trusted-principals` cannot taint its own postings.
     * Without this, the dimension would be a self-service way out of the regulatory aggregates.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000ff", roles = ["ROLE_OPERATOR"])
    fun `an untrusted principal cannot mark its own posting synthetic`() {
        val journalId = postJournal(TODAY, REAL_AMOUNT, tainted = true)
        assertThat(syntheticColumnOf(journalId))
            .`as`("an untrusted taint claim is declined, and the posting stays REAL")
            .isFalse()
    }

    @Test
    @TestSecurity(user = CANARY_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = CANARY_PRINCIPAL),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `concurrent trusted and ordinary postings keep separate CDI decisions after property loss`() {
        val held = propertyLoss.beginPair()
        val clients = Executors.newFixedThreadPool(2)
        val posts = mutableListOf<Future<UUID>>()
        try {
            val synthetic = clients.submit<UUID> { postJournal(TODAY, SYNTHETIC_AMOUNT, tainted = true) }
            posts += synthetic
            // Only the test thread waits. The server has assigned TRUE and returns an incomplete Uni.
            held.syntheticAssigned.get(10, TimeUnit.SECONDS)
            val real = clients.submit<UUID> { postJournal(TODAY, REAL_AMOUNT, tainted = false) }
            posts += real
            held.realAssigned.get(10, TimeUnit.SECONDS)
            // FALSE was assigned while the TRUE request was still alive, before either ledger read.
            // A shared holder would now turn the first journal REAL, even if both requests return 201.
            held.resume.complete(null)

            assertThat(syntheticColumnOf(synthetic.get(10, TimeUnit.SECONDS)))
                .`as`("concurrent synthetic journal retains its own CDI decision").isTrue()
            assertThat(syntheticColumnOf(real.get(10, TimeUnit.SECONDS)))
                .`as`("concurrent ordinary journal retains its own CDI decision").isFalse()
        } finally {
            propertyLoss.endPair(held)
            posts.forEach { it.cancel(true) }
            clients.shutdownNow()
            check(clients.awaitTermination(10, TimeUnit.SECONDS)) { "HTTP test clients did not terminate" }
        }
        val followUp = postJournal(TODAY, REAL_AMOUNT, tainted = false)
        assertThat(syntheticColumnOf(followUp)).`as`("fresh ordinary request remains REAL").isFalse()
    }

    private fun postJournal(date: String, amount: Double, tainted: Boolean): UUID {
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$date",
              "valueDate": "$date",
              "description": "ADR-0252 dimension IT (tainted=$tainted)",
              "createdBy": "$CANARY_ACTOR_ID",
              "lines": [
                {
                  "glAccountId": "$DEBIT_ACCOUNT",
                  "side": "DEBIT",
                  "amount": "$amount",
                  "currencyCode": "USD",
                  "baseAmount": "$amount",
                  "baseCurrencyCode": "USD"
                },
                {
                  "glAccountId": "$CREDIT_ACCOUNT",
                  "side": "CREDIT",
                  "amount": "$amount",
                  "currencyCode": "USD",
                  "baseAmount": "$amount",
                  "baseCurrencyCode": "USD"
                }
              ]
            }
        """.trimIndent()

        val response = (
            Given {
                contentType("application/json")
                body(payload)
                if (tainted) header(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.TRUE_VALUE) else this
            } When {
                post("/api/v1/journals")
            } Then {
                statusCode(201)
            }
            ).extract().jsonPath()

        return UUID.fromString(response.getString("id"))
    }

    private fun trialBalance(scope: String?): JsonPath = (
        Given {
            // asOf is always explicit. The service's Clock is UTC while the test JVM's default zone
            // is not, so `LocalDate.now()` on the two sides disagree for two hours a day — long
            // enough for this test to have been green on the wrong reason at 09:00 and red at 00:05.
            queryParam("asOf", TODAY)
            if (scope != null) queryParam("scope", scope) else this
        } When {
            get("/api/v1/journals/trial-balance")
        } Then {
            statusCode(200)
        }
        ).extract().jsonPath()

    /** Total debit booked to the test's debit account, in the given scope. */
    private fun netFor(scope: String?): Double =
        trialBalance(scope).getString("lines.find { it.code == '$DEBIT_CODE' }.totalDebit")?.toDouble() ?: 0.0

    /** Reads the column itself — the API is not allowed to be the only witness of its own state. */
    private fun syntheticColumnOf(journalId: UUID): Boolean {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement("select synthetic from journal_entries where id = ?").use { statement ->
                statement.setObject(1, journalId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no journal_entries row for $journalId" }
                    return rows.getBoolean(1)
                }
            }
        }
    }

    companion object {
        /** The canary service account. Trusted only because [TrustedCanaryProfile] names it. */
        const val CANARY_PRINCIPAL = "service-account-openbank-synthetic-canary"

        // The journal author is a UUID; the authenticated service-account name is not.
        const val CANARY_ACTOR_ID = "00000000-0000-0000-0000-000000000252"

        // Two single-currency USD leaf accounts no other IT in this module posts to. The deltas
        // below make that a convenience rather than a dependency.
        private const val DEBIT_CODE = "1202"
        private const val DEBIT_ACCOUNT = "a0000000-0000-0000-0000-000000001202"
        private const val CREDIT_ACCOUNT = "a0000000-0000-0000-0000-000000004102"

        // Deliberately unequal, so an assertion cannot pass by reading the other population.
        private const val REAL_AMOUNT = 111.11
        private const val SYNTHETIC_AMOUNT = 222.22
        private val WITHIN: Offset<Double> = Offset.offset(0.001)

        /** The service books and aggregates on a UTC clock; so does this test, on both sides. */
        private val TODAY: String = LocalDate.now(Clock.systemUTC()).toString()
    }
}

/**
 * Test-only fault injection after the real inbound trust filter. It removes only the legacy
 * property, never writes the holder or grants identity, and defaults off outside this profile.
 * This models property loss; it does not reproduce the dispatch of an older deployed image.
 */
@ApplicationScoped
class SyntheticPropertyLossFixture {
    @ConfigProperty(name = "openbank.test.synthetic-taint.drop-request-property", defaultValue = "false")
    var enabled: Boolean = false

    @Inject
    lateinit var holder: SyntheticTaintContext

    private val active = AtomicReference<HeldRequests?>()

    fun beginPair(): HeldRequests = HeldRequests().also {
        check(active.compareAndSet(null, it)) { "another HTTP pair is already armed" }
    }

    fun endPair(held: HeldRequests) {
        check(active.compareAndSet(held, null)) { "the HTTP pair changed unexpectedly" }
        // A failed assertion must also release server requests. Completion is a no-op after success.
        held.resume.completeExceptionally(IllegalStateException("HTTP pair ended before release"))
    }

    // The original ContainerRequestFilter has the default USER priority. The Uni suspends the
    // filter chain without blocking its event loop; null resumes the actual ledger resource.
    @ServerRequestFilter(priority = Priorities.USER + 1)
    fun filter(ctx: ContainerRequestContext): Uni<Response?> {
        if (!enabled || ctx.method != "POST" || ctx.uriInfo.path.trim('/') != "api/v1/journals") {
            return Uni.createFrom().nullItem()
        }
        val decision = ctx.getProperty(SYNTHETIC_TAINT_PROPERTY) as? Boolean
            ?: error("the real synthetic trust filter must execute before the loss fixture")
        check(holder.tainted == decision) { "both trusted carriers must agree before fault injection" }
        ctx.removeProperty(SYNTHETIC_TAINT_PROPERTY)
        val held = active.get() ?: return Uni.createFrom().nullItem()
        val assigned = if (decision) held.syntheticAssigned else held.realAssigned
        check(assigned.complete(null)) { "the HTTP pair received a duplicate taint decision" }
        return Uni.createFrom().completionStage(held.resume)
    }

    class HeldRequests {
        val syntheticAssigned = CompletableFuture<Void>()
        val realAssigned = CompletableFuture<Void>()

        // Bound the server-side pause as well as the test's waits, including failure/cleanup paths.
        val resume: CompletableFuture<Response?> = CompletableFuture<Response?>().orTimeout(10, TimeUnit.SECONDS)
    }
}
