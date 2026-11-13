// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.domestic.application.port.out.AccountStandingPort
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.OpenAmlCaseCommand
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.SchemeGatewayPort
import com.openbank.domestic.application.port.out.SchemeSubmissionOutcome
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.workflow.DomesticPaymentActivitiesImpl
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import com.openbank.domestic.application.workflow.PaymentAdmissionActivitiesImpl
import com.openbank.domestic.application.workflow.PaymentAdmissionSwitch
import com.openbank.domestic.application.workflow.PaymentHoldPolicy
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.libs.fincrime.admission.UnavailableCause
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

private const val AML_TOPIC = "openbank.aml.events"
private const val WAIT_SECONDS = 60L
private const val OPERATOR = "00000000-0000-0000-0000-000000000099"
private const val EDGE = "service-account-openbank-edge"
private const val SCOPE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/**
 * ADR-0374 on the domestic rail, proven by effect: a real HTTP request creates the payment, the
 * REAL workflow and the REAL admission and payment activities run against a real Postgres, and a
 * case decision arrives as a real Kafka record through the production consumer. Every assertion is
 * a plain JDBC read of what was committed.
 *
 * Replaced, and only these: the five things on the far side of the rail's ports (account standing,
 * sanctions, fraud, the scheme, settlement) and Temporal's server, which is the in-process test
 * environment so that the 15-minute re-evaluation and the 48-hour expiry can be crossed. The fakes
 * record every call, which is what lets a test say what was NOT asked and what was sent only once.
 */
@QuarkusTest
@TestProfile(PaymentAdmissionJourneyIT.Profile::class)
@QuarkusTestResource(value = FraudHoldJourneyKafkaResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = OPERATOR, roles = ["ROLE_PAYMENTS", "ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "azp", value = "openbank-admin-ui"),
        Claim(key = "sub", value = OPERATOR),
        Claim(key = "preferred_username", value = OPERATOR),
    ],
)
class PaymentAdmissionJourneyIT {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.domestic.admission.enabled" to "true",
            "openbank.domestic.fraud.enforcement-enabled" to "true",
            "openbank.domestic.scheme-submission.enabled" to "true",
            "mp.messaging.incoming.fraud-hold-resolution-in.enabled" to "true",
            "quarkus.otel.sdk.disabled" to "true",
            // No outbox dispatch and no sweeps: the rows stay as the admission wrote them.
            "quarkus.scheduler.enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
            AdmissionJourneyRuntime::class.java,
            FakeStanding::class.java,
            FakeScreening::class.java,
            FakeFraud::class.java,
            FakeScheme::class.java,
            FakeSettlement::class.java,
            FakeAmlCases::class.java,
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var world: AdmissionWorld

    @Inject
    lateinit var runtime: AdmissionJourneyRuntime

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @BeforeEach
    fun reset() = world.reset()

    @Test
    fun `a payment that passes every control is validated, sent once and never held`() {
        val payment = create()

        awaitStatus(payment, "SETTLED")

        assertThat(world.submissions(payment)).isEqualTo(1)
        assertThat(
            statusTrail(payment),
        ).containsExactly("RECEIVED>VALIDATED", "VALIDATED>SENT_TO_CLEARING", "SENT_TO_CLEARING>SETTLED")
        assertThat(heldEvents(payment)).isEmpty()
    }

    @Test
    fun `a payment from a frozen account is rejected, and sanctions and fraud never hear of it`() {
        val account = UUID.randomUUID()
        world.frozen += account
        val payment = create(debtorAccountId = account)

        awaitStatus(payment, "REJECTED")

        assertThat(rejectReason(payment)).isEqualTo("DEBTOR_ACCOUNT_BLOCKED")
        assertThat(world.screened).noneMatch { it.startsWith(payment.toString()) }
        assertThat(world.scored).doesNotContain(account)
        assertThat(world.submissions(payment)).isZero()
        assertThat(heldEvents(payment)).isEmpty()
    }

    @Test
    fun `a sanctions review holds with one case request in the same transaction, and its case releases it once`() {
        val payment = create(creditorName = "REVIEW Trading")

        awaitStatus(payment, "HELD")
        val held = heldEvents(payment)
        assertThat(held).hasSize(1)
        val event = held.single().payload
        assertThat(event.path("eventType").asText()).isEqualTo("PAYMENT_HELD")
        assertThat(event.path("caseKey").asText()).isEqualTo(PaymentHoldPolicy.caseKey(payment))
        assertThat(event.path("debtorPartyId").asText()).isEqualTo(world.owner.toString())
        assertThat(event.path("reasons").map { it.asText() }).containsExactly("SANCTIONS_POTENTIAL_MATCH")
        assertThat(event.toString()).doesNotContain("REVIEW Trading").doesNotContain("Alice Example")
        assertThat(Instant.parse(event.path("expiresAt").asText()))
            .isEqualTo(Instant.parse(event.path("heldAt").asText()).plus(PaymentHoldPolicy.EXPIRY))
        // One transaction wrote the HELD row, its status event and the case request: same xmin.
        val writers = writersOfHold(payment)
        assertThat(
            writers.map {
                it.eventType
            },
        ).containsExactlyInAnyOrder("domestic.payment.status-changed", "domestic.payment.held")
        assertThat(writers.map { it.outboxXmin }.toSet()).containsExactly(writers.first().paymentXmin)
        assertThat(world.submissions(payment)).isZero()

        // The operator status endpoint cannot move it (ADR-0374 D13).
        RestAssured.given().contentType("application/json").body("""{"targetStatus":"VALIDATED"}""")
            .patch("/api/v1/domestic-payments/$payment/status").then().statusCode(409)
        assertThat(status(payment)).isEqualTo("HELD")

        kafkaProducer().use { producer ->
            // A case somebody else opened for the same payment decides nothing.
            producer.decide(payment, "CLEARED", "attacker-chosen-key")
            producer.decide(payment, "CLEARED", "aml-$payment-FRAUD_REVIEW", alert = "FRAUD_REVIEW")
            runtime.advance(Duration.ofMinutes(2))
            assertThat(status(payment)).isEqualTo("HELD")

            producer.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment))
            awaitStatus(payment, "SETTLED")
            // A redelivered decision finds no run and changes nothing.
            producer.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment))
            producer.decide(payment, "BLOCKED", PaymentHoldPolicy.caseKey(payment))
        }
        runtime.advance(Duration.ofMinutes(2))

        assertThat(status(payment)).isEqualTo("SETTLED")
        assertThat(world.submissions(payment)).isEqualTo(1)
        assertThat(heldEvents(payment)).hasSize(1)
        assertThat(statusTrail(payment)).startsWith("RECEIVED>HELD", "HELD>VALIDATED")
    }

    @Test
    fun `a fraud review holds the payment, and a case decision to block rejects it`() {
        val payment = create(amount = FakeFraud.REVIEW_AMOUNT)

        awaitStatus(payment, "HELD")
        assertThat(
            heldEvents(payment).single().payload.path("reasons").map {
                it.asText()
            },
        ).containsExactly("FRAUD_REVIEW")

        kafkaProducer().use { it.decide(payment, "BLOCKED", PaymentHoldPolicy.caseKey(payment)) }

        awaitStatus(payment, "REJECTED")
        assertThat(rejectReason(payment)).isEqualTo("AML_HOLD")
        assertThat(world.submissions(payment)).isZero()
    }

    @Test
    fun `a hold nobody decides expires into a rejection`() {
        val payment = create(creditorName = "REVIEW Imports")
        awaitStatus(payment, "HELD")

        runtime.advance(PaymentHoldPolicy.EXPIRY.minusMinutes(10))
        assertThat(status(payment)).isEqualTo("HELD")
        runtime.advance(Duration.ofMinutes(20))

        awaitStatus(payment, "REJECTED")
        assertThat(rejectReason(payment)).isEqualTo("AML_HOLD")
        assertThat(world.submissions(payment)).isZero()
    }

    @Test
    fun `an unavailable control holds the payment without a case, and the payment proceeds when the control answers`() {
        world.screeningDown.set(true)
        val payment = create()

        awaitStatus(payment, "HELD")
        assertThat(heldEvents(payment)).describedAs("an outage must not open a case").isEmpty()
        assertThat(world.submissions(payment)).isZero()

        world.screeningDown.set(false)
        runtime.advance(PaymentHoldPolicy.REEVALUATION_INTERVAL.plusMinutes(1))

        awaitStatus(payment, "SETTLED")
        assertThat(heldEvents(payment)).isEmpty()
        assertThat(world.submissions(payment)).isEqualTo(1)
    }

    @Test
    fun `standing that cannot be read holds the payment, and an outage that persists becomes exactly one case`() {
        world.standingDown.set(true)
        val payment = create()
        awaitStatus(payment, "HELD")
        assertThat(heldEvents(payment)).isEmpty()

        repeat(PaymentHoldPolicy.MAX_REEVALUATIONS) {
            runtime.advance(PaymentHoldPolicy.REEVALUATION_INTERVAL.plusMinutes(1))
        }

        await("the exhausted re-evaluation to request the case") { heldEvents(payment).size == 1 }
        val event = heldEvents(payment).single().payload
        assertThat(event.path("reasons").map { it.asText() }).containsExactly("UNAVAILABLE_DEPENDENCY_DOWN")
        // Standing was never read, so the event names no party; the case intake resolves it.
        assertThat(event.path("debtorPartyId").isNull).isTrue()
        assertThat(status(payment)).isEqualTo("HELD")
        runtime.advance(Duration.ofHours(1))
        assertThat(heldEvents(payment)).hasSize(1)
        assertThat(world.submissions(payment)).isZero()
    }

    @Test
    fun `a released payment whose account was frozen during the hold is rejected, not sent`() {
        val account = UUID.randomUUID()
        val payment = create(debtorAccountId = account, creditorName = "REVIEW Holdings")
        awaitStatus(payment, "HELD")

        world.frozen += account
        kafkaProducer().use { it.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment)) }

        awaitStatus(payment, "REJECTED")
        assertThat(rejectReason(payment)).isEqualTo("DEBTOR_ACCOUNT_BLOCKED")
        assertThat(world.submissions(payment)).isZero()
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `the customer edge reads a held payment as received, on the payment and on the submission lookup`() {
        val account = UUID.randomUUID()
        val key = "admission-edge-${UUID.randomUUID()}"
        val payment = create(account, creditorName = "REVIEW Partners", idempotencyKey = key, customerScope = SCOPE)
        awaitStatus(payment, "HELD")

        RestAssured.given().get("/api/v1/domestic-payments/$payment")
            .then().statusCode(200).body("status", equalTo("RECEIVED"))
        RestAssured.given().contentType("application/json").header("Idempotency-Key", key)
            .body("""{"debtorAccountId":"$account","customerSubmissionScope":"$SCOPE"}""")
            .post("/api/v1/domestic-payments/submissions/lookup")
            .then().statusCode(200).body("outcome", equalTo("FOUND")).body("status", equalTo("RECEIVED"))
        assertThat(status(payment)).describedAs("the row itself is HELD").isEqualTo("HELD")
    }

    @Test
    fun `with the chain on the status endpoint cannot move a payment that is still RECEIVED`() {
        val gate = CompletableDeferred<Unit>()
        world.standingGate.set(gate)
        val payment = create()
        await("the admission to be waiting on standing") { world.standingWaiters.get() > 0 }
        assertThat(status(payment)).isEqualTo("RECEIVED")

        RestAssured.given().contentType("application/json").body("""{"targetStatus":"VALIDATED"}""")
            .patch("/api/v1/domestic-payments/$payment/status").then().statusCode(409)
        assertThat(status(payment)).isEqualTo("RECEIVED")

        gate.complete(Unit)
        awaitStatus(payment, "SETTLED")
        assertThat(world.submissions(payment)).isEqualTo(1)
    }

    @Test
    fun `an operator reads a held payment as held`() {
        val payment = create(creditorName = "REVIEW Logistics")
        awaitStatus(payment, "HELD")

        RestAssured.given().get("/api/v1/domestic-payments/$payment")
            .then().statusCode(200).body("status", equalTo("HELD"))
    }

    @Test
    fun `the read helpers return nothing for a payment that was never written`() {
        val unknown = UUID.randomUUID()
        assertThat(status(unknown)).isNull()
        assertThat(heldEvents(unknown)).isEmpty()
        assertThat(writersOfHold(unknown)).isEmpty()
    }

    private fun create(
        debtorAccountId: UUID = UUID.randomUUID(),
        creditorName: String = "Brno Utility",
        amount: BigDecimal = BigDecimal("1234.56"),
        idempotencyKey: String = "admission-it-${UUID.randomUUID()}",
        customerScope: String? = null,
    ): UUID {
        val scope = customerScope?.let { """"customerSubmissionScope": "$it",""" }.orEmpty()
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(
                """
                {
                  $scope
                  "debtorAccountId": "$debtorAccountId",
                  "debtorAccountNumber": "1234567890",
                  "debtorBankCode": "0800",
                  "debtorName": "Alice Example",
                  "creditorAccountNumber": "9876543210",
                  "creditorBankCode": "0100",
                  "creditorName": "$creditorName",
                  "amount": $amount,
                  "currency": "CZK",
                  "variableSymbol": "2026001",
                  "specificSymbol": null,
                  "constantSymbol": "0308",
                  "messageForPayee": "Utility bill",
                  "priority": "STANDARD",
                  "transferScope": null,
                  "technicalAccountCode": null,
                  "statementLabel": "Monthly settlement",
                  "endToEndId": "E2E-ADMISSION-IT"
                }
                """.trimIndent(),
            )
            .post("/api/v1/domestic-payments")
        assertThat(response.statusCode).isEqualTo(201)
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun <T> query(sql: String, id: UUID, read: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) read(rows) else null }.toList()
                }
            }
        }

    private fun status(paymentId: UUID): String? =
        query("SELECT status FROM domestic_payments WHERE payment_id = ?", paymentId) { it.getString(1) }.singleOrNull()

    private fun rejectReason(paymentId: UUID): String? =
        query("SELECT reject_reason FROM domestic_payments WHERE payment_id = ?", paymentId) {
            it.getString(1)
        }.single()

    private data class Outbox(val eventType: String, val payload: JsonNode)

    private fun outbox(paymentId: UUID): List<Outbox> = query(
        "SELECT event_type, payload FROM domestic_payment_outbox WHERE aggregate_id = ? ORDER BY created_at, id",
        paymentId,
    ) { Outbox(it.getString(1), mapper.readTree(it.getString(2))) }

    private fun heldEvents(paymentId: UUID) = outbox(paymentId).filter { it.eventType == "domestic.payment.held" }

    private fun statusTrail(paymentId: UUID): List<String> = outbox(paymentId)
        .filter { it.eventType == "domestic.payment.status-changed" }
        .sortedBy { it.payload.path("aggregateRevision").asLong() }
        .map { "${it.payload.path("previousStatus").asText()}>${it.payload.path("newStatus").asText()}" }

    private data class Writer(val paymentXmin: String, val outboxXmin: String, val eventType: String)

    /** The transactions (`xmin`) that wrote the HELD payment row and the outbox rows of that revision. */
    private fun writersOfHold(paymentId: UUID): List<Writer> = query(
        """
        SELECT p.xmin::text, o.xmin::text, o.event_type
        FROM domestic_payments p
        JOIN domestic_payment_outbox o ON o.aggregate_id = p.payment_id
        WHERE p.payment_id = ? AND p.status = 'HELD'
          AND (o.payload::jsonb ->> 'aggregateRevision')::bigint = p.aggregate_revision
        """.trimIndent(),
        paymentId,
    ) { Writer(it.getString(1), it.getString(2), it.getString(3)) }

    private fun awaitStatus(paymentId: UUID, expected: String) =
        await("payment $paymentId to be $expected (is ${status(paymentId)})") { status(paymentId) == expected }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            Thread.sleep(50)
        }
    }

    private fun kafkaProducer() = KafkaProducer(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to false,
        ),
        StringSerializer(),
        StringSerializer(),
    )

    /** The `aml.case.status_changed.v1` body aml-service publishes when a case is decided. */
    private fun KafkaProducer<String, String>.decide(
        paymentId: UUID,
        newStatus: String,
        caseKey: String,
        alert: String = PaymentHoldPolicy.CASE_ALERT_CODE,
    ) {
        val caseId = UUID.randomUUID()
        val body = mapper.writeValueAsString(
            mapOf(
                "caseId" to caseId,
                "partyId" to world.owner,
                "previousStatus" to "UNDER_REVIEW",
                "newStatus" to newStatus,
                "decisionReason" to "Synthetic test decision",
                "decidedBy" to "analyst-2",
                "occurredAt" to Instant.now().toString(),
                "transactionId" to paymentId,
                "alertCode" to alert,
                "idempotencyKey" to caseKey,
            ),
        )
        send(ProducerRecord(AML_TOPIC, 0, caseId.toString(), body)).get(WAIT_SECONDS, TimeUnit.SECONDS)
    }
}

/** What the far side of the rail's ports says and what it was asked, shared by the fakes and the test. */
@ApplicationScoped
class AdmissionWorld {
    val owner: UUID = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a")
    val frozen: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val standingDown = AtomicBoolean(false)
    val screeningDown = AtomicBoolean(false)

    /** When set, the standing answer waits on it: a payment that stays RECEIVED for as long as a test needs. */
    val standingGate = AtomicReference<CompletableDeferred<Unit>?>(null)
    val standingWaiters = AtomicInteger()
    val screened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val scored: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val submitted = ConcurrentHashMap<UUID, AtomicInteger>()

    fun submitted(paymentId: UUID) {
        submitted.computeIfAbsent(paymentId) { AtomicInteger() }.incrementAndGet()
    }

    fun submissions(paymentId: UUID): Int = submitted[paymentId]?.get() ?: 0

    fun reset() {
        frozen.clear()
        standingDown.set(false)
        screeningDown.set(false)
        standingGate.getAndSet(null)?.complete(Unit)
        standingWaiters.set(0)
    }
}

@Alternative
@ApplicationScoped
class FakeStanding(private val world: AdmissionWorld) : AccountStandingPort {
    override suspend fun debtorStanding(accountId: UUID): AccountStandingLookup {
        world.standingGate.get()?.let { gate ->
            world.standingWaiters.incrementAndGet()
            gate.await()
        }
        return answer(accountId)
    }

    private fun answer(accountId: UUID): AccountStandingLookup = when {
        world.standingDown.get() -> AccountStandingLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
        accountId in world.frozen -> AccountStandingLookup.Known(
            world.owner,
            mayDebit = false,
            reason = "ACCOUNT_FROZEN",
        )
        else -> AccountStandingLookup.Known(world.owner, mayDebit = true, reason = null)
    }
}

@Alternative
@ApplicationScoped
class FakeScreening(private val world: AdmissionWorld) : SanctionsScreeningPort {
    override suspend fun screen(name: String, role: ScreeningRole, idempotencyKey: String): ScreeningResult {
        if (world.screeningDown.get()) throw ScreeningUnavailableException(IllegalStateException("sanctions is down"))
        world.screened += idempotencyKey
        val status = if (name.startsWith("REVIEW")) ScreeningMatchStatus.POTENTIAL_HIT else ScreeningMatchStatus.CLEAR
        return ScreeningResult(name, role, status, if (status == ScreeningMatchStatus.CLEAR) 0.0 else 0.8, null)
    }
}

@Alternative
@ApplicationScoped
class FakeFraud(private val world: AdmissionWorld) : FraudScoringPort {
    override suspend fun score(command: FraudScoreCommand): FraudScoreOutcome {
        command.accountId?.let { world.scored += it }
        val verdict = if (command.amount.compareTo(REVIEW_AMOUNT) == 0) FraudVerdict.REVIEW else FraudVerdict.ALLOW
        return FraudScoreOutcome(verdict, 10, "rules-it", emptyList())
    }

    companion object {
        val REVIEW_AMOUNT: BigDecimal = BigDecimal("666.00")
    }
}

@Alternative
@ApplicationScoped
class FakeScheme(private val world: AdmissionWorld) : SchemeGatewayPort {
    override suspend fun submit(payment: DomesticPayment): SchemeSubmissionOutcome {
        world.submitted(payment.id)
        return SchemeSubmissionOutcome(accepted = true, reasonCode = null)
    }
}

@Alternative
@ApplicationScoped
class FakeSettlement : SettlementPort {
    override suspend fun settle(payment: DomesticPayment) =
        SettlementOutcome(settled = true, transactionId = UUID.randomUUID())
}

@Alternative
@ApplicationScoped
class FakeAmlCases : AmlCasePort {
    override suspend fun openCase(command: OpenAmlCaseCommand) = Unit
}

/** The REAL workflow and the REAL activity beans on Temporal's in-process, time-skipping server. */
@Alternative
@ApplicationScoped
class AdmissionJourneyRuntime(
    private val activities: DomesticPaymentActivitiesImpl,
    private val admission: PaymentAdmissionActivitiesImpl,
) {
    private lateinit var environment: TestWorkflowEnvironment

    @PostConstruct
    fun start() {
        PaymentAdmissionSwitch.enabled = true
        environment = TestWorkflowEnvironment.newInstance()
        val worker = environment.newWorker("openbank-domestic-payments")
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities, admission)
        environment.start()
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = environment.workflowClient

    fun advance(duration: Duration) = environment.sleep(duration)

    @PreDestroy
    fun stop() {
        PaymentAdmissionSwitch.enabled = false
        if (::environment.isInitialized) environment.close()
    }
}
