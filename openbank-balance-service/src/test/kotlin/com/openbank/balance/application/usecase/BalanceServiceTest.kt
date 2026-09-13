// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.`in`.CreditAccountCommand
import com.openbank.balance.application.port.`in`.DebitAccountCommand
import com.openbank.balance.application.port.`in`.InitializeBalanceCommand
import com.openbank.balance.application.port.`in`.PlaceHoldCommand
import com.openbank.balance.application.port.`in`.SetOverdraftLimitCommand
import com.openbank.balance.application.port.out.BalanceMovementPort
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.application.port.out.MovementOutcome
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.domain.model.BalanceEventActors
import com.openbank.balance.domain.model.BalanceEventType
import com.openbank.balance.domain.model.BalanceHold
import com.openbank.libs.domain.event.EventActor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class BalanceServiceTest {

    private fun sampleBalance(booked: String = "100.00", available: String = "100.00") = Balance(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        currency = "CZK",
        bookedAmount = BigDecimal(booked),
        availableAmount = BigDecimal(available),
        reservedAmount = BigDecimal.ZERO,
        pendingAmount = BigDecimal.ZERO,
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        version = 0L,
    )

    @Test
    fun `a retried placeHold with the same referenceId replays the original hold with no second reservation`(): Unit =
        runBlocking {
            // ADR-0287 / #8351: referenceId names one durable business fact, so a retry must replay
            // the original hold — no second reservation, no second HOLD_PLACED event.
            val balance = sampleBalance()
            val balanceRepo = FakeBalanceRepository(balance)
            val holdRepo = FakeHoldRepository()
            val movement = FakeBalanceMovementPort(balanceRepo)
            val service = BalanceService(balanceRepo, holdRepo, movement)
            val cmd =
                PlaceHoldCommand(
                    balance.accountId,
                    BigDecimal("20.00"),
                    "CZK",
                    "card auth",
                    "ref-1",
                    ttlSeconds = 60,
                )

            val first = service.placeHold(cmd)
            val replayed = service.placeHold(cmd)

            assertEquals(first.id, replayed.id)
            assertEquals(1, holdRepo.savedWithEvent.size)
        }

    @Test
    fun `placeHold persists hold and emits HOLD_PLACED event`(): Unit = runBlocking {
        val balance = sampleBalance()
        val balanceRepo = FakeBalanceRepository(balance)
        val holdRepo = FakeHoldRepository()
        val movement = FakeBalanceMovementPort(balanceRepo)
        val service = BalanceService(balanceRepo, holdRepo, movement)

        val hold = service.placeHold(
            PlaceHoldCommand(balance.accountId, BigDecimal("20.00"), "CZK", "card auth", "ref-1", ttlSeconds = 60),
        )

        assertEquals(0, hold.amount.compareTo(BigDecimal("20.00")))
        // The reservation, the hold row and the event travel together now (#8510): the service no
        // longer touches balanceRepo.update / holdRepo.save / a publisher separately.
        assertEquals(0, holdRepo.savedWithEvent.single().second.reservedAmount.compareTo(BigDecimal("20.00")))
        assertEquals(BalanceEventType.HOLD_PLACED, holdRepo.savedWithEvent.single().third.eventType)
        assertTrue(balanceRepo.updated.isEmpty())
    }

    @Test
    fun `debit wraps insufficient funds as domain exception`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "10.00", available = "10.00"))
        val service = BalanceService(
            balanceRepo,
            FakeHoldRepository(),
            FakeBalanceMovementPort(balanceRepo),
        )

        assertThrows<InsufficientFundsException> {
            runBlocking {
                service.debit(DebitAccountCommand(balanceRepo.seed.accountId, BigDecimal("20.00"), "CZK", "ref-2"))
            }
        }
    }

    @Test
    fun `credit applies once and emits a BALANCE_UPDATED event`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "100.00", available = "100.00"))
        val movement = FakeBalanceMovementPort(balanceRepo)
        val service = BalanceService(balanceRepo, FakeHoldRepository(), movement)

        val result = service.credit(
            CreditAccountCommand(balanceRepo.seed.accountId, BigDecimal("40.00"), "CZK", "pay-1"),
        )

        assertEquals(0, result.bookedAmount.compareTo(BigDecimal("140.00")))
        // The event is the port's job now (#8510): the fake records what the real impl would write
        // to balance_outbox inside the movement transaction.
        assertEquals(1, movement.events.size)
        assertEquals(BalanceEventType.BALANCE_UPDATED, movement.events.single().eventType)
    }

    @Test
    fun `a duplicate credit referenceId does not double-apply and emits no second event`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "100.00", available = "100.00"))
        val movement = FakeBalanceMovementPort(balanceRepo)
        val service = BalanceService(balanceRepo, FakeHoldRepository(), movement)
        val acct = balanceRepo.seed.accountId

        val first = service.credit(CreditAccountCommand(acct, BigDecimal("40.00"), "CZK", "pay-1"))
        val replay = service.credit(CreditAccountCommand(acct, BigDecimal("40.00"), "CZK", "pay-1")) // same ref

        // Applied exactly once: 100 + 40 = 140 on both the first call and the idempotent replay.
        assertEquals(0, first.bookedAmount.compareTo(BigDecimal("140.00")))
        assertEquals(0, replay.bookedAmount.compareTo(BigDecimal("140.00")))
        // Only the first application writes an event — a replay must not double-count downstream.
        assertEquals(1, movement.events.size)
    }

    @Test
    fun `a duplicate debit referenceId does not double-apply`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "100.00", available = "100.00"))
        val movement = FakeBalanceMovementPort(balanceRepo)
        val service = BalanceService(balanceRepo, FakeHoldRepository(), movement)
        val acct = balanceRepo.seed.accountId

        service.debit(DebitAccountCommand(acct, BigDecimal("30.00"), "CZK", "wd-1"))
        val replay = service.debit(DebitAccountCommand(acct, BigDecimal("30.00"), "CZK", "wd-1")) // same ref

        assertEquals(0, replay.bookedAmount.compareTo(BigDecimal("70.00"))) // 100 - 30, applied once
        assertEquals(1, movement.events.size)
        // The debit event keeps the pre-#8510 wire shape: the amount is NEGATED on the wire.
        assertEquals(0, movement.events.single().amount!!.compareTo(BigDecimal("-30.00")))
    }

    @Test
    fun `getBalance without asOf returns the live current balance`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "100000.00", available = "100000.00"))
        balanceRepo.futureDelta = BigDecimal("777.00") // must be ignored when asOf is absent
        val service = BalanceService(
            balanceRepo,
            FakeHoldRepository(),
            FakeBalanceMovementPort(balanceRepo),
        )

        val result = service.getBalance(
            com.openbank.balance.application.port.`in`.GetBalanceQuery(balanceRepo.seed.accountId, "CZK"),
        )

        assertEquals(0, result.bookedAmount.compareTo(BigDecimal("100000.00")))
    }

    @Test
    fun `getBalance with asOf rewinds booked by deltas booked after the date`(): Unit = runBlocking {
        // Current booked 100000; 30000 was booked AFTER asOf → balance as of that date was 70000.
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "100000.00", available = "100000.00"))
        balanceRepo.futureDelta = BigDecimal("30000.00")
        val service = BalanceService(
            balanceRepo,
            FakeHoldRepository(),
            FakeBalanceMovementPort(balanceRepo),
        )

        val result = service.getBalance(
            com.openbank.balance.application.port.`in`.GetBalanceQuery(
                balanceRepo.seed.accountId,
                "CZK",
                java.time.LocalDate.parse("2026-06-07"),
            ),
        )

        assertEquals(0, result.bookedAmount.compareTo(BigDecimal("70000.00")))
        // Point-in-time snapshot reports available == booked and carries no reservation/pending.
        assertEquals(0, result.availableAmount.compareTo(BigDecimal("70000.00")))
        assertEquals(0, result.reservedAmount.compareTo(BigDecimal.ZERO))
        assertEquals(0, result.pendingAmount.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `getBalance with asOf and no later movements equals current booked`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance(booked = "100000.00", available = "100000.00"))
        // futureDelta defaults to ZERO (projection empty / no activity after asOf)
        val service = BalanceService(
            balanceRepo,
            FakeHoldRepository(),
            FakeBalanceMovementPort(balanceRepo),
        )

        val result = service.getBalance(
            com.openbank.balance.application.port.`in`.GetBalanceQuery(
                balanceRepo.seed.accountId,
                "CZK",
                java.time.LocalDate.parse("2026-06-07"),
            ),
        )

        assertEquals(0, result.bookedAmount.compareTo(BigDecimal("100000.00")))
    }

    @Test
    fun `initializeBalance returns existing balance without overwriting`(): Unit = runBlocking {
        val existing = sampleBalance()
        val balanceRepo = FakeBalanceRepository(existing)
        val service = BalanceService(
            balanceRepo,
            FakeHoldRepository(),
            FakeBalanceMovementPort(balanceRepo),
        )

        val result = service.initializeBalance(InitializeBalanceCommand(existing.accountId, "CZK", BigDecimal("5.00")))

        assertEquals(existing, result)
        assertTrue(balanceRepo.saved.isEmpty())
    }

    @Test
    fun `setOverdraftLimit updates arrangedOverdraftLimit on an existing balance`(): Unit = runBlocking {
        val balance = sampleBalance()
        val balanceRepo = FakeBalanceRepository(balance)
        val service = BalanceService(
            balanceRepo,
            FakeHoldRepository(),
            FakeBalanceMovementPort(balanceRepo),
        )

        val result = service.setOverdraftLimit(
            SetOverdraftLimitCommand(balance.accountId, "CZK", BigDecimal("5000.00")),
        )

        assertEquals(0, result.arrangedOverdraftLimit.compareTo(BigDecimal("5000.00")))
        assertTrue(balanceRepo.updated.isNotEmpty())
    }

    @Test
    fun `setOverdraftLimit throws BalanceNotFoundException when balance does not exist`(): Unit = runBlocking {
        val service = BalanceService(
            FakeBalanceRepository(),
            FakeHoldRepository(),
            FakeBalanceMovementPort(FakeBalanceRepository()),
        )

        assertThrows<BalanceNotFoundException> {
            service.setOverdraftLimit(SetOverdraftLimitCommand(UUID.randomUUID(), "EUR", BigDecimal.ZERO))
        }
    }

    /**
     * #3994 — red against `origin/main`, where `BalanceEvent` had no `actorId` at all and the three
     * balance event types accounted for 542 of the 1341 unattributed rows in the live audit trail.
     *
     * Asserts the EXACT id, not that one is present: `assertNotNull` would pass against the empty
     * string, against `"null"` (the four-character string #4307 removed from `actor_id`) and
     * against a mechanism copied from the wrong call site, and the mechanism segment is the only
     * part that makes the value worth storing.
     */
    @Test
    fun `an inbound hold names the balance API as its system origin - no person placed it`(): Unit = runBlocking {
        val balance = sampleBalance()
        val balanceRepo = FakeBalanceRepository(balance)
        val holdRepo = FakeHoldRepository()
        val service = BalanceService(
            balanceRepo,
            holdRepo,
            FakeBalanceMovementPort(balanceRepo),
        )

        service.placeHold(
            PlaceHoldCommand(balance.accountId, BigDecimal("20.00"), "CZK", "card auth", "ref-1", ttlSeconds = 60),
        )

        val event = holdRepo.savedWithEvent.single().third
        assertEquals("system:balance-service:balance-api", event.actorId)
        assertEquals("SYSTEM", event.actorType)
    }

    @Test
    fun `a credit carries the same system origin`(): Unit = runBlocking {
        val balanceRepo = FakeBalanceRepository(sampleBalance())
        val movement = FakeBalanceMovementPort(balanceRepo)
        val service = BalanceService(balanceRepo, FakeHoldRepository(), movement)

        service.credit(CreditAccountCommand(balanceRepo.seed.accountId, BigDecimal("5.00"), "CZK", "ref-credit"))

        assertEquals("system:balance-service:balance-api", movement.events.single().actorId)
        assertEquals("SYSTEM", movement.events.single().actorType)
    }

    /**
     * The wire, not the object (#3994).
     *
     * `BalanceEvent` is a SERIALISED data class — the outbox payload is built by
     * `ObjectMapper.writeValueAsString` (#8510: same mapper call the retired direct emitter used,
     * so the bytes are unchanged), so the JSON keys are Kotlin property names and no string
     * literal `"actorId"` exists anywhere in this module. A `command grep '"actorId"'` over
     * balance-service therefore reports nothing whether the field is on the wire or not, which is
     * exactly how a survey by grep misses half this fleet's event fields. Only serialising can
     * tell, so this test serialises.
     */
    @Test
    fun `the serialised event puts the actor on the wire under the key AuditConsumer reads`() {
        // findAndRegisterModules mirrors the Quarkus-configured mapper the real publisher uses.
        val mapper = ObjectMapper().findAndRegisterModules()
        val json: JsonNode = mapper.readTree(
            mapper.writeValueAsString(
                BalanceEvent(
                    eventId = UUID.randomUUID(),
                    eventType = BalanceEventType.BALANCE_UPDATED,
                    accountId = UUID.randomUUID(),
                    currency = "CZK",
                    amount = BigDecimal("1.00"),
                    bookedAmount = BigDecimal("1.00"),
                    availableAmount = BigDecimal("1.00"),
                    reservedAmount = BigDecimal.ZERO,
                    occurredAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    actorId = BalanceEventActors.LEDGER_PROJECTION,
                    actorType = EventActor.TYPE_SYSTEM,
                ),
            ),
        )

        assertEquals("system:balance-service:ledger-projection", json.get("actorId").asText())
        assertEquals("SYSTEM", json.get("actorType").asText())
    }

    private class FakeBalanceRepository(initial: Balance? = null) : BalanceRepository {
        var seed: Balance = initial ?: Balance(
            id = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            currency = "CZK",
            bookedAmount = BigDecimal("100.00"),
            availableAmount = BigDecimal("100.00"),
            reservedAmount = BigDecimal.ZERO,
            pendingAmount = BigDecimal.ZERO,
            updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            version = 0L,
        )
        val saved = mutableListOf<Balance>()
        val updated = mutableListOf<Balance>()

        override suspend fun findByAccountIdAndCurrency(accountId: UUID, currency: String): Balance? =
            seed.takeIf { it.accountId == accountId && it.currency == currency }

        override suspend fun findAllByAccountId(accountId: UUID): List<Balance> = listOf(seed).filter {
            it.accountId ==
                accountId
        }
        override suspend fun save(balance: Balance): Balance = balance.also {
            seed = it
            saved += it
        }
        override suspend fun update(balance: Balance): Balance = balance.also {
            seed = it
            updated += it
        }
        override suspend fun sumBookedByCurrency(): Map<String, BigDecimal> = mapOf(seed.currency to seed.bookedAmount)

        /** Booked delta the test wants reported as "applied after asOf" (defaults to none). */
        var futureDelta: BigDecimal = BigDecimal.ZERO

        // Value-date-correct sum = current total less the future-value-dated portion (ADR-0178).
        override suspend fun sumBookedByCurrencyAsOf(asOf: java.time.LocalDate): Map<String, BigDecimal> =
            mapOf(seed.currency to (seed.bookedAmount - futureDelta))

        override suspend fun sumBookedDeltaAfter(
            accountId: UUID,
            currency: String,
            asOf: java.time.LocalDate,
        ): BigDecimal = futureDelta

        override suspend fun sumFutureValueDatedByCurrency(asOf: java.time.LocalDate): Map<String, BigDecimal> =
            mapOf(seed.currency to futureDelta)

        /** Not-yet-effective CREDIT tail the test wants reported (ADR-0178 Phase 2, #1745). */
        var notYetEffectiveCredit: BigDecimal = BigDecimal.ZERO

        override suspend fun sumNotYetEffectiveCredit(
            accountId: UUID,
            currency: String,
            asOf: java.time.LocalDate,
        ): BigDecimal = notYetEffectiveCredit

        override suspend fun findCreditsMaturingOn(
            date: java.time.LocalDate,
        ): List<com.openbank.balance.application.port.out.AccountCurrency> = emptyList()
    }

    /**
     * In-memory idempotent movement port: applies the domain rule to the repo's seed once per
     * (account, currency, referenceId, operation); a duplicate returns the current balance with
     * applied=false and mutates nothing — mirroring the real adapter's dedup contract. Since #8510
     * the port also owns the event: the fake records exactly what the real impl persists into
     * `balance_outbox` inside the movement transaction (post-mutation figures, negated debit
     * amount, the caller-supplied actor), so the use-case tests still see the event contract.
     */
    private class FakeBalanceMovementPort(private val repo: FakeBalanceRepository) : BalanceMovementPort {
        private val applied = mutableSetOf<String>()
        val events = mutableListOf<BalanceEvent>()

        override suspend fun applyCredit(
            accountId: UUID,
            currency: String,
            referenceId: String,
            amount: BigDecimal,
            actorId: String,
        ) = apply(accountId, currency, referenceId, "CREDIT", amount, actorId) { it.applyCredit(amount) }

        override suspend fun applyDebit(
            accountId: UUID,
            currency: String,
            referenceId: String,
            amount: BigDecimal,
            actorId: String,
        ) = apply(accountId, currency, referenceId, "DEBIT", amount, actorId) { it.applyDebit(amount) }

        @Suppress("LongParameterList")
        private fun apply(
            accountId: UUID,
            currency: String,
            referenceId: String,
            op: String,
            amount: BigDecimal,
            actorId: String,
            mutate: (Balance) -> Balance,
        ): MovementOutcome {
            val current = repo.seed.takeIf { it.accountId == accountId && it.currency == currency }
                ?: throw BalanceNotFoundException("Balance not found for account=$accountId")
            val key = "$accountId|$currency|$referenceId|$op"
            if (key in applied) return MovementOutcome(current, applied = false)
            val updated = mutate(current) // may throw IllegalArgumentException (overdraft) before marking
            repo.seed = updated
            applied += key
            events += BalanceEvent(
                eventId = UUID.randomUUID(),
                eventType = BalanceEventType.BALANCE_UPDATED,
                accountId = accountId,
                currency = currency,
                amount = if (op == "DEBIT") amount.negate() else amount,
                bookedAmount = updated.bookedAmount,
                availableAmount = updated.availableAmount,
                reservedAmount = updated.reservedAmount,
                occurredAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                actorId = actorId,
                actorType = EventActor.TYPE_SYSTEM,
                sourceService = "balance-service",
            )
            return MovementOutcome(updated, applied = true)
        }
    }

    private class FakeHoldRepository : HoldRepository {
        val saved = mutableListOf<BalanceHold>()

        /** (hold, balance, event) triples the service handed to the transactional methods (#8510). */
        val savedWithEvent = mutableListOf<Triple<BalanceHold, Balance, BalanceEvent>>()
        val releasedWithEvent = mutableListOf<Triple<BalanceHold, Balance, BalanceEvent>>()

        override suspend fun findById(holdId: UUID): BalanceHold? = saved.firstOrNull { it.id == holdId }
        override suspend fun findActiveByAccountId(accountId: UUID): List<BalanceHold> = saved.filter {
            it.accountId ==
                accountId &&
                it.releasedAt == null
        }
        override suspend fun findActiveByReferenceId(referenceId: String): List<BalanceHold> = saved.filter {
            it.referenceId ==
                referenceId &&
                it.releasedAt == null
        }
        override suspend fun findByNaturalKey(accountId: UUID, currency: String, referenceId: String): BalanceHold? =
            saved.firstOrNull {
                it.accountId == accountId && it.currency == currency && it.referenceId == referenceId
            }
        override suspend fun save(hold: BalanceHold): BalanceHold = hold.also { saved += it }
        override suspend fun update(hold: BalanceHold): BalanceHold = hold
        override suspend fun saveWithEvent(hold: BalanceHold, balance: Balance, event: BalanceEvent): BalanceHold =
            hold.also {
                saved += it
                savedWithEvent += Triple(hold, balance, event)
            }
        override suspend fun releaseWithEvent(hold: BalanceHold, balance: Balance, event: BalanceEvent): BalanceHold =
            hold.also {
                releasedWithEvent += Triple(hold, balance, event)
            }
    }
}
