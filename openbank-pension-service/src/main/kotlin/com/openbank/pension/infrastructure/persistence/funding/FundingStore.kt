// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.persistence.funding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.port.out.ClaimBatchRepository
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractFundingView
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.ContributionRepository
import com.openbank.pension.application.port.out.EmployerEnrolmentRepository
import com.openbank.pension.application.port.out.IncentiveClaimRepository
import com.openbank.pension.application.port.out.IncentiveLedgerRepository
import com.openbank.pension.application.port.out.TaxYearSummaryRepository
import com.openbank.pension.application.port.out.UnmatchedPaymentRepository
import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.domain.contribution.UnmatchedPayment
import com.openbank.pension.domain.contribution.UnmatchedReason
import com.openbank.pension.domain.contribution.UnmatchedStatus
import com.openbank.pension.domain.incentive.ClaimBatch
import com.openbank.pension.domain.incentive.ClaimBatchStatus
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.incentive.EmployerExemption
import com.openbank.pension.domain.incentive.IncentiveClaim
import com.openbank.pension.domain.incentive.IncentiveLedgerEntry
import com.openbank.pension.domain.incentive.LedgerEntryKind
import com.openbank.pension.domain.incentive.TaxYearSummary
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * `@Singleton`, not `@ApplicationScoped`: a stateless store needs no client proxy, and a proxy
 * would need a no-args constructor the shared base class cannot offer.
 *
 * Shared plumbing for the S3 funding stores. Raw SQL over the reactive pool for the S3 funding tables (ADR-0334 S3), the shape
 * copilot-service's `PgVectorPassageIndex` uses. Chosen over Panache here because every write is
 * an append or an `ON CONFLICT DO NOTHING` insert — the idempotency guarantee IS the statement, and
 * an ORM would hide it behind a read-then-persist that a concurrent redelivery can race past.
 * Every value is a bind parameter; no SQL is assembled from input.
 *
 * It also works from a bare scheduler context: the pool needs no Hibernate session, so no
 * `withSession` wrapper can be forgotten on the cron path.
 */
abstract class PgFundingSupport(protected val client: Pool) {
    protected suspend fun rows(sql: String, args: Tuple): List<Row> =
        client.preparedQuery(sql).execute(args).awaitSuspending().toList()

    protected suspend fun exec(sql: String, args: Tuple): Int =
        client.preparedQuery(sql).execute(args).awaitSuspending().rowCount()
}

@Singleton
class PgContractReferences(client: Pool) :
    PgFundingSupport(client),
    ContractReferenceRepository {

    override suspend fun referenceFor(contractId: UUID): String {
        client.preparedQuery(
            """
            INSERT INTO pension_contract_references (contract_id, reference)
            VALUES ($1, nextval('pension_contract_reference_seq')::text)
            ON CONFLICT (contract_id) DO NOTHING
            """.trimIndent(),
        ).execute(Tuple.of(contractId)).awaitSuspending()
        return rows("SELECT reference FROM pension_contract_references WHERE contract_id = $1", Tuple.of(contractId))
            .first().getString("reference")
    }

    override suspend fun contractFor(reference: String): UUID? =
        rows("SELECT contract_id FROM pension_contract_references WHERE reference = $1", Tuple.of(reference))
            .firstOrNull()?.getUUID("contract_id")
}

@Singleton
class PgContractDirectory(client: Pool) :
    PgFundingSupport(client),
    ContractFundingDirectory {

    override suspend fun find(contractId: UUID): ContractFundingView? =
        rows("${Sql.CONTRACT_SELECT} WHERE contract_id = $1", Tuple.of(contractId)).firstOrNull()?.toContract()

    override suspend fun byParticipant(participantPartyId: UUID): List<ContractFundingView> = rows(
        "${Sql.CONTRACT_SELECT} WHERE participant_party_id = $1 ORDER BY created_at, contract_id",
        Tuple.of(participantPartyId),
    )
        .map { it.toContract() }

    override suspend fun fundable(): List<ContractFundingView> =
        rows("${Sql.CONTRACT_SELECT} WHERE status IN ('ACTIVE', 'SUSPENDED') ORDER BY created_at", Tuple.tuple())
            .map { it.toContract() }
}

@Singleton
class PgContributions(client: Pool) :
    PgFundingSupport(client),
    ContributionRepository {

    override suspend fun insertIfAbsent(contribution: Contribution): Pair<Contribution, Boolean> {
        val inserted = client.preparedQuery(
            """
            INSERT INTO pension_contributions (id, contract_id, payment_id, source, channel, amount, currency,
                value_date, employer_party_id, subscription_order_id, received_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            ON CONFLICT (payment_id) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.tuple(
                listOf(
                    contribution.id, contribution.contractId, contribution.paymentId, contribution.source.name,
                    contribution.channel.name, contribution.amount, contribution.currency, contribution.valueDate,
                    contribution.employerPartyId, contribution.subscriptionOrderId, utc(contribution.receivedAt),
                ),
            ),
        ).awaitSuspending().rowCount() == 1
        val stored = rows(
            "${Sql.CONTRIBUTION_SELECT} WHERE payment_id = $1",
            Tuple.of(contribution.paymentId),
        ).first().toContribution()
        return stored to inserted
    }

    override suspend fun setSubscriptionOrder(contributionId: UUID, orderId: String) {
        client.preparedQuery("UPDATE pension_contributions SET subscription_order_id = $2 WHERE id = $1")
            .execute(Tuple.of(contributionId, orderId)).awaitSuspending()
    }

    override suspend fun byContract(contractId: UUID): List<Contribution> =
        rows("${Sql.CONTRIBUTION_SELECT} WHERE contract_id = $1 ORDER BY value_date, received_at", Tuple.of(contractId))
            .map { it.toContribution() }

    override suspend fun byContractAndYear(contractId: UUID, taxYear: Int): List<Contribution> =
        byContractAndRange(contractId, LocalDate.of(taxYear, 1, 1), LocalDate.of(taxYear + 1, 1, 1))

    override suspend fun byContractAndRange(
        contractId: UUID,
        from: LocalDate,
        toExclusive: LocalDate,
    ): List<Contribution> = rows(
        "${Sql.CONTRIBUTION_SELECT} WHERE contract_id = $1 AND value_date >= $2 AND value_date < $3 ORDER BY value_date, received_at",
        Tuple.of(contractId, from, toExclusive),
    ).map { it.toContribution() }
}

@Singleton
class PgUnmatchedPayments(client: Pool) :
    PgFundingSupport(client),
    UnmatchedPaymentRepository {

    override suspend fun insertIfAbsent(payment: UnmatchedPayment): UnmatchedPayment {
        val p = payment.payment
        client.preparedQuery(
            """
            INSERT INTO pension_unmatched_payments (id, payment_id, amount, currency, value_date, reference, channel,
                payer_account, reason, status, created_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            ON CONFLICT (payment_id) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.tuple(
                listOf(
                    payment.id, p.paymentId, p.amount, p.currency, p.valueDate, p.reference, p.channel.name,
                    p.payerAccount, payment.reason.name, payment.status.name, utc(payment.createdAt),
                ),
            ),
        ).awaitSuspending()
        return rows("${Sql.UNMATCHED_SELECT} WHERE payment_id = $1", Tuple.of(p.paymentId)).first().toUnmatched()
    }

    override suspend fun findById(id: UUID): UnmatchedPayment? =
        rows("${Sql.UNMATCHED_SELECT} WHERE id = $1", Tuple.of(id)).firstOrNull()?.toUnmatched()

    override suspend fun list(status: UnmatchedStatus?): List<UnmatchedPayment> = rows(
        "${Sql.UNMATCHED_SELECT} WHERE ($1::text IS NULL OR status = $1) ORDER BY created_at",
        Tuple.of(status?.name),
    )
        .map { it.toUnmatched() }

    override suspend fun update(payment: UnmatchedPayment) {
        client.preparedQuery(
            "UPDATE pension_unmatched_payments SET status = $2, resolved_contract_id = $3, resolved_by = $4, resolved_at = $5 WHERE id = $1",
        ).execute(
            Tuple.tuple(
                listOf(
                    payment.id,
                    payment.status.name,
                    payment.resolvedContractId,
                    payment.resolvedBy,
                    payment.resolvedAt?.let(::utc),
                ),
            ),
        ).awaitSuspending()
    }
}

@Singleton
class PgIncentiveClaims(client: Pool) :
    PgFundingSupport(client),
    IncentiveClaimRepository {

    override suspend fun insertIfAbsent(claim: IncentiveClaim): Pair<IncentiveClaim, Boolean> {
        val inserted = client.preparedQuery(
            """
            INSERT INTO pension_incentive_claims (id, contract_id, incentive_id, period, basis, claimed_amount, currency,
                status, batch_id, received_amount, rejection_reason, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
            ON CONFLICT (contract_id, incentive_id, period) DO NOTHING
            """.trimIndent(),
        ).execute(claimTuple(claim)).awaitSuspending().rowCount() == 1
        val stored = rows(
            "${Sql.CLAIM_SELECT} WHERE contract_id = $1 AND incentive_id = $2 AND period = $3",
            Tuple.of(claim.contractId, claim.incentiveId, claim.period.toString()),
        ).first().toClaim()
        return stored to inserted
    }

    override suspend fun findById(id: UUID): IncentiveClaim? =
        rows("${Sql.CLAIM_SELECT} WHERE id = $1", Tuple.of(id)).firstOrNull()?.toClaim()

    override suspend fun byStatus(status: ClaimStatus): List<IncentiveClaim> =
        rows("${Sql.CLAIM_SELECT} WHERE status = $1 ORDER BY period, created_at", Tuple.of(status.name)).map {
            it.toClaim()
        }

    override suspend fun byContract(contractId: UUID): List<IncentiveClaim> =
        rows("${Sql.CLAIM_SELECT} WHERE contract_id = $1 ORDER BY period", Tuple.of(contractId)).map { it.toClaim() }

    override suspend fun update(claim: IncentiveClaim) {
        client.preparedQuery(
            """
            UPDATE pension_incentive_claims SET status = $2, batch_id = $3, received_amount = $4,
                rejection_reason = $5, updated_at = $6 WHERE id = $1
            """.trimIndent(),
        ).execute(
            Tuple.tuple(
                listOf(
                    claim.id,
                    claim.status.name,
                    claim.batchId,
                    claim.receivedAmount,
                    claim.rejectionReason,
                    utc(claim.updatedAt),
                ),
            ),
        ).awaitSuspending()
    }

    private fun claimTuple(c: IncentiveClaim) = Tuple.tuple(
        listOf(
            c.id, c.contractId, c.incentiveId, c.period.toString(), c.basis, c.claimedAmount, c.currency, c.status.name,
            c.batchId, c.receivedAmount, c.rejectionReason, utc(c.createdAt), utc(c.updatedAt),
        ),
    )
}

@Singleton
class PgClaimBatches(client: Pool) :
    PgFundingSupport(client),
    ClaimBatchRepository {

    override suspend fun fileAtomically(batch: ClaimBatch, at: Instant): Boolean {
        val ids = batch.claimIds.toTypedArray()
        return client.withTransaction { conn ->
            conn.preparedQuery(
                """
                INSERT INTO pension_claim_batches (id, claim_format, period, claim_ids, payload, channel_reference, status, created_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                """.trimIndent(),
            ).execute(
                Tuple.tuple(
                    listOf(
                        batch.id,
                        batch.claimFormat,
                        batch.period.toString(),
                        batch.claimIds.joinToString(","),
                        batch.payload,
                        batch.channelReference,
                        batch.status.name,
                        utc(batch.createdAt),
                    ),
                ),
            ).flatMap {
                conn.preparedQuery(
                    "UPDATE pension_incentive_claims SET status = 'SUBMITTED', batch_id = $1, updated_at = $2 " +
                        "WHERE id = ANY($3) AND status = 'PENDING'",
                ).execute(Tuple.of(batch.id, utc(at), ids))
            }.flatMap { result ->
                if (result.rowCount() == ids.size) {
                    io.smallrye.mutiny.Uni.createFrom().item(true)
                } else {
                    // Roll the whole filing back: some claim was already filed by a concurrent run.
                    io.smallrye.mutiny.Uni.createFrom().failure(LostFilingRace())
                }
            }
        }.onFailure(LostFilingRace::class.java).recoverWithItem(false).awaitSuspending()
    }

    private class LostFilingRace : RuntimeException("claim batch lost a filing race")

    override suspend fun findById(id: UUID): ClaimBatch? =
        rows("${Sql.BATCH_SELECT} WHERE id = $1", Tuple.of(id)).firstOrNull()?.toBatch()

    override suspend fun list(): List<ClaimBatch> =
        rows("${Sql.BATCH_SELECT} ORDER BY created_at DESC", Tuple.tuple()).map {
            it.toBatch()
        }

    override suspend fun update(batch: ClaimBatch) {
        client.preparedQuery("UPDATE pension_claim_batches SET status = $2, channel_reference = $3 WHERE id = $1")
            .execute(Tuple.of(batch.id, batch.status.name, batch.channelReference)).awaitSuspending()
    }
}

@Singleton
class PgEmployerEnrolments(client: Pool) :
    PgFundingSupport(client),
    EmployerEnrolmentRepository {

    override suspend fun enrol(contractId: UUID, employerPartyId: UUID) {
        exec(
            "INSERT INTO pension_employer_enrolments (contract_id, employer_party_id, enrolled_at) " +
                "VALUES ($1, $2, now()) " +
                "ON CONFLICT (contract_id, employer_party_id) DO NOTHING",
            Tuple.of(contractId, employerPartyId),
        )
    }

    override suspend fun isEnrolled(contractId: UUID, employerPartyId: UUID): Boolean = rows(
        "SELECT 1 FROM pension_employer_enrolments WHERE contract_id = $1 AND employer_party_id = $2",
        Tuple.of(contractId, employerPartyId),
    ).isNotEmpty()
}

@Singleton
class PgIncentiveLedger(client: Pool) :
    PgFundingSupport(client),
    IncentiveLedgerRepository {

    override suspend fun append(entry: IncentiveLedgerEntry) {
        client.preparedQuery(
            """
            INSERT INTO pension_incentive_ledger (id, contract_id, incentive_id, claim_id, kind, amount, tax_year, period, occurred_at,
                idempotency_key)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            ON CONFLICT (idempotency_key) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.tuple(
                listOf(
                    entry.id, entry.contractId, entry.incentiveId, entry.claimId, entry.kind.name, entry.amount,
                    entry.taxYear, entry.period.toString(), utc(entry.occurredAt), entry.idempotencyKey,
                ),
            ),
        ).awaitSuspending()
    }

    override suspend fun byContract(contractId: UUID): List<IncentiveLedgerEntry> = rows(
        "SELECT * FROM pension_incentive_ledger WHERE contract_id = $1 ORDER BY occurred_at, id",
        Tuple.of(contractId),
    ).map { it.toLedger() }
}

@Singleton
class PgTaxYears(client: Pool, private val objectMapper: ObjectMapper) :
    PgFundingSupport(client),
    TaxYearSummaryRepository {

    override suspend fun findFinal(contractId: UUID, taxYear: Int): TaxYearSummary? = rows(
        "SELECT summary FROM pension_tax_year_certificates WHERE contract_id = $1 AND tax_year = $2",
        Tuple.of(contractId, taxYear),
    ).firstOrNull()?.let { objectMapper.readValue<SummaryRow>(it.getString("summary")).toDomain() }

    override suspend fun saveFinal(summary: TaxYearSummary) {
        client.preparedQuery(
            """
            INSERT INTO pension_tax_year_certificates (contract_id, tax_year, summary, certificate_document_id, finalized_at)
            VALUES ($1, $2, $3, $4, $5) ON CONFLICT (contract_id, tax_year) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.of(
                summary.contractId,
                summary.taxYear,
                objectMapper.writeValueAsString(SummaryRow.from(summary)),
                requireNotNull(summary.certificateDocumentId),
                utc(requireNotNull(summary.finalizedAt)),
            ),
        ).awaitSuspending()
    }

    override suspend fun externalCapUsage(participantPartyId: UUID, taxYear: Int): Map<String, BigDecimal> = rows(
        "SELECT cap_group, amount FROM pension_external_cap_usage WHERE participant_party_id = $1 AND tax_year = $2",
        Tuple.of(participantPartyId, taxYear),
    ).associate { it.getString("cap_group") to it.getBigDecimal("amount") }

    override suspend fun declareExternalCapUsage(
        participantPartyId: UUID,
        taxYear: Int,
        usage: Map<String, BigDecimal>,
    ) {
        usage.forEach { (group, amount) ->
            client.preparedQuery(
                """
                INSERT INTO pension_external_cap_usage (participant_party_id, tax_year, cap_group, amount)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (participant_party_id, tax_year, cap_group) DO UPDATE SET amount = EXCLUDED.amount
                """.trimIndent(),
            ).execute(Tuple.of(participantPartyId, taxYear, group, amount)).awaitSuspending()
        }
    }
}

private fun utc(i: Instant): OffsetDateTime = i.atOffset(ZoneOffset.UTC)

private fun Row.instant(column: String): Instant = getOffsetDateTime(column).toInstant()

private fun Row.toContract() = ContractFundingView(
    contractId = getUUID("contract_id"),
    participantPartyId = getUUID("participant_party_id"),
    jurisdiction = getString("jurisdiction"),
    productLine = getString("product_line"),
    packVersion = getInteger("pack_version"),
    status = getString("status"),
    currency = getString("contribution_currency"),
    createdAt = instant("created_at"),
)

private fun Row.toContribution() = Contribution(
    id = getUUID("id"),
    contractId = getUUID("contract_id"),
    paymentId = getString("payment_id"),
    source = ContributionSource.valueOf(getString("source")),
    channel = ContributionChannel.valueOf(getString("channel")),
    amount = getBigDecimal("amount"),
    currency = getString("currency"),
    valueDate = getLocalDate("value_date"),
    employerPartyId = getUUID("employer_party_id"),
    subscriptionOrderId = getString("subscription_order_id"),
    receivedAt = instant("received_at"),
)

private fun Row.toUnmatched() = UnmatchedPayment(
    id = getUUID("id"),
    payment = IncomingPayment(
        paymentId = getString("payment_id"),
        amount = getBigDecimal("amount"),
        currency = getString("currency"),
        valueDate = getLocalDate("value_date"),
        reference = getString("reference"),
        channel = ContributionChannel.valueOf(getString("channel")),
        payerAccount = getString("payer_account"),
    ),
    reason = UnmatchedReason.valueOf(getString("reason")),
    status = UnmatchedStatus.valueOf(getString("status")),
    createdAt = instant("created_at"),
    resolvedContractId = getUUID("resolved_contract_id"),
    resolvedBy = getString("resolved_by"),
    resolvedAt = getOffsetDateTime("resolved_at")?.toInstant(),
)

private fun Row.toClaim() = IncentiveClaim(
    id = getUUID("id"),
    contractId = getUUID("contract_id"),
    incentiveId = getString("incentive_id"),
    period = YearMonth.parse(getString("period")),
    basis = getBigDecimal("basis"),
    claimedAmount = getBigDecimal("claimed_amount"),
    currency = getString("currency"),
    status = ClaimStatus.valueOf(getString("status")),
    batchId = getUUID("batch_id"),
    receivedAmount = getBigDecimal("received_amount"),
    rejectionReason = getString("rejection_reason"),
    createdAt = instant("created_at"),
    updatedAt = instant("updated_at"),
)

private fun Row.toBatch() = ClaimBatch(
    id = getUUID("id"),
    claimFormat = getString("claim_format"),
    period = YearMonth.parse(getString("period")),
    claimIds = getString("claim_ids").split(",").filter { it.isNotBlank() }.map(UUID::fromString),
    payload = getString("payload"),
    channelReference = getString("channel_reference"),
    status = ClaimBatchStatus.valueOf(getString("status")),
    createdAt = instant("created_at"),
)

private fun Row.toLedger() = IncentiveLedgerEntry(
    id = getUUID("id"),
    contractId = getUUID("contract_id"),
    incentiveId = getString("incentive_id"),
    claimId = getUUID("claim_id"),
    kind = LedgerEntryKind.valueOf(getString("kind")),
    amount = getBigDecimal("amount"),
    taxYear = getInteger("tax_year"),
    period = YearMonth.parse(getString("period")),
    occurredAt = instant("occurred_at"),
    idempotencyKey = getString("idempotency_key"),
)

/** Storage shape of a certified summary — kept apart so the domain type stays annotation-free. */
data class SummaryRow(
    val contractId: String,
    val taxYear: Int,
    val currency: String,
    val participantContributions: BigDecimal,
    val employerContributions: BigDecimal,
    val stateIncentives: BigDecimal,
    val transferIn: BigDecimal,
    val deductibleAmount: BigDecimal,
    val indicativeTaxSaving: BigDecimal?,
    val sharedCapUsedElsewhere: Map<String, BigDecimal>,
    val employerExemptions: List<ExemptionRow>,
    val finalizedAt: String?,
    val certificateDocumentId: String?,
) {
    fun toDomain() = TaxYearSummary(
        UUID.fromString(contractId), taxYear, currency, participantContributions, employerContributions,
        stateIncentives, transferIn, deductibleAmount, indicativeTaxSaving, sharedCapUsedElsewhere,
        employerExemptions.map {
            EmployerExemption(UUID.fromString(it.employerPartyId), it.contributed, it.exempt, it.taxable)
        },
        finalizedAt?.let(Instant::parse), certificateDocumentId,
    )

    companion object {
        fun from(s: TaxYearSummary) = SummaryRow(
            s.contractId.toString(), s.taxYear, s.currency, s.participantContributions, s.employerContributions,
            s.stateIncentives, s.transferIn, s.deductibleAmount, s.indicativeTaxSaving, s.sharedCapUsedElsewhere,
            s.employerExemptions.map {
                ExemptionRow(it.employerPartyId.toString(), it.contributed, it.exempt, it.taxable)
            },
            s.finalizedAt?.toString(), s.certificateDocumentId,
        )
    }
}

data class ExemptionRow(
    val employerPartyId: String,
    val contributed: BigDecimal,
    val exempt: BigDecimal,
    val taxable: BigDecimal,
)

private object Sql {
    const val CONTRACT_SELECT =
        "SELECT contract_id, participant_party_id, jurisdiction, product_line, pack_version, status, " +
            "contribution_currency, created_at FROM pension_contracts"
    const val CONTRIBUTION_SELECT = "SELECT * FROM pension_contributions"
    const val UNMATCHED_SELECT = "SELECT * FROM pension_unmatched_payments"
    const val CLAIM_SELECT = "SELECT * FROM pension_incentive_claims"
    const val BATCH_SELECT = "SELECT * FROM pension_claim_batches"
}
