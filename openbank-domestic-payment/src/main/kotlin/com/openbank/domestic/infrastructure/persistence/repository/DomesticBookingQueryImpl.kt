// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.application.port.out.DomesticBookingAgeSnapshot
import com.openbank.domestic.application.port.out.DomesticBookingQuery
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class DomesticBookingQueryImpl : DomesticBookingQuery {
    override suspend fun bookingAgeSnapshot(): DomesticBookingAgeSnapshot = Panache.withSession {
        Panache.getSession().flatMap { session ->
            // One statement: do not race an oldest-time read against a separate legacy count.
            session.createQuery(
                "select new com.openbank.domestic.application.port.out.DomesticBookingAgeSnapshot(" +
                    "min(p.bookingFirstDueAt), count(case when p.bookingFirstDueAt is null " +
                    "and (p.bookingClockCovered is null or p.bookingClockCovered = false) then p.id else null end)) " +
                    "from DomesticPaymentEntity p where p.bookingProvenAt is null",
                DomesticBookingAgeSnapshot::class.java,
            ).singleResult
        }
    }.awaitSuspending()
}
