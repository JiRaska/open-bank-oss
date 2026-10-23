// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.swift.application.port.out.SwiftBookingAgeSnapshot
import com.openbank.swift.application.port.out.SwiftBookingQuery
import com.openbank.swift.domain.model.SwiftMessageType
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SwiftBookingQueryImpl : SwiftBookingQuery {
    override suspend fun bookingAgeSnapshot(): SwiftBookingAgeSnapshot = Panache.withSession {
        Panache.getSession().flatMap { session ->
            // One statement: do not race an oldest-time read against a separate legacy count.
            session.createQuery(
                "select new com.openbank.swift.application.port.out.SwiftBookingAgeSnapshot(" +
                    "min(p.bookingFirstDueAt), count(case when p.bookingFirstDueAt is null " +
                    "and (p.bookingClockCovered is null or p.bookingClockCovered = false) then p.id else null end)) " +
                    "from SwiftMessageEntity p where p.messageType = :bookingType and p.bookingProvenAt is null",
                SwiftBookingAgeSnapshot::class.java,
            ).setParameter("bookingType", SwiftMessageType.MT103).singleResult
        }
    }.awaitSuspending()
}
