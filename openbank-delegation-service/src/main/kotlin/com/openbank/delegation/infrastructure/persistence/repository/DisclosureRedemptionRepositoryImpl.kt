// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.DisclosureRedemptionRepository
import com.openbank.delegation.application.port.out.IssueRedemptionRecord
import com.openbank.delegation.application.port.out.RedemptionChallenge
import com.openbank.delegation.domain.model.RedeemableSnapshot
import com.openbank.delegation.infrastructure.persistence.entity.DisclosureRedemptionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DisclosureRedemptionRepositoryImpl : DisclosureRedemptionRepository {
    override suspend fun issue(record: IssueRedemptionRecord): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<UUID>(LOCK_DISCLOSURE_SQL)
                .setParameter("disclosureId", record.disclosureId)
                .setParameter("grantorPartyId", record.grantorPartyId)
                .singleResultOrNull.flatMap { lockedId ->
                    if (lockedId == null) return@flatMap io.smallrye.mutiny.Uni.createFrom().item(false)
                    session.createNativeQuery<Any>(ROTATE_SQL)
                        .setParameter("disclosureId", record.disclosureId)
                        .setParameter("issuanceKeyHash", record.issuanceIdempotencyKeyHash)
                        .setParameter("now", record.now).executeUpdate().flatMap {
                            session.createNativeQuery<Any>(ISSUE_SQL)
                                .setParameter("id", record.id).setParameter("disclosureId", record.disclosureId)
                                .setParameter("recipientHint", record.recipientHint)
                                .setParameter("magicTokenHash", record.magicTokenHash)
                                .setParameter("otpSalt", record.otpSalt).setParameter("otpHash", record.otpHash)
                                .setParameter("expiresAt", record.expiresAt).setParameter("maxViews", record.maxViews)
                                .setParameter("issuanceKeyHash", record.issuanceIdempotencyKeyHash)
                                .setParameter("now", record.now).executeUpdate().map { it == 1 }
                        }
                }
        }
    }.awaitSuspending()

    override suspend fun peek(accessTicketHash: String, now: Instant): RedeemableSnapshot? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any>>(PEEK_SQL)
                .setParameter("ticketHash", accessTicketHash)
                .setParameter("now", now)
                .singleResultOrNull
                .map { row -> row?.toSnapshot() }
        }
    }.awaitSuspending()

    override suspend fun findChallenge(magicTokenHash: String): RedemptionChallenge? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "FROM DisclosureRedemptionEntity WHERE magicTokenHash = :hash",
                DisclosureRedemptionEntity::class.java,
            ).setParameter("hash", magicTokenHash).singleResultOrNull
        }
    }.awaitSuspending()?.toChallenge()

    override suspend fun recordFailedAttempt(id: UUID, idempotencyKeyHash: String, now: Instant): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(FAILED_SQL).setParameter("id", id)
                    .setParameter("idempotencyKeyHash", idempotencyKeyHash).setParameter("now", now)
                    .executeUpdate().map { it == 1 }
            }
        }.awaitSuspending()

    override suspend fun verify(
        id: UUID,
        accessTicketHash: String,
        idempotencyKeyHash: String,
        now: Instant,
    ): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(VERIFY_SQL).setParameter("id", id)
                .setParameter("ticketHash", accessTicketHash).setParameter("idempotencyKeyHash", idempotencyKeyHash)
                .setParameter("now", now).executeUpdate().map { it == 1 }
        }
    }.awaitSuspending()

    override suspend fun consume(
        accessTicketHash: String,
        idempotencyKeyHash: String,
        now: Instant,
    ): RedeemableSnapshot? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any>>(CONSUME_SQL)
                .setParameter("ticketHash", accessTicketHash).setParameter("now", now)
                .setParameter("idempotencyKeyHash", idempotencyKeyHash).singleResultOrNull
                .map { row -> row?.toSnapshot() }
        }
    }.awaitSuspending()

    override suspend fun revoke(disclosureId: UUID, grantorPartyId: UUID, now: Instant): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(REVOKE_SQL).setParameter("disclosureId", disclosureId)
                    .setParameter("grantorPartyId", grantorPartyId).setParameter("now", now).executeUpdate()
                    .map { it == 1 }
            }
        }.awaitSuspending()

    private fun Array<Any>.toSnapshot() = RedeemableSnapshot(
        this[0] as UUID,
        this[1] as UUID,
        this[2] as String,
        (this[VIEW_NUMBER_INDEX] as Number).toInt(),
        (this[MAX_VIEWS_INDEX] as Number).toInt(),
    )

    private companion object {
        const val VIEW_NUMBER_INDEX = 3
        const val MAX_VIEWS_INDEX = 4
        const val LOCK_DISCLOSURE_SQL = """
            SELECT id FROM delegation_disclosures
            WHERE id=:disclosureId AND grantor_party_id=:grantorPartyId AND status='READY'
            FOR UPDATE
        """
        const val ROTATE_SQL = """
            UPDATE disclosure_redemptions SET status='REVOKED', magic_token_hash=NULL,
                otp_salt=NULL, otp_hash=NULL, access_ticket_hash=NULL, revoked_at=:now, updated_at=:now
            WHERE disclosure_id=:disclosureId AND status='ISSUED'
              AND issuance_idempotency_key_hash <> :issuanceKeyHash
        """
        const val ISSUE_SQL = """
            INSERT INTO disclosure_redemptions
                (id, disclosure_id, status, recipient_hint, magic_token_hash, otp_salt, otp_hash,
                 issuance_idempotency_key_hash, expires_at, max_views, views, failed_attempts, created_at, updated_at)
            SELECT :id, :disclosureId, 'ISSUED', :recipientHint, :magicTokenHash, :otpSalt, :otpHash,
                   :issuanceKeyHash, :expiresAt, :maxViews, 0, 0, :now, :now
            WHERE NOT EXISTS (
                SELECT 1 FROM disclosure_redemptions r
                WHERE r.disclosure_id=:disclosureId AND r.status='VERIFIED'
            ) AND NOT EXISTS (
                SELECT 1 FROM disclosure_redemptions r
                WHERE r.disclosure_id=:disclosureId AND r.status='ISSUED'
                  AND r.issuance_idempotency_key_hash=:issuanceKeyHash
            )
        """
        const val FAILED_SQL = """
            WITH claimed AS (
                INSERT INTO disclosure_redemption_verification_attempts
                    (redemption_id, idempotency_key_hash, successful, attempted_at)
                VALUES (:id, :idempotencyKeyHash, false, :now)
                ON CONFLICT DO NOTHING
                RETURNING redemption_id
            )
            UPDATE disclosure_redemptions SET failed_attempts=failed_attempts+1,
                status=CASE WHEN failed_attempts+1 >= 5 THEN 'LOCKED' ELSE status END,
                magic_token_hash=CASE WHEN failed_attempts+1 >= 5 THEN NULL ELSE magic_token_hash END,
                otp_salt=CASE WHEN failed_attempts+1 >= 5 THEN NULL ELSE otp_salt END,
                otp_hash=CASE WHEN failed_attempts+1 >= 5 THEN NULL ELSE otp_hash END,
                updated_at=:now
            FROM claimed
            WHERE id=claimed.redemption_id AND status='ISSUED' AND failed_attempts < 5 AND expires_at > :now
        """
        const val VERIFY_SQL = """
            WITH claimed AS (
                INSERT INTO disclosure_redemption_verification_attempts
                    (redemption_id, idempotency_key_hash, successful, attempted_at)
                VALUES (:id, :idempotencyKeyHash, true, :now)
                ON CONFLICT DO NOTHING
                RETURNING redemption_id
            )
            UPDATE disclosure_redemptions SET status='VERIFIED', access_ticket_hash=:ticketHash,
                verification_idempotency_key_hash=:idempotencyKeyHash,
                magic_token_hash=NULL, otp_salt=NULL, otp_hash=NULL, verified_at=:now, updated_at=:now
            FROM claimed
            WHERE id=claimed.redemption_id AND status='ISSUED' AND failed_attempts < 5 AND expires_at > :now
        """
        const val CONSUME_SQL = """
            WITH candidate AS (
                SELECT r.id, r.views + 1 AS view_number
                FROM disclosure_redemptions r
                JOIN delegation_disclosures d ON d.id=r.disclosure_id
                WHERE r.access_ticket_hash=:ticketHash AND r.status='VERIFIED'
                  AND r.expires_at > :now AND r.views < r.max_views AND d.status='READY'
            ), claimed AS (
                INSERT INTO disclosure_redemption_view_consumptions
                    (redemption_id, idempotency_key_hash, view_number, consumed_at)
                SELECT id, :idempotencyKeyHash, view_number, :now FROM candidate
                ON CONFLICT DO NOTHING
                RETURNING redemption_id, view_number
            )
            UPDATE disclosure_redemptions r SET views=claimed.view_number,
                status=CASE WHEN claimed.view_number >= r.max_views THEN 'EXHAUSTED' ELSE 'VERIFIED' END,
                access_ticket_hash=CASE WHEN claimed.view_number >= r.max_views THEN NULL ELSE access_ticket_hash END,
                updated_at=:now
            FROM claimed, delegation_disclosures d
            WHERE r.id=claimed.redemption_id AND r.disclosure_id=d.id
            RETURNING r.id, d.snapshot_id, d.snapshot_sha256, r.views, r.max_views
        """
        const val PEEK_SQL = """
            SELECT r.id, d.snapshot_id, d.snapshot_sha256, r.views + 1, r.max_views
            FROM disclosure_redemptions r
            JOIN delegation_disclosures d ON d.id=r.disclosure_id
            WHERE r.access_ticket_hash=:ticketHash AND r.status='VERIFIED' AND r.expires_at > :now
              AND r.views < r.max_views AND d.status='READY'
        """
        const val REVOKE_SQL = """
            UPDATE disclosure_redemptions r SET status='REVOKED', magic_token_hash=NULL, otp_salt=NULL,
                otp_hash=NULL, access_ticket_hash=NULL, revoked_at=:now, updated_at=:now
            FROM delegation_disclosures d
            WHERE r.disclosure_id=d.id AND d.id=:disclosureId AND d.grantor_party_id=:grantorPartyId
              AND r.status IN ('ISSUED','VERIFIED')
        """
    }
}
