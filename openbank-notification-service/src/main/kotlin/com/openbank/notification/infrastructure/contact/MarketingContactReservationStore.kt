// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.contact

import com.openbank.libs.contact.ContactPolicy
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class MarketingReservationDecision { RESERVED, CAP_REACHED, ALREADY_RESERVED }

/** Serializes outbound marketing reservations for one party across consumers and pods. */
@ApplicationScoped
class MarketingContactReservationStore(private val pool: PgPool, private val clock: Clock) {
    private val policy = ContactPolicy()

    fun reserve(notificationId: UUID, partyId: UUID): Uni<MarketingReservationDecision> {
        val windowStart = Instant.now(clock).minusSeconds(policy.sendWindowSeconds).atOffset(ZoneOffset.UTC)
        return pool.withTransaction { conn ->
            // A hash collision only serializes unrelated parties; it cannot loosen the cap.
            conn.preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))")
                .execute(Tuple.of(partyId.toString()))
                .flatMap {
                    conn.preparedQuery(
                        "SELECT 1 FROM marketing_contact_reservations WHERE notification_id = $1",
                    ).execute(Tuple.of(notificationId))
                }
                .flatMap { existing ->
                    if (existing.iterator().hasNext()) {
                        Uni.createFrom().item(MarketingReservationDecision.ALREADY_RESERVED)
                    } else {
                        conn.preparedQuery(
                            """
                            SELECT count(*) AS contacts
                            FROM marketing_contact_reservations AS reservation
                            JOIN notifications AS notification
                              ON notification.notification_id = reservation.notification_id
                            WHERE reservation.party_id = $1 AND reservation.reserved_at >= $2
                              AND notification.status IN ('PENDING', 'SENT', 'BOUNCED')
                            """.trimIndent(),
                        ).execute(Tuple.of(partyId, windowStart)).flatMap { rows ->
                            if (rows.first().getLong("contacts") >= policy.sendCapPerWindow) {
                                Uni.createFrom().item(MarketingReservationDecision.CAP_REACHED)
                            } else {
                                conn.preparedQuery(
                                    """
                                    INSERT INTO marketing_contact_reservations (notification_id, party_id)
                                    VALUES ($1, $2)
                                    """.trimIndent(),
                                ).execute(Tuple.of(notificationId, partyId))
                                    .replaceWith(MarketingReservationDecision.RESERVED)
                            }
                        }
                    }
                }
        }
    }
}
