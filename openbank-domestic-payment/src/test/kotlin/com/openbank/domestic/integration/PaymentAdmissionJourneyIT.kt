// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.domestic.application.port.out.AccountStandingPort
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.OpenAmlCaseCommand
import com.openbank.domestic.application.port.out.PaymentAdmissionWrites
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.SchemeGatewayPort
import com.openbank.domestic.application.port.out.SchemeSubmissionOutcome
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.workflow.AdmissionRun
import com.openbank.domestic.application.workflow.DomesticPaymentActivitiesImpl
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import com.openbank.domestic.application.workflow.PaymentAdmissionActivitiesImpl
import com.openbank.domestic.application.workflow.PaymentHoldPolicy
import com.openbank.domestic.application.workflow.domesticPaymentWorkflowId
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.infrastructure.scheduler.ScreeningRedriveScheduler
import com.openbank.libs.domain.money.Money
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
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import io.vertx.core.Vertx
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
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
// One application boot, one broker and one database serve every journey; a second class would
// start all three again to run the same fakes.
@Suppress("LargeClass")
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
    lateinit var redrive: ScreeningRedriveScheduler

    @Inject
    lateinit var payments: DomesticPaymentRepository

    @Inject
    lateinit var admissionWrites: PaymentAdmissionWrites

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

        // The expiry is read from the row. An hour short of its term the hold is still a hold,
        // however often the run looks.
        shiftHold(payment, PaymentHoldPolicy.EXPIRY.minusHours(1))
        runtime.advance(Duration.ofMinutes(40))
        assertThat(status(payment)).isEqualTo("HELD")
        shiftHold(payment, Duration.ofHours(2))
        runtime.advance(PaymentHoldPolicy.HOLD_CHECK_INTERVAL.plusMinutes(1))

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
    fun `a release whose standing re-run gets no answer is retried, and goes through when standing answers`() {
        val payment = create(creditorName = "REVIEW Shipping")
        awaitStatus(payment, "HELD")

        world.standingDown.set(true)
        kafkaProducer().use { it.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment)) }
        await("the release to have asked for standing and got no answer") { world.standingUnanswered.get() >= 1 }
        runtime.advance(Duration.ofMinutes(20))

        // An outage is not a refusal: the payment is neither sent nor rejected, it is still held,
        // and the release has been tried again.
        assertThat(world.standingUnanswered.get()).isGreaterThanOrEqualTo(2)
        assertThat(status(payment)).isEqualTo("HELD")
        assertThat(
            query("SELECT coalesce(reject_reason, 'none') FROM domestic_payments WHERE payment_id = ?", payment) {
                it.getString(1)
            },
        ).containsExactly("none")
        assertThat(world.submissions(payment)).isZero()

        world.standingDown.set(false)
        runtime.advance(PaymentHoldPolicy.HOLD_CHECK_INTERVAL.plusMinutes(1))
        awaitStatus(payment, "SETTLED")
        assertThat(world.submissions(payment)).isEqualTo(1)
    }

    @Test
    fun `a held payment whose run died gets a new run, keeps its one case request and is still released by its case`() {
        val payment = create(creditorName = "REVIEW Freight")
        awaitStatus(payment, "HELD")
        assertThat(heldEvents(payment)).hasSize(1)
        val asked = world.screenings.get()

        // The run is gone: nothing waits for the case, nothing would expire the hold.
        runtime.terminate(payment)
        kafkaProducer().use { it.decide(payment, "BLOCKED", "attacker-chosen-key") }

        sweep()
        // A second tick finds the run it started and starts no other.
        sweep()

        assertThat(runtime.isRunning(payment)).describedAs("the reconciliation started a run").isTrue()
        assertThat(status(payment)).isEqualTo("HELD")
        // The new run did not evaluate the payment again: its case was requested, the hold is the
        // case's to decide. No control was asked, and no second case request was written.
        runtime.advance(Duration.ofMinutes(2))
        assertThat(world.screenings.get()).isEqualTo(asked)
        assertThat(heldEvents(payment)).hasSize(1)

        kafkaProducer().use { it.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment)) }
        awaitStatus(payment, "SETTLED")
        assertThat(world.submissions(payment)).isEqualTo(1)
    }

    @Test
    fun `a decision that arrives after the hold's run died is not lost - it starts a run and releases the payment`() {
        val payment = create(creditorName = "REVIEW Haulage")
        awaitStatus(payment, "HELD")
        runtime.terminate(payment)

        // No sweep has run: the decision itself finds the payment HELD with no run.
        kafkaProducer().use { it.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment)) }

        awaitStatus(payment, "SETTLED")
        assertThat(world.submissions(payment)).isEqualTo(1)
        assertThat(heldEvents(payment)).hasSize(1)
    }

    @Test
    fun `a recovered hold expires 48 hours after the payment was first held, not after its recovery`() {
        val payment = create(creditorName = "REVIEW Cargo")
        awaitStatus(payment, "HELD")
        runtime.terminate(payment)
        // Held 47 hours ago, as the row says.
        shiftHold(payment, Duration.ofHours(47))

        sweep()
        runtime.advance(Duration.ofMinutes(30))
        assertThat(status(payment)).isEqualTo("HELD")
        // An hour and a bit later by the row, which is where the new run reads it from.
        shiftHold(payment, Duration.ofMinutes(61))
        runtime.advance(PaymentHoldPolicy.HOLD_CHECK_INTERVAL.plusMinutes(1))

        awaitStatus(payment, "REJECTED")
        assertThat(rejectReason(payment)).isEqualTo("AML_HOLD")
        assertThat(world.submissions(payment)).isZero()
    }

    @Test
    fun `with the chain on a RECEIVED payment nothing acts on can be rejected by an operator, never validated`() {
        // A RECEIVED payment whose run has closed: the state a run that failed or gave up leaves
        // behind. Reached by letting a payment run to its end and putting the row back, so that no
        // run is ended by force with an activity in flight (that locks the test server's clock).
        val payment = create()
        awaitStatus(payment, "SETTLED")
        update("UPDATE domestic_payments SET status = 'RECEIVED' WHERE payment_id = ?", payment)
        val reject = """{"targetStatus":"REJECTED","rejectReason":"TECHNICAL_ERROR"}"""
        fun patch(body: String) = RestAssured.given().contentType("application/json").body(body)
            .patch("/api/v1/domestic-payments/$payment/status").then()

        // No run, but the re-drive has attempts left and will start one: admission still owns it.
        patch(reject).statusCode(409)
        update("UPDATE domestic_payments SET redrive_attempts = 5 WHERE payment_id = ?", payment)
        // Now nothing will ever pick it up. It may be refused, and only refused.
        patch("""{"targetStatus":"VALIDATED"}""").statusCode(409)
        assertThat(status(payment)).isEqualTo("RECEIVED")
        patch(reject).statusCode(200)

        assertThat(status(payment)).isEqualTo("REJECTED")
    }

    @Test
    fun `a payment is scored for fraud once however many times it is evaluated`() {
        val account = UUID.randomUUID()
        world.screeningDown.set(true)
        val payment = create(debtorAccountId = account)
        awaitStatus(payment, "HELD")
        repeat(2) { runtime.advance(PaymentHoldPolicy.REEVALUATION_INTERVAL.plusMinutes(1)) }
        world.screeningDown.set(false)
        runtime.advance(PaymentHoldPolicy.REEVALUATION_INTERVAL.plusMinutes(1))
        awaitStatus(payment, "SETTLED")

        // Evaluated at least four times; fraud-service heard of it once and the row kept the answer.
        assertThat(world.scoreCalls[account]?.get()).isEqualTo(1)
        assertThat(
            query("SELECT admission_fraud_verdict FROM domestic_payments WHERE payment_id = ?", payment) {
                it.getString(1)
            },
        ).containsExactly("ALLOW")
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `the customer edge is not told which control rejected a payment, on the payment or in the list`() {
        val account = UUID.randomUUID()
        world.frozen += account
        val payment = create(account, idempotencyKey = "admission-edge-${UUID.randomUUID()}", customerScope = SCOPE)
        awaitStatus(payment, "REJECTED")
        assertThat(
            rejectReason(payment),
        ).describedAs("the row keeps the real reason").isEqualTo("DEBTOR_ACCOUNT_BLOCKED")

        val one = RestAssured.given().get("/api/v1/domestic-payments/$payment").then().statusCode(200)
            .body("status", equalTo("REJECTED"))
            .body("rejectReason", nullValue())
            .body("rejectDetail", nullValue())
            .extract().asString()
        val listed = RestAssured.given().queryParam("debtorAccountId", account).get("/api/v1/domestic-payments")
            .then().statusCode(200).extract().asString()

        assertThat(one).doesNotContain("BLOCKED", "AML", "SANCTIONS", "FRAUD")
        assertThat(listed).contains(payment.toString()).doesNotContain("BLOCKED", "AML", "SANCTIONS", "FRAUD")
    }

    @Test
    fun `an operator reads the real reason a payment was rejected for`() {
        val account = UUID.randomUUID()
        world.frozen += account
        val payment = create(account)
        awaitStatus(payment, "REJECTED")

        RestAssured.given().get("/api/v1/domestic-payments/$payment")
            .then().statusCode(200).body("rejectReason", equalTo("DEBTOR_ACCOUNT_BLOCKED"))
    }

    @Test
    fun `a hold is never released after its expiry - a CLEARED that arrives at hour 60 rejects`() {
        val payment = create(creditorName = "REVIEW Latecomer")
        awaitStatus(payment, "HELD")
        // The run is gone and nobody looked at the payment for 60 hours.
        runtime.terminate(payment)
        shiftHold(payment, Duration.ofHours(60))

        kafkaProducer().use { it.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment)) }

        awaitStatus(payment, "REJECTED")
        assertThat(rejectReason(payment)).isEqualTo("AML_HOLD")
        assertThat(column(payment, "hold_decision")).describedAs("the late decision was not accepted").isEqualTo("none")
        assertThat(world.submissions(payment)).isZero()
    }

    @Test
    fun `a decision accepted before its run died is applied by the run that recovers the hold`() {
        val payment = create(creditorName = "REVIEW Survivor")
        awaitStatus(payment, "HELD")
        // The release cannot complete yet, so the decision is on the row and nowhere else.
        world.standingDown.set(true)
        kafkaProducer().use { it.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment)) }
        await("the decision to be recorded on the payment") { column(payment, "hold_decision") == "RELEASE" }
        await("the release to have been tried") { world.standingUnanswered.get() >= 1 }
        runtime.terminate(payment)
        assertThat(status(payment)).isEqualTo("HELD")

        // Nothing sends the decision again. The row has it.
        world.standingDown.set(false)
        sweep()

        awaitStatus(payment, "SETTLED")
        assertThat(world.submissions(payment)).isEqualTo(1)
    }

    @Test
    fun `a release does not send a payment whose control never answered - it waits, then follows the control`() {
        world.screeningDown.set(true)
        val clean = create(creditorName = "Brno Waterworks")
        val listed = create(creditorName = "SANCTIONED Trading")
        for (payment in listOf(clean, listed)) awaitStatus(payment, "HELD")
        repeat(PaymentHoldPolicy.MAX_REEVALUATIONS) {
            runtime.advance(PaymentHoldPolicy.REEVALUATION_INTERVAL.plusMinutes(1))
        }
        for (payment in listOf(clean, listed)) {
            await("the outage to become the case of $payment") { heldEvents(payment).size == 1 }
        }

        // The reviewer clears both. Neither was ever screened.
        kafkaProducer().use { producer ->
            for (payment in listOf(
                clean,
                listed,
            )) {
                producer.decide(payment, "CLEARED", PaymentHoldPolicy.caseKey(payment))
            }
        }
        for (payment in listOf(clean, listed)) {
            await("the release of $payment to be recorded") { column(payment, "hold_decision") == "RELEASE" }
        }
        runtime.advance(PaymentHoldPolicy.HOLD_CHECK_INTERVAL.plusMinutes(1))

        // Screening is still down: a case decision does not stand in for it.
        for (payment in listOf(clean, listed)) {
            assertThat(status(payment)).isEqualTo("HELD")
            assertThat(world.submissions(payment)).isZero()
        }

        world.screeningDown.set(false)
        runtime.advance(PaymentHoldPolicy.HOLD_CHECK_INTERVAL.plusMinutes(1))

        // It answers now: the clean payment goes, the listed one does not, release or no release.
        awaitStatus(clean, "SETTLED")
        awaitStatus(listed, "REJECTED")
        assertThat(rejectReason(listed)).isEqualTo("SANCTIONS_HIT")
        assertThat(world.submissions(clean)).isEqualTo(1)
        assertThat(world.submissions(listed)).isZero()
    }

    @Test
    fun `the re-drive budget counts runs that were started, and offering a run to an open one spends nothing`() {
        val gate = CompletableDeferred<Unit>()
        world.standingGate.set(gate)
        val received = create()
        await("the admission to be waiting on standing") { world.standingWaiters.get() > 0 }
        val held = create(creditorName = "REVIEW Ledgerworks")
        await("the second payment to be HELD") { status(held) == "HELD" || world.standingWaiters.get() > 1 }

        repeat(3) { sweep() }

        // Both have an open run: offered three times, never re-driven.
        assertThat(column(received, "redrive_attempts")).isEqualTo("0")
        assertThat(column(received, "run_checked_at")).isNotEqualTo("none")
        assertThat(status(received)).isEqualTo("RECEIVED")
        gate.complete(Unit)
        awaitStatus(received, "SETTLED")
        awaitStatus(held, "HELD")
        repeat(2) { sweep() }
        assertThat(column(held, "redrive_attempts")).describedAs("a HELD payment is not re-driven").isEqualTo("0")
        assertThat(column(held, "run_checked_at")).isNotEqualTo("none")
    }

    @Test
    fun `what a payment was scored on cannot be changed by a later write`() {
        val account = UUID.randomUUID()
        val payment = create(debtorAccountId = account, creditorName = "REVIEW Fixtures")
        awaitStatus(payment, "HELD")
        val before = scoredFacts(payment)

        // A lifecycle write handed a payment whose amount, currency, debtor and creditor differ.
        onVertx {
            val stored = checkNotNull(payments.findById(payment))
            val tampered = stored.copy(
                amount = Money.of(BigDecimal("999999.00"), "EUR"),
                debtorAccountId = UUID.randomUUID(),
                debtorAccountNumber = "0000000001",
                creditorAccountNumber = "0000000002",
                creditorBankCode = "9999",
                creditorName = "Somebody Else",
            ).transitionTo(
                DomesticPaymentStatus.REJECTED,
                DomesticRejectReason.TECHNICAL_ERROR,
                clock = Clock.systemUTC(),
            )
            admissionWrites.transition(tampered, emptyList())
        }

        assertThat(status(payment)).isEqualTo("REJECTED")
        // The write moved the status and nothing the fraud score was computed from. If an amend path
        // for these columns ever appears, the kept score (admission_fraud_*) must be bound to them.
        assertThat(scoredFacts(payment)).isEqualTo(before)
    }

    private fun scoredFacts(paymentId: UUID): List<String> = query(
        """
        SELECT amount::text || '|' || currency || '|' || debtor_account_id::text || '|' || debtor_account_number
            || '|' || creditor_account_number || '|' || creditor_bank_code || '|' || creditor_name
        FROM domestic_payments WHERE payment_id = ?
        """.trimIndent(),
        paymentId,
    ) { it.getString(1) }

    /** Moves the start of the payment's hold [by] this much into the past, as if that time had passed. */
    private fun shiftHold(paymentId: UUID, by: Duration) = update(
        "UPDATE domestic_payments SET held_at = held_at - interval '${by.toMinutes()} minutes' WHERE payment_id = ?",
        paymentId,
    )

    private fun column(paymentId: UUID, name: String): String = query(
        "SELECT coalesce($name::text, 'none') FROM domestic_payments WHERE payment_id = ?",
        paymentId,
    ) { it.getString(1) }.single()

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        val context = Vertx.currentContext()
        val onContext = Executor { task -> context.runOnContext { task.run() } }.asCoroutineDispatcher()
        CoroutineScope(onContext).async { block() }.asUni()
    }

    /** One tick of the re-drive and hold-reconciliation sweep, with no minimum age. */
    private fun sweep() {
        val minAge = redrive.minAgeMinutes
        redrive.minAgeMinutes = -1
        try {
            // As the scheduler runs it: every resumption returns to the Vert.x context, also the
            // one after the blocking Temporal start, or the next repository call has no session.
            onVertx { redrive.sweep() }
        } finally {
            redrive.minAgeMinutes = minAge
        }
    }

    private fun update(sql: String, id: UUID) = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
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

    // On a timeout the message carries what the run last did, which is what tells a decision that
    // never arrived from an activity that never completed.
    private fun awaitStatus(paymentId: UUID, expected: String) = await(
        { "payment $paymentId to be $expected (is ${status(paymentId)}; run: ${runtime.lastEvents(paymentId)})" },
    ) { status(paymentId) == expected }

    private fun await(description: String, ready: () -> Boolean) = await({ description }, ready)

    private fun await(description: () -> String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for ${description()}" }
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
    val scoreCalls = ConcurrentHashMap<UUID, AtomicInteger>()
    val screenings = AtomicInteger()
    val standingUnanswered = AtomicInteger()
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
        standingUnanswered.set(0)
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
        world.standingDown.get() -> {
            world.standingUnanswered.incrementAndGet()
            AccountStandingLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN)
        }
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
        world.screenings.incrementAndGet()
        val status = when {
            name.startsWith("REVIEW") -> ScreeningMatchStatus.POTENTIAL_HIT
            name.startsWith("SANCTIONED") -> ScreeningMatchStatus.HIT
            else -> ScreeningMatchStatus.CLEAR
        }
        val score = when (status) {
            ScreeningMatchStatus.CLEAR -> 0.0
            ScreeningMatchStatus.HIT -> 1.0
            else -> 0.8
        }
        return ScreeningResult(name, role, status, score, null)
    }
}

@Alternative
@ApplicationScoped
class FakeFraud(private val world: AdmissionWorld) : FraudScoringPort {
    override suspend fun score(command: FraudScoreCommand): FraudScoreOutcome {
        command.accountId?.let {
            world.scored += it
            world.scoreCalls.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet()
        }
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
        environment = TestWorkflowEnvironment.newInstance()
        // As the worker registrar does: the rail's queue and the admission queue.
        for (queue in listOf("openbank-domestic-payments", AdmissionRun.queue("openbank-domestic-payments"))) {
            val worker = environment.newWorker(queue)
            worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(activities, admission)
        }
        environment.start()
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = environment.workflowClient

    fun advance(duration: Duration) = environment.sleep(duration)

    /**
     * Ends the payment's run the way a failed or terminated run ends: nothing left to signal.
     *
     * Only once the run is parked on its timer. The row turns HELD when the admission activity
     * commits, which is before that activity completes; ending the run with the attempt still in
     * flight leaves the test server's clock locked, and every later `advance` then waits for ever.
     */
    fun terminate(paymentId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!lastEvents(paymentId).endsWith("TIMER_STARTED")) {
            check(System.nanoTime() < deadline) { "Run of $paymentId never parked: ${lastEvents(paymentId)}" }
            Thread.sleep(20)
        }
        environment.workflowClient.newUntypedWorkflowStub(domesticPaymentWorkflowId(paymentId)).terminate("test")
    }

    /** The last history events of the payment's current run, for a failure message. */
    fun lastEvents(paymentId: UUID): String = runCatching {
        environment.workflowClient.fetchHistory(domesticPaymentWorkflowId(paymentId)).events
            .takeLast(8).joinToString(" > ") { it.eventType.name.removePrefix("EVENT_TYPE_") }
    }.getOrElse { "unreadable (${it.message})" }

    fun isRunning(paymentId: UUID): Boolean = environment.workflowClient.workflowServiceStubs.blockingStub()
        .describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(environment.namespace)
                .setExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(domesticPaymentWorkflowId(paymentId)).build(),
                )
                .build(),
        ).workflowExecutionInfo.status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING

    @PreDestroy
    fun stop() {
        if (::environment.isInitialized) environment.close()
    }
}
