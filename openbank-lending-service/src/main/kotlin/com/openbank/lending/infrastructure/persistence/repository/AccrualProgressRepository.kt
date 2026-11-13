// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.AccrualIntent
import com.openbank.lending.application.port.out.AccrualPostingReceipt
import com.openbank.lending.application.port.out.AccrualProgressPort
import com.openbank.lending.application.port.out.PreparedAccrualPosting
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** A committed intent precedes HTTP; all local consequences of its receipt share one transaction. */
@ApplicationScoped
class AccrualProgressRepository(private val sf: Mutiny.SessionFactory, private val mapper: ObjectMapper) :
    AccrualProgressPort {
    override fun begin(
        installment: LoanInstallment,
        businessDate: LocalDate,
        prepare: () -> PreparedAccrualPosting?,
    ): Uni<AccrualIntent> = sf.withTransaction { s ->
        lockInstallment(s, installment.id).flatMap { source ->
            require(source[0] == installment.loanId.value && (source[1] as Number).toInt() == installment.number)
            require(
                date(source[2]) == installment.dueDate &&
                    source[SRC_CURRENCY].toString().trim() == installment.interest.currency.code,
            )
            require((source[SRC_AMOUNT] as BigDecimal).compareTo(installment.interest.amount) == 0)
            load(s, installment.id).flatMap { existing ->
                if (existing != null) {
                    verifySource(existing, source)
                    Uni.createFrom().item(existing.intent(installment.id))
                } else {
                    require(source[SRC_ACCRUED] == false) { "Accrued installment has no durable receipt" }
                    val posting = prepare()
                    val reference = "loan:${source[0]}:inst:${source[1]}:accrual"
                    require((posting == null) == ((source[SRC_AMOUNT] as BigDecimal).signum() == 0))
                    require(
                        posting == null ||
                            posting.reference == reference &&
                            posting.transactionId == transactionId(reference),
                    )
                    s.createNativeQuery<Int>(INSERT)
                        .setParameter("id", installment.id).setParameter("loan", source[0])
                        .setParameter("number", source[1]).setParameter("due", date(source[2]))
                        .setParameter("amount", source[SRC_AMOUNT])
                        .setParameter("currency", source[SRC_CURRENCY].toString().trim())
                        .setParameter("day", businessDate).setParameter("reference", reference)
                        .setParameter("transaction", transactionId(reference))
                        .setParameter("requested", posting?.accountingDate)
                        .setParameter("request", posting?.requestJson).executeUpdate()
                        .replaceWith(AccrualIntent(installment.id, businessDate, posting, false))
                }
            }
        }
    }

    override fun complete(intent: AccrualIntent, receipt: AccrualPostingReceipt?, at: OffsetDateTime): Uni<Unit> =
        sf.withTransaction { s ->
            // Same parent-before-child order as FK cascade erasure. Never accept caller economic facts.
            lockInstallment(s, intent.installmentId).flatMap { source ->
                load(s, intent.installmentId).flatMap { stored ->
                    requireNotNull(stored) { "Accrual intent was erased" }
                    verifySource(stored, source)
                    require(stored.intent(intent.installmentId).copy(completed = intent.completed) == intent) {
                        "Accrual intent changed"
                    }
                    validateReceipt(stored, receipt)
                    if (stored.completedAt != null) {
                        require(stored.journalId == receipt?.journalId && stored.bookedDate == receipt?.accountingDate)
                        Uni.createFrom().item(Unit)
                    } else {
                        finish(s, intent.installmentId, stored, receipt, at)
                    }
                }
            }
        }

    private fun finish(
        s: Mutiny.Session,
        id: UUID,
        stored: Stored,
        receipt: AccrualPostingReceipt?,
        at: OffsetDateTime,
    ): Uni<Unit> = s.createNativeQuery<Int>(COMPLETE).setParameter("journal", receipt?.journalId)
        .setParameter("booked", receipt?.accountingDate).setParameter("at", at).setParameter("id", id).executeUpdate()
        .flatMap { changed ->
            check(changed == 1)
            s.createNativeQuery<Int>(MARK).setParameter("at", at).setParameter("id", id).executeUpdate()
        }.flatMap { changed ->
            check(changed == 1) { "Installment status has no matching completion" }
            if (receipt == null) Uni.createFrom().item(0) else emit(s, stored, at)
        }.flatMap {
            // Lock/write day rows in deterministic date order, including replayed old intents.
            val days = listOfNotNull(stored.businessDate, receipt?.accountingDate).distinct().sorted()
            days.fold(Uni.createFrom().item(0)) { prior, day ->
                prior.flatMap {
                    increment(
                        s,
                        day,
                        if (day == stored.businessDate) 1 else 0,
                        if (day == receipt?.accountingDate) 1 else 0,
                    )
                }
            }
        }.replaceWith(Unit)

    private fun emit(s: Mutiny.Session, stored: Stored, at: OffsetDateTime): Uni<Int> {
        val payload = mapper.writeValueAsString(
            linkedMapOf(
                "aggregateType" to "LOAN",
                "aggregateId" to stored.loanId,
                "loanId" to stored.loanId,
                "installment" to stored.number,
                "interest" to Money.of(stored.amount, stored.currency).toString(),
                "dueDate" to stored.dueDate.toString(),
                "occurredAt" to at.toInstant().toString(),
                "sourceService" to "lending",
            ),
        )
        return s.createNativeQuery<Int>(EVENT).setParameter("event", transactionId(stored.reference + ":event"))
            .setParameter("loan", stored.loanId).setParameter("payload", payload).setParameter("at", at).executeUpdate()
    }

    private fun increment(s: Mutiny.Session, day: LocalDate, items: Int, journals: Int) = s.createNativeQuery<Int>(
        INCREMENT,
    ).setParameter("day", day).setParameter("items", items).setParameter("journals", journals).executeUpdate()

    private fun validateReceipt(stored: Stored, receipt: AccrualPostingReceipt?) {
        require((receipt == null) == (stored.posting == null)) { "Missing or unexpected accrual receipt" }
        if (receipt != null) {
            require(receipt.journalId != UUID(0, 0) && receipt.transactionId == transactionId(stored.reference))
            require(receipt.accountingDate == stored.posting!!.accountingDate)
        }
    }

    private fun verifySource(stored: Stored, source: Array<Any?>) {
        require(stored.loanId == source[0] && stored.number == (source[1] as Number).toInt())
        require(stored.dueDate == date(source[2]) && stored.amount.compareTo(source[SRC_AMOUNT] as BigDecimal) == 0)
        require(stored.currency == source[SRC_CURRENCY].toString().trim()) {
            "Installment changed after accrual intent"
        }
    }

    private fun lockInstallment(s: Mutiny.Session, id: UUID): Uni<Array<Any?>> =
        s.createNativeQuery<Array<Any?>>(SOURCE).setParameter("id", id).singleResult

    private fun load(s: Mutiny.Session, id: UUID): Uni<Stored?> =
        s.createNativeQuery<Array<Any?>>(SELECT).setParameter("id", id).resultList.map { rows ->
            rows.singleOrNull()?.let { r ->
                val reference = r[COL_REFERENCE] as String
                Stored(
                    r[COL_LOAN] as UUID, (r[COL_NUMBER] as Number).toInt(),
                    date(
                        r[COL_DUE],
                    ),
                    r[COL_AMOUNT] as BigDecimal,
                    r[COL_CURRENCY].toString().trim(), date(r[COL_DAY]), reference,
                    if (r[COL_REQUESTED] ==
                        null
                    ) {
                        null
                    } else {
                        PreparedAccrualPosting(
                            reference,
                            r[COL_TRANSACTION] as UUID,
                            date(r[COL_REQUESTED]),
                            r[COL_REQUEST] as String,
                        )
                    },
                    r[COL_JOURNAL] as? UUID, r[COL_BOOKED]?.let(::date), r[COL_COMPLETED],
                )
            }
        }

    private data class Stored(
        val loanId: UUID,
        val number: Int,
        val dueDate: LocalDate,
        val amount: BigDecimal,
        val currency: String,
        val businessDate: LocalDate,
        val reference: String,
        val posting: PreparedAccrualPosting?,
        val journalId: UUID?,
        val bookedDate: LocalDate?,
        val completedAt: Any?,
    ) {
        fun intent(id: UUID) = AccrualIntent(id, businessDate, posting, completedAt != null)
    }

    private companion object {
        const val SRC_AMOUNT = 3
        const val SRC_CURRENCY = 4
        const val SRC_ACCRUED = 5
        const val COL_LOAN = 0
        const val COL_NUMBER = 1
        const val COL_DUE = 2
        const val COL_AMOUNT = 3
        const val COL_CURRENCY = 4
        const val COL_DAY = 5
        const val COL_REFERENCE = 6
        const val COL_TRANSACTION = 7
        const val COL_REQUESTED = 8
        const val COL_REQUEST = 9
        const val COL_JOURNAL = 10
        const val COL_BOOKED = 11
        const val COL_COMPLETED = 12
        fun date(value: Any?) = if (value is LocalDate) value else LocalDate.parse(value.toString())
        fun transactionId(reference: String) = UUID.nameUUIDFromBytes(reference.toByteArray(Charsets.UTF_8))
        const val SOURCE =
            "SELECT loan_id, number, due_date, interest, currency, interest_accrued FROM " +
                "installment WHERE id=:id FOR UPDATE"
        const val SELECT =
            "SELECT loan_id, installment_number, due_date, interest_amount, currency, " +
                "business_date, reference, transaction_id, requested_date, request_json, " +
                "journal_id, booked_date, completed_at FROM lending_accrual_progress WHERE " +
                "installment_id=:id FOR UPDATE"
        const val INSERT =
            "INSERT INTO lending_accrual_progress " +
                "(installment_id, loan_id, installment_number, due_date, interest_amount, " +
                "currency, business_date, reference, transaction_id, requested_date, " +
                "request_json, started_at) " +
                "VALUES (:id,:loan,:number,:due,:amount,:currency,:day,:reference," +
                ":transaction,:requested,:request,CURRENT_TIMESTAMP)"
        const val COMPLETE =
            "UPDATE lending_accrual_progress SET journal_id=:journal, booked_date=:booked, " +
                "completed_at=:at WHERE installment_id=:id AND completed_at IS NULL"
        const val MARK =
            "UPDATE installment SET interest_accrued=TRUE, accrued_at=:at WHERE id=:id AND NOT " +
                "interest_accrued"
        const val EVENT =
            "INSERT INTO " +
                "lending_outbox(event_id,aggregate_id,event_type,payload,status,created_at,updated_at) " +
                "VALUES (:event,:loan,'loan.interest_accrued',:payload,'PENDING',:at,:at)"
        const val INCREMENT =
            "INSERT INTO " +
                "lending_close_job_run(business_date,job,items_processed,journals_posted) VALUES " +
                "(:day,'accrual',:items,:journals) ON CONFLICT(business_date,job) DO UPDATE SET " +
                "items_processed=lending_close_job_run.items_processed+EXCLUDED.items_processed, " +
                "journals_posted=lending_close_job_run.journals_posted+EXCLUDED.journals_posted"
    }
}
