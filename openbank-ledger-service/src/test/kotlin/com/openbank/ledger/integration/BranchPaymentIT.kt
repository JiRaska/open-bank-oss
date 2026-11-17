// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

@QuarkusTest
@io.quarkus.test.junit.TestProfile(BranchPaymentIT.BranchProfile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestSecurity(user = "service-account-openbank-transaction", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "azp", value = "openbank-transaction"),
        Claim(key = "preferred_username", value = "service-account-openbank-transaction"),
    ],
)
class BranchPaymentIT {
    class BranchProfile : io.quarkus.test.junit.QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "openbank.synthetic.trusted-principals" to "service-account-openbank-transaction",
            "test.branch-carrier-loss" to "true",
        )
    }

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbc: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var user: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var password: String

    @Test
    fun `synthetic endpoint refuses an absent trust decision before recording real money`() {
        connection().use { db ->
            val p = Payment()
            register(db, p)
            val response = given().contentType("application/json").body(p.payload())
                .post("/api/v1/journals/branch-payments/synthetic")
            assertThat(response.statusCode).isEqualTo(403)
            assertCounts(db, p, 0, 0)
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["holder", "property"])
    fun `synthetic branch receipt retains either trusted carrier and cannot replay as real`(lost: String) {
        connection().use { db ->
            val p = Payment()
            register(db, p)
            val response = given().contentType("application/json").body(p.payload())
                .header(com.openbank.libs.synthetic.SyntheticTaint.KAFKA_HEADER, "true")
                .header("X-Test-Branch-Carrier-Loss", lost).post("/api/v1/journals/branch-payments/synthetic")
            assertThat(response.statusCode).isEqualTo(200)
            assertCounts(db, p, 2, 2)
            assertThat(
                scalar(
                    db,
                    "SELECT count(*) FROM journal_entries WHERE " +
                        "transaction_id='${p.id}' AND NOT synthetic",
                ),
            )
                .isZero()
            assertThat(
                scalar(
                    db,
                    "SELECT count(*) FROM ledger_outbox WHERE " +
                        "payload::jsonb->>'transactionId'='${p.id}' AND NOT synthetic",
                ),
            ).isZero()
            assertThat(post(p.payload()).statusCode).isEqualTo(409)
            val snapshot = given().queryParam("bankId", p.bank).queryParam("asOf", p.date.toString())
                .get("/api/v1/journals/branch-books")
            assertThat(snapshot.statusCode).isEqualTo(200)
            assertThat(snapshot.jsonPath().getList<String>("lines.branchBookId"))
                .doesNotContain(p.sourceBook.toString(), p.targetBook.toString())
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `one-sided cash flow keeps the branch on both economic legs`(outbound: Boolean) {
        connection().use { db ->
            val p = Payment()
            register(db, p)
            val removed = if (outbound) {
                setOf("targetAccountId", "targetBookId")
            } else {
                setOf("sourceAccountId", "sourceBookId")
            }
            val response = post(p.payload().filterKeys { it !in removed })
            assertThat(response.statusCode).isEqualTo(200)
            assertThat(response.jsonPath().getList<String>("journalIds")).hasSize(1)
            assertCounts(db, p, 1, 1)
            db.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT e.branch_book_id,g.code,sum(CASE WHEN l.side='D' THEN l.amount ELSE -l.amount END)
                    FROM journal_entries e JOIN journal_lines l ON l.journal_id=e.id
                    JOIN gl_accounts g ON g.id=l.gl_account_id WHERE e.transaction_id='${p.id}'
                    GROUP BY e.branch_book_id,g.code
                    """.trimIndent(),
                ).use { rows ->
                    val balances = mutableMapOf<String, String>()
                    while (rows.next()) {
                        assertThat(rows.getObject(1)).isEqualTo(if (outbound) p.sourceBook else p.targetBook)
                        balances[rows.getString(2)] = rows.getBigDecimal(3).stripTrailingZeros().toPlainString()
                    }
                    assertThat(balances).containsExactlyInAnyOrderEntriesOf(
                        if (outbound) {
                            mapOf("2101" to "100", "1101" to "-100")
                        } else {
                            mapOf("1101" to "100", "2101" to "-100")
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `same-book transfer moves both deposit pockets without inter-branch clearing`() {
        connection().use { db ->
            val p = Payment()
            register(db, p)
            val response = post(p.payload(targetBook = p.sourceBook))
            assertThat(response.statusCode).isEqualTo(200)
            assertThat(response.jsonPath().getList<String>("journalIds")).hasSize(1)
            assertCounts(db, p, 1, 2)
            db.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT l.sub_account_id,sum(CASE WHEN l.side='D' THEN l.amount ELSE -l.amount END)
                    FROM journal_entries e JOIN journal_lines l ON l.journal_id=e.id
                    WHERE e.transaction_id='${p.id}' GROUP BY l.sub_account_id
                    """.trimIndent(),
                ).use { rows ->
                    val balances = mutableMapOf<UUID, String>()
                    while (rows.next()) {
                        balances[rows.getObject(1) as UUID] =
                            rows.getBigDecimal(2).stripTrailingZeros().toPlainString()
                    }
                    assertThat(
                        balances,
                    ).containsExactlyInAnyOrderEntriesOf(mapOf(p.source to "100", p.target to "-100"))
                }
            }
        }
    }

    @Test
    fun `failure on second book rolls back both journals and retry posts exactly once`() {
        connection().use { db ->
            val p = Payment()
            register(db, p)
            db.createStatement().use {
                it.execute(
                    """
                CREATE FUNCTION reject_test_branch() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN
                  IF NEW.branch_book_id='${p.targetBook}'::uuid THEN RAISE EXCEPTION 'injected target book outage'; END IF;
                  RETURN NEW; END; ${'$'}${'$'};
                CREATE TRIGGER reject_test_branch BEFORE INSERT ON journal_entries
                FOR EACH ROW EXECUTE FUNCTION reject_test_branch();
                    """.trimIndent(),
                )
            }
            try {
                assertThat(post(p.payload()).statusCode).isBetween(500, 599)
                assertCounts(db, p, 0, 0)
                assertThat(
                    scalar(db, "SELECT count(*) FROM ledger_branch_payments WHERE operation_key='payment:${p.id}'"),
                ).isZero()
            } finally {
                db.createStatement().use {
                    it.execute("DROP TRIGGER reject_test_branch ON journal_entries; DROP FUNCTION reject_test_branch()")
                }
            }
            val recovered = post(p.payload())
            assertThat(recovered.statusCode).isEqualTo(200)
            assertThat(recovered.jsonPath().getList<String>("journalIds")).hasSize(2)
            assertThat(post(p.payload()).jsonPath().getList<String>("journalIds"))
                .isEqualTo(recovered.jsonPath().getList<String>("journalIds"))
            assertThat(post(p.payload(amount = "101.00")).statusCode).isEqualTo(409)
            assertThat(post(p.payload(targetBook = p.sourceBook)).statusCode).isEqualTo(409)
            assertCounts(db, p, 2, 2)
            assertEconomics(db, p)
            val snapshot = given().queryParam("bankId", p.bank).queryParam("asOf", p.date.toString())
                .get("/api/v1/journals/branch-books")
            assertThat(snapshot.statusCode).isEqualTo(200)
            assertThat(snapshot.jsonPath().getBoolean("clearingBalanced")).isTrue()
            assertThat(snapshot.jsonPath().getList<Number>("lines.clearing").sumOf { it.toDouble() }).isZero()
        }
    }

    @Test
    fun `concurrent replay keeps one pair and rejects a foreign bank before money`() {
        connection().use { db ->
            val p = Payment()
            register(db, p)
            assertThat(post(p.payload(bank = UUID.randomUUID())).statusCode).isEqualTo(409)
            assertCounts(db, p, 0, 0)
            val responses = java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
                val a = CompletableFuture.supplyAsync({ post(p.payload()) }, executor)
                val b = CompletableFuture.supplyAsync({ post(p.payload()) }, executor)
                a.get() to b.get()
            }
            val (first, second) = responses
            assertThat(first.statusCode).isEqualTo(200)
            assertThat(second.statusCode).isEqualTo(200)
            assertThat(first.jsonPath().getList<String>("journalIds"))
                .isEqualTo(second.jsonPath().getList<String>("journalIds"))
            assertCounts(db, p, 2, 2)
            assertEconomics(db, p)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-lending"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun `another machine principal cannot create branch payments`() {
        assertThat(post(Payment().payload()).statusCode).isEqualTo(403)
    }

    private fun assertEconomics(db: Connection, p: Payment) {
        db.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT e.branch_book_id,g.code,sum(CASE WHEN l.side='D' THEN l.amount ELSE -l.amount END) balance
                FROM journal_entries e JOIN journal_lines l ON l.journal_id=e.id
                JOIN gl_accounts g ON g.id=l.gl_account_id WHERE e.transaction_id='${p.id}'
                GROUP BY e.branch_book_id,g.code
                """.trimIndent(),
            ).use { rows ->
                val actual = mutableMapOf<String, String>()
                while (rows.next()) {
                    actual["${rows.getObject(1)}:${rows.getString(2)}"] =
                        rows.getBigDecimal(3).stripTrailingZeros().toPlainString()
                }
                assertThat(actual).containsExactlyInAnyOrderEntriesOf(
                    mapOf(
                        "${p.sourceBook}:2101" to "100",
                        "${p.sourceBook}:1291" to "-100",
                        "${p.targetBook}:1291" to "100",
                        "${p.targetBook}:2101" to "-100",
                    ),
                )
            }
        }
    }

    private fun assertCounts(db: Connection, p: Payment, journals: Long, movements: Long) {
        assertThat(
            scalar(db, "SELECT count(*) FROM journal_entries WHERE transaction_id='${p.id}'"),
        ).isEqualTo(journals)
        assertThat(
            scalar(
                db,
                "SELECT count(*) FROM ledger_outbox WHERE event_type='AccountBookedChanged' AND " +
                    "payload::jsonb->>'transactionId'='${p.id}'",
            ),
        ).isEqualTo(movements)
    }
    private fun register(db: Connection, p: Payment) {
        db.createStatement().use {
            it.execute(
                "INSERT INTO ledger_branch_books VALUES ('${p.sourceBook}','${p.bank}','AT'),('${p.targetBook}','${p.bank}','SK')",
            )
        }
    }
    private fun scalar(db: Connection, sql: String): Long = db.createStatement().use { st ->
        st.executeQuery(sql).use {
            it.next()
            it.getLong(1)
        }
    }
    private fun connection() = DriverManager.getConnection(jdbc, user, password)
    private fun post(payload: Map<String, Any>) = given().contentType("application/json").body(payload)
        .post("/api/v1/journals/branch-payments")

    private data class Payment(
        val id: UUID = UUID.randomUUID(),
        val bank: UUID = UUID.fromString("b0000000-0000-0000-0000-000000000380"),
        val sourceBook: UUID = UUID.randomUUID(),
        val targetBook: UUID = UUID.randomUUID(),
        val source: UUID = UUID.randomUUID(),
        val target: UUID = UUID.randomUUID(),
        val date: LocalDate = LocalDate.of(2026, 11, 17),
    ) {
        fun payload(
            amount: String = "100.00",
            bank: UUID = this.bank,
            targetBook: UUID = this.targetBook,
        ): Map<String, Any> = mapOf(
            "idempotencyKey" to "payment:$id", "transactionId" to id, "bankId" to bank,
            "sourceAccountId" to source, "targetAccountId" to target,
            "sourceBookId" to sourceBook, "targetBookId" to targetBook,
            "amount" to amount, "currency" to "EUR", "entryDate" to date.toString(), "valueDate" to date.toString(),
            "createdBy" to UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
    }
}

/** Test-only carrier loss after the real authentication/trust filter, never a synthetic grant. */
@jakarta.enterprise.context.ApplicationScoped
class BranchSyntheticCarrierLoss {
    @ConfigProperty(name = "test.branch-carrier-loss", defaultValue = "false")
    var enabled: Boolean = false

    @jakarta.inject.Inject
    lateinit var holder: com.openbank.libs.web.SyntheticTaintContext

    @org.jboss.resteasy.reactive.server.ServerRequestFilter(priority = jakarta.ws.rs.Priorities.USER + 1)
    fun loseCarrier(ctx: jakarta.ws.rs.container.ContainerRequestContext) {
        if (!enabled || !ctx.uriInfo.path.trim('/').startsWith("api/v1/journals/branch-payments")) return
        when (ctx.getHeaderString("X-Test-Branch-Carrier-Loss")) {
            "holder" -> holder.tainted = false
            "property" -> ctx.removeProperty(com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY)
        }
    }
}
