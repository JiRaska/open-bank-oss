// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * Attribution recovery from the broker address (#3994).
 *
 * EVERY assertion here checks the VALUE, never `isNotNull()`. That is the point: the defect under
 * test is a *default* — `?: "unknown"` and `?: "UNKNOWN"` — so a non-null assertion passes against
 * the broken code exactly as it does against the fixed code. Non-nullity is what let 76% of the
 * audit trail go unattributed without a single test going red.
 */
class AuditAttributionTest {

    private val repo = mockk<AuditRepository>()

    private val registry = SimpleMeterRegistry()

    private lateinit var consumer: AuditConsumer

    @BeforeEach
    fun setUp() {
        consumer = AuditConsumer().also {
            it.repo = repo
            it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
            it.clock = Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC)
            it.meterRegistry = registry
        }
    }

    @Test
    fun `the ce-type header names the event when the body carries no discriminator`(): Unit = runBlocking {
        // The body of an outbox-relayed event is the bare domain event, with no eventType key at
        // all — while the type rides in the `ce-type` header the whole time. RED against the old
        // consume(payload: String): the header was unreadable, so this stored "UNKNOWN". That is
        // the live 131-row UNKNOWN bucket, all of it money-path payment settlements.
        val entry = capturingSave()

        consumer.consume(
            """{"paymentId":"${UUID.randomUUID()}","status":"SETTLED"}""",
            EventAddress(topic = "openbank.domestic.payment.events", ceType = "domestic.payment.status-changed"),
        )

        assertThat(entry.captured.eventType).isEqualTo("domestic.payment.status-changed")
    }

    @Test
    fun `card-issuance-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's card-issuance-service fix: CardEvent (CardIssued/CardStatusChanged/
        // CardLimitsChanged/CardControlsChanged, serialised via ObjectMapper.writeValueAsString in
        // CardService.outboxMessage) now carries "sourceService" on the base sealed class. Before
        // this, EventAttribution's `openbank.cards.events` -> `card-issuance-service` entry already
        // resolved these rows correctly, but as TOPIC-sourced — and this topic IS in
        // audit-service's consumed-topics list today, so this is a live attribution upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"card.status_changed.v1","cardId":"${UUID.randomUUID()}",""" +
                """"sourceService":"card-issuance-service"}""",
            EventAddress(topic = "openbank.cards.events"),
        )

        assertThat(entry.captured.eventType).isEqualTo("card.status_changed.v1")
        assertThat(entry.captured.sourceService).isEqualTo("card-issuance-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `consent-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's consent-service fix: every ConsentEvents.kt class (ConsentGranted,
        // ConsentRevoked, ConsentExpired, ConsentRejected, SuppressionCreated, SuppressionRevoked)
        // now carries "sourceService" — a serialised data class (ConsentRepositoryImpl.outboxMessage
        // calls objectMapper.writeValueAsString(event) directly, no hand-built map). Before this,
        // EventAttribution's `openbank.consent.events` -> `consent-service` entry already resolved
        // these rows correctly, but as TOPIC-sourced rather than the producer's own claim — and this
        // topic IS in audit-service's consumed-topics list today, so this is a live attribution
        // upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"aggregateId":"${UUID.randomUUID()}","eventType":"ConsentGranted",""" +
                """"sourceService":"consent-service"}""",
            EventAddress(topic = "openbank.consent.events"),
        )

        assertThat(entry.captured.eventType).isEqualTo("ConsentGranted")
        assertThat(entry.captured.sourceService).isEqualTo("consent-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `fx-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's fx-service fix: FxConversionExecuted (FxService.settle, serialised
        // via objectMapper.writeValueAsString) now carries "sourceService". Before this,
        // EventAttribution's `openbank.fx.conversion.completed` -> `fx-service` entry already
        // resolved these rows correctly, but as TOPIC-sourced — and this topic IS in
        // audit-service's consumed-topics list today, so this is a live attribution upgrade.
        // fx-service is a money-path service (rules.yaml: money_path_services).
        val entry = capturingSave()

        consumer.consume(
            """{"conversionId":"${UUID.randomUUID()}","sourceService":"fx-service"}""",
            EventAddress(topic = "openbank.fx.conversion.completed"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("fx-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `sepa-instant's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's sepa-instant fix: KafkaSctInstEventPublisher.publish builds a
        // hand-built map (not a serialised data class), now including "sourceService" alongside
        // "type"/"paymentId"/"occurredAt". Before this, EventAttribution's
        // `openbank.sepa.instant.events` -> `sepa-instant` entry already resolved these rows
        // correctly, but as TOPIC-sourced — and this topic IS in audit-service's consumed-topics
        // list today, so this is a live attribution upgrade. sepa-instant is a money-path service
        // (rules.yaml: money_path_services).
        val entry = capturingSave()

        consumer.consume(
            """{"type":"SctInstPaymentSubmitted","paymentId":"${UUID.randomUUID()}",""" +
                """"sourceService":"sepa-instant"}""",
            EventAddress(topic = "openbank.sepa.instant.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("sepa-instant")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `balance-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's balance-service fix: BalanceEvent now carries "sourceService" on the
        // serialised event (same idiom as its existing actorId/actorType, #3994's original fix) for
        // all six publish sites (BalanceService's hold/credit/debit, LedgerProjectionService, and
        // ValueDateRollScheduler). Before this, EventAttribution's `openbank.balance.events` ->
        // `balance-service` entry already resolved these rows correctly, but as TOPIC-sourced
        // rather than the producer's own claim — and this topic IS in audit-service's
        // consumed-topics list today, so this is a live attribution upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"eventId":"${UUID.randomUUID()}","eventType":"BALANCE_UPDATED",""" +
                """"sourceService":"balance-service"}""",
            EventAddress(topic = "openbank.balance.events"),
        )

        assertThat(entry.captured.eventType).isEqualTo("BALANCE_UPDATED")
        assertThat(entry.captured.sourceService).isEqualTo("balance-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `sepa-payment's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's sepa-payment fix: SepaPaymentCreatedEvent and
        // SepaPaymentStatusChangedEvent (KafkaSepaPaymentEventPublisher.paymentCreatedPayload /
        // statusChangedPayload, both objectMapper.writeValueAsString) now carry "sourceService".
        // Before this, EventAttribution's `openbank.sepa.payment.events` -> `sepa-payment` entry
        // already resolved these rows correctly, but as TOPIC-sourced — and this topic IS in
        // audit-service's consumed-topics list today, so this is a live attribution upgrade.
        // sepa-payment is a money-path service (rules.yaml: money_path_services).
        val entry = capturingSave()

        consumer.consume(
            """{"paymentId":"${UUID.randomUUID()}","status":"SETTLED",""" +
                """"sourceService":"sepa-payment"}""",
            EventAddress(topic = "openbank.sepa.payment.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("sepa-payment")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `dispute-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's dispute-service fix: DisputeService's hand-built outbox payloads
        // (dispute.opened / dispute.resolved / dispute.remediation_requested) and
        // ComplaintService's shared complaintPayload builder now carry "sourceService". Before
        // this, EventAttribution's `openbank.dispute.events` -> `dispute-service` entry already
        // resolved these rows correctly, but as TOPIC-sourced — and this topic IS in
        // audit-service's consumed-topics list today, so this is a live attribution upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"dispute.resolved","disputeId":"${UUID.randomUUID()}",""" +
                """"sourceService":"dispute-service"}""",
            EventAddress(topic = "openbank.dispute.events"),
        )

        assertThat(entry.captured.eventType).isEqualTo("dispute.resolved")
        assertThat(entry.captured.sourceService).isEqualTo("dispute-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `transaction-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's transaction-service fix: TransactionInitiatedEvent /
        // TransactionCompletedEvent / TransactionFailedEvent / TransactionSettledEvent now
        // serialise "sourceService" themselves
        // (openbank-transaction-service/.../domain/event/TransactionEvents.kt +
        // TransactionSettledEvent.kt). Before this, EventAttribution's single
        // "openbank.transactions.transaction.initiated" -> "transaction-service" entry already
        // resolved TransactionInitiatedEvent rows correctly (but as TOPIC-sourced, not the
        // producer's own claim) — and the other three event types, which share that same
        // outbound topic, resolved to the same table entry too, which is coincidentally correct
        // rather than verified per event type. This is unlike #5255's domestic-payment fix:
        // "eventType" ("TransactionInitiated" etc., via DomainEvent) already existed on the wire
        // and is read verbatim by the fraud feature engine
        // (VelocityFeatures.TRANSACTION_INITIATED), so it is unchanged here.
        val entry = capturingSave()

        consumer.consume(
            """{"aggregateId":"${UUID.randomUUID()}","eventType":"TransactionInitiated",""" +
                """"sourceService":"transaction-service"}""",
            EventAddress(topic = "openbank.transactions.transaction.initiated"),
        )

        assertThat(entry.captured.eventType).isEqualTo("TransactionInitiated")
        assertThat(entry.captured.sourceService).isEqualTo("transaction-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `clearing-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's clearing-service fix: ClearingEventPublisherImpl.batchSettledPayload
        // and .itemClearedPayload now put "sourceService" onto the hand-built outbox map for both
        // the batch.settled and item.cleared events. Before this, EventAttribution's
        // `openbank.clearing.batch.event` -> `clearing-service` entry (the real outgoing topic for
        // both event types, via the `clearing-events-out` channel) already resolved these rows
        // correctly, but as TOPIC-sourced rather than the producer's own claim — and audit-service
        // subscribes to this topic today (its `application.yaml` consumed-topics list), so this is
        // a live attribution upgrade, not a forward-looking one.
        val entry = capturingSave()

        consumer.consume(
            """{"batchId":"${UUID.randomUUID()}","eventType":"openbank.clearing.batch.settled",""" +
                """"sourceService":"clearing-service"}""",
            EventAddress(topic = "openbank.clearing.batch.event"),
        )

        assertThat(entry.captured.eventType).isEqualTo("openbank.clearing.batch.settled")
        assertThat(entry.captured.sourceService).isEqualTo("clearing-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `swift-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's swift-service fix: SwiftService.submitToScheme/settle now put
        // "sourceService" onto the hand-built outbox maps for both the SENT and COMPLETED
        // swift.message.status-changed writes. Before this, EventAttribution's
        // `openbank.payments.swift.event` -> `swift-service` entry already resolved these rows
        // correctly, but as TOPIC-sourced rather than the producer's own claim — and audit-service
        // subscribes to this topic today (its `application.yaml` consumed-topics list), so this is
        // a live attribution upgrade, not a forward-looking one.
        val entry = capturingSave()

        consumer.consume(
            """{"swiftMessageId":"${UUID.randomUUID()}","eventType":"swift.message.status-changed",""" +
                """"sourceService":"swift-service"}""",
            EventAddress(topic = "openbank.payments.swift.event"),
        )

        assertThat(entry.captured.eventType).isEqualTo("swift.message.status-changed")
        assertThat(entry.captured.sourceService).isEqualTo("swift-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `sanctions-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's sanctions-service fix: SanctionsRepositoryImpl.eventPayload now
        // serialises "sourceService" onto the outbox payload (the serialised SanctionsCheck
        // aggregate) for both the screening and review events. Before this, EventAttribution's
        // `openbank.sanctions.screening.event` -> `sanctions-service` entry already resolved these
        // rows correctly, but as TOPIC-sourced rather than the producer's own claim — and this
        // topic IS in audit-service's consumed-topics list today, so this is a live attribution
        // upgrade (unlike sca-service's #5337, whose topic audit-service does not consume at all).
        val entry = capturingSave()

        consumer.consume(
            """{"id":"${UUID.randomUUID()}","status":"CLEAR","eventType":"SanctionChecked",""" +
                """"sourceService":"sanctions-service"}""",
            EventAddress(topic = "openbank.sanctions.screening.event"),
        )

        assertThat(entry.captured.eventType).isEqualTo("SanctionChecked")
        assertThat(entry.captured.sourceService).isEqualTo("sanctions-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `account-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's account-service fix: AccountCreatedEvent /
        // AccountStatusChangedEvent / AccountClosedEvent / SavingsWithdrawalApproved now serialise
        // "sourceService" themselves (openbank-account-service/.../AccountEvents.kt). Before this,
        // TopicAttribution's "openbank.accounts.account.created" -> "account-service" entry already
        // resolved this row correctly, but as TOPIC-sourced — a derived claim, not the producer's
        // own. This is unlike #5255's domestic-payment fix: AccountCreatedEvent's "eventType"
        // ("AccountCreated") already existed on the wire via DomainEvent and is read verbatim by
        // balance-/document-/statement-/campaign-service, so it is unchanged here.
        val entry = capturingSave()

        consumer.consume(
            """{"aggregateId":"${UUID.randomUUID()}","eventType":"AccountCreated",""" +
                """"sourceService":"account-service"}""",
            EventAddress(topic = "openbank.accounts.account.created"),
        )

        assertThat(entry.captured.eventType).isEqualTo("AccountCreated")
        assertThat(entry.captured.sourceService).isEqualTo("account-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `kyc-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's kyc-service fix: KycEvents.lifecycle's flat envelope now carries
        // "sourceService" (openbank-kyc-service/.../domain/model/KycEvent.kt) alongside the
        // "eventType" key it already wrote (KYC_CASE_OPENED etc. — already SCREAMING_SNAKE_CASE,
        // unchanged here: it's read verbatim by onboarding-service and party-service). Before
        // this, TopicAttribution's "openbank.kyc.events" -> "kyc-service" entry already resolved
        // this row correctly, but as TOPIC-sourced, not the producer's own claim.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"KYC_CASE_APPROVED","kycCaseId":"${UUID.randomUUID()}",""" +
                """"sourceService":"kyc-service"}""",
            EventAddress(topic = "openbank.kyc.events"),
        )

        assertThat(entry.captured.eventType).isEqualTo("KYC_CASE_APPROVED")
        assertThat(entry.captured.sourceService).isEqualTo("kyc-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `domestic-payment's own eventType and sourceService win, no longer falling to the sentinels`(): Unit =
        runBlocking {
            // Issue #3994's own fix on the producer side: DomesticPaymentCreatedEvent /
            // DomesticPaymentStatusChangedEvent now serialise "eventType" and "sourceService"
            // themselves (openbank-domestic-payment/.../DomesticPaymentEvents.kt), so this no
            // longer needs the ce-type header or the topic table to attribute correctly — the
            // shape below is the real wire payload KafkaDomesticPaymentEventPublisher emits.
            // RED before that producer change: with no such keys in the body this fell through to
            // ce-type/topic (still correct, per the other tests here) but as TOPIC-sourced, not
            // the producer's own EVENT-sourced claim.
            val entry = capturingSave()

            consumer.consume(
                """{"paymentId":"${UUID.randomUUID()}","status":"SETTLED",""" +
                    """"eventType":"DOMESTIC_PAYMENT_STATUS_CHANGED","sourceService":"domestic-payment"}""",
                EventAddress(topic = "openbank.domestic.payment.events", ceType = "domestic.payment.status-changed"),
            )

            assertThat(entry.captured.eventType).isEqualTo("DOMESTIC_PAYMENT_STATUS_CHANGED")
            assertThat(entry.captured.sourceService).isEqualTo("domestic-payment")
            assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
        }

    @Test
    fun `statement-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's statement-service fix: both hand-built JSON payload templates
        // (period.closed.v1 in StatementService.periodClosedEvent, period.close_failed.v1 in
        // CloseOrchestrator.emitCloseFailed) now carry a literal "sourceService" key. Before this,
        // EventAttribution's `openbank.statement.event` -> `statement-service` entry already
        // resolved these rows correctly, but as TOPIC-sourced — and this topic IS in
        // audit-service's consumed-topics list today, so this is a live attribution upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"account.statement.period.closed.v1",""" +
                """"accountId":"${UUID.randomUUID()}","sourceService":"statement-service"}""",
            EventAddress(topic = "openbank.statement.event"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("statement-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `statement-service's restated event is attributed to the producer too`(): Unit = runBlocking {
        // The sweep patched period.closed.v1 and period.close_failed.v1 and missed the third type,
        // account.statement.period.restated.v1, which lives in its own service class
        // (StatementRestatementService) rather than in StatementService/CloseOrchestrator. It also
        // carried no `occurredAt`, so the row was stamped with the consumer's INGEST time and that
        // was recorded as the business time — asserted here as OccurredAtSource.EVENT.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"account.statement.period.restated.v1",""" +
                """"accountId":"${UUID.randomUUID()}","occurredAt":"2026-02-01T02:30:00Z",""" +
                """"sourceService":"statement-service"}""",
            EventAddress(topic = "openbank.statement.event"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("statement-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
        assertThat(entry.captured.occurredAt).isEqualTo(Instant.parse("2026-02-01T02:30:00Z"))
        assertThat(entry.captured.occurredAtSource).isEqualTo(OccurredAtSource.EVENT)
    }

    @Test
    fun `document-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's document-service fix: DocumentGenerated and
        // SignatureCeremonyCompleted (both objectMapper.writeValueAsString) now carry
        // "sourceService". Before this, EventAttribution's `openbank.documents.document.event` ->
        // `document-service` entry already resolved these rows correctly, but as TOPIC-sourced —
        // and this topic IS in audit-service's consumed-topics list today, so this is a live
        // attribution upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"documentId":"${UUID.randomUUID()}","sourceService":"document-service"}""",
            EventAddress(topic = "openbank.documents.document.event"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("document-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `party-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's party-service fix: PartyEvents' flat envelope now carries
        // "sourceService" (openbank-party-service/.../PartyEvent.kt) alongside the "eventType" key
        // it already wrote (PARTY_CREATED etc. — already SCREAMING_SNAKE_CASE, unchanged here).
        // Before this, TopicAttribution's "openbank.party.events" -> "party-service" entry already
        // resolved this row correctly, but as TOPIC-sourced.
        val entry = capturingSave()

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}","eventType":"PARTY_CREATED","sourceService":"party-service"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(entry.captured.eventType).isEqualTo("PARTY_CREATED")
        assertThat(entry.captured.sourceService).isEqualTo("party-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `security-scanner's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's security-scanner fix: IctIncidentService's hand-built
        // ict-incident-events-out payload (its ONLY live event producer — the outbox apparatus
        // was deleted entirely by #4709/#4940) now carries "sourceService". Before this,
        // EventAttribution's `openbank.security.ict.incident` -> `security-scanner` entry
        // already resolved these rows correctly, but as TOPIC-sourced rather than the producer's
        // own claim — and this topic IS in audit-service's consumed-topics list today, so this
        // is a live attribution upgrade.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"ICT_INCIDENT_REPORTED","sourceService":"security-scanner"}""",
            EventAddress(topic = "openbank.security.ict.incident"),
        )

        assertThat(entry.captured.eventType).isEqualTo("ICT_INCIDENT_REPORTED")
        assertThat(entry.captured.sourceService).isEqualTo("security-scanner")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `sca-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256: sca-service's DEVICE_ENROLLED payload (ScaService.kt, a hand-built map
        // serialised onto the outbox) carries "sourceService" — but it was the ONE producer of the
        // 21 audited topics with no wire-payload test, so nothing held the claim. The topic is live:
        // #5369 added openbank.sca.events to this service's consumed-topics list, closing #5338,
        // where SCA device enrollment had never been audited at all. sca-service is money-path.
        //
        // This test is what the sweep's own closure criterion asks for and is the last of the 21
        // producers to get it. The payload shape below is ScaService.kt's verbatim.
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"DEVICE_ENROLLED","deviceId":"${UUID.randomUUID()}",""" +
                """"partyId":"${UUID.randomUUID()}","credentialId":"cred-1","algorithm":"ES256",""" +
                """"occurredAt":"2026-08-09T11:00:00Z","sourceService":"sca-service"}""",
            EventAddress(topic = "openbank.sca.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("sca-service")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
        assertThat(entry.captured.occurredAt).isEqualTo(Instant.parse("2026-08-09T11:00:00Z"))
        assertThat(entry.captured.occurredAtSource).isEqualTo(OccurredAtSource.EVENT)
    }

    @Test
    fun `the topic names the producing service when the producer does not`(): Unit = runBlocking {
        // 1353 of 1774 live rows are here: every producer except customer-edge omits sourceService.
        // RED against the old code, which stored "unknown" with ABSENT-equivalent silence.
        val entry = capturingSave()

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}","eventType":"PARTY_MERGED"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("party-service")
        // The value alone is not enough. A derived attribution stored as though the producer had
        // claimed it is the same class of defect one level up.
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.TOPIC)
    }

    @Test
    fun `lending-service's own sourceService wins, no longer falling to the topic table`(): Unit = runBlocking {
        // Issue #3994/#5256's lending fix: the six remaining LendingService event types
        // (loan.disbursed, loan.interest_accrued, loan.written_off, loan.rescheduled,
        // loan.stage_changed, loan.provisioned — all hand-built payload strings, serialised
        // verbatim via KafkaLendingOutboxEventPublisher onto the single "lending-events-out"
        // channel / "openbank.lending.events" topic) now carry "sourceService", joining the three
        // event types (credit.application.transition, credit.decision.evaluated,
        // credit.loan.transition) that already had it. Before this, EventAttribution's
        // `openbank.lending.events` -> `lending-service` entry already resolved these six event
        // types' rows correctly, but as TOPIC-sourced — and this topic IS in audit-service's
        // consumed-topics list today, so this is a live attribution upgrade, not a forward-looking
        // one. lending-service is a money-path service (rules.yaml: money_path_services).
        //
        // Note the value is "lending", not "lending-service" — matching the literal the three
        // already-fixed event types use (a pre-existing choice this PR preserves for
        // self-consistency across every lending-service event, rather than introducing a second,
        // different self-reported string). That is deliberately NOT what the topic-fallback table
        // would say, which is exactly what this test demonstrates: the producer's own claim wins.
        val entry = capturingSave()

        consumer.consume(
            """{"loanId":"${UUID.randomUUID()}","eventType":"loan.disbursed","sourceService":"lending"}""",
            EventAddress(topic = "openbank.lending.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("lending")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `lending-service's shared-helper event types are attributed to the producer too`(): Unit = runBlocking {
        // #5399 swept this module's nine per-event payload builders and missed loan.withdrawn and
        // loan.accelerated — the only two types built by a shared, parameterised helper
        // (TerminationService.emitDomainEvent), so a reader following the per-event builders never
        // reached them. This is that helper's wire shape (see TerminationServiceTest, which reads
        // the key off the payload the production code actually emits).
        val entry = capturingSave()

        consumer.consume(
            """{"eventType":"loan.withdrawn","loanId":"${UUID.randomUUID()}",""" +
                """"partyId":"${UUID.randomUUID()}","occurredAt":"2026-08-09T12:00:00Z",""" +
                """"sourceService":"lending"}""",
            EventAddress(topic = "openbank.lending.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("lending")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `the producer's own claim wins over the topic, and is marked as the producer's`(): Unit = runBlocking {
        // Body-first ordering: this change can only turn a sentinel into a value. It must never
        // re-attribute a row that is already attributed, or customer-edge's 421 correct rows move.
        val entry = capturingSave()

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}","sourceService":"customer-edge"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("customer-edge")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `an unrecognised topic attributes nothing rather than guessing`(): Unit = runBlocking {
        // The honest failure mode. A convention-based derivation would answer
        // "openbank-<segment>-service" for anything at all, which is how a confident FALSE service
        // name gets chain-hashed into an evidentiary record. Unknown stays unknown.
        val entry = capturingSave()

        consumer.consume(
            """{"accountId":"${UUID.randomUUID()}"}""",
            EventAddress(topic = "com.acme.some.other.topic"),
        )

        assertThat(entry.captured.sourceService).isEqualTo("unknown")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.ABSENT)
    }

    @Test
    fun `a topic-derived row is still counted as a producer gap`(): Unit = runBlocking {
        // Folding TOPIC in with EVENT would make the outstanding producer work vanish from the
        // dashboard the moment this ships — the same silence that let the defect reach 76%.
        coEvery { repo.save(any()) } returns Unit

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(
            registry.counter(
                "openbank.audit.attribution.missing",
                "source_service",
                "party-service",
                "provenance",
                "TOPIC",
            ).count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a message is always acked, even when it cannot be stored`(): Unit = runBlocking {
        // The one way this change could be WORSE than the defect it fixes. Taking Message<String>
        // switches SmallRye from auto-ack to manual ack; an un-acked message stalls the partition
        // and the audit trail stops dead. Unparseable payload = the path most likely to skip it.
        val acked = AtomicBoolean(false)
        val message = Message.of(
            "this is not json",
            Supplier<CompletionStage<Void>> {
                acked.set(true)
                CompletableFuture.completedFuture(null)
            },
        )

        consumer.consume(message)

        assertThat(acked.get()).isTrue()
    }

    @Test
    fun `a message with no broker metadata still stores, on the sentinels`(): Unit = runBlocking {
        // Nothing is rejected: absent metadata is not an error, it is just no extra information.
        val entry = capturingSave()
        val message = Message.of(
            """{"accountId":"${UUID.randomUUID()}"}""",
            Supplier<CompletionStage<Void>> { CompletableFuture.completedFuture(null) },
        )

        consumer.consume(message)

        assertThat(entry.captured.sourceService).isEqualTo("unknown")
        assertThat(entry.captured.sourceServiceSource).isEqualTo(AttributionSource.ABSENT)
    }

    private fun capturingSave() = slot<AuditEntry>().also {
        coEvery { repo.save(capture(it)) } returns Unit
    }
}
