// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.application.port.out.SepaBookingAgeSnapshot
import com.openbank.sepa.application.port.out.SepaBookingQuery
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SepaBookingQueryImpl : SepaBookingQuery {
    override suspend fun bookingAgeSnapshot(): SepaBookingAgeSnapshot = Panache.withSession {
        Panache.getSession().flatMap { session ->
            // One statement: do not race an oldest-time read against a separate legacy count.
            session.createQuery(
                "select new com.openbank.sepa.application.port.out.SepaBookingAgeSnapshot(" +
                    "min(p.bookingFirstDueAt), count(case when p.bookingFirstDueAt is null " +
                    "and (p.bookingClockCovered is null or p.bookingClockCovered = false) then p.id else null end)) " +
                    "from SepaPaymentEntity p where p.bookingProvenAt is null",
                SepaBookingAgeSnapshot::class.java,
            ).singleResult
        }
    }.awaitSuspending()
}
