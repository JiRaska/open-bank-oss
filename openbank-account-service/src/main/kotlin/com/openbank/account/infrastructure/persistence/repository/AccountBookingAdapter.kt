// SPDX-License-Identifier: Apache-2.0

package com.openbank.account.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.AccountBookingPort
import com.openbank.account.application.port.out.PendingAccountBooking
import com.openbank.account.domain.model.AccountBookingReceipt
import com.openbank.account.domain.model.ProductRevisionPin
import com.openbank.account.infrastructure.client.BookingGrantRequest
import com.openbank.account.infrastructure.client.ProductCatalogBookingClient
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@ApplicationScoped
class AccountBookingAdapter(
    @RestClient private val catalog: ProductCatalogBookingClient,
    private val mapper: ObjectMapper,
) : AccountBookingPort {
    override suspend fun findPending(operationId: UUID): PendingAccountBooking? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(FIND_PENDING, Array<Any>::class.java)
                .setParameter("operationId", operationId)
                .singleResultOrNull
        }
    }.awaitSuspending()?.let { row ->
        PendingAccountBooking(
            operationId = operationId,
            idempotencyKey = row[0] as String,
            requestHash = row[1] as String,
            partyId = row[2] as UUID,
            productId = row[3] as UUID,
            grantRevisionId = row[4] as UUID,
            pin = mapper.readValue(row[5] as String, ProductRevisionPin::class.java),
        )
    }

    override suspend fun consume(pending: PendingAccountBooking): AccountBookingReceipt {
        val request = BookingGrantRequest(
            pending.operationId,
            pending.partyId,
            pending.pin.revisionId,
            pending.grantRevisionId,
        )
        val receipt = catalog.consume(request).awaitSuspending()
        check(
            receipt.operationId == request.operationId &&
                receipt.subjectId == request.subjectId &&
                receipt.productRevisionId == request.productRevisionId &&
                receipt.grantRevisionId == request.grantRevisionId,
        ) { "Catalog returned a receipt for another booking operation" }
        return AccountBookingReceipt(
            operationId = receipt.operationId,
            legalEntityId = receipt.bookedContext.legalEntityId,
            bookingBookId = receipt.bookedContext.bookingBookId,
            operatingMode = AccountBookingReceipt.OperatingMode.valueOf(receipt.bookedContext.mode),
            establishmentId = receipt.bookedContext.establishmentId,
            approvalDigest = receipt.approvalDigest,
            consumedAt = receipt.consumedAt.toInstant(),
        )
    }

    private companion object {
        const val FIND_PENDING = """
            SELECT idempotency_key, request_hash, party_id, product_id, grant_revision_id,
                   CAST(product_revision_pin AS text)
            FROM account_booking_operations WHERE operation_id = :operationId
        """
    }
}
