// SPDX-License-Identifier: Apache-2.0

package com.openbank.productcatalog.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.annotations.RegisterForReflection
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
@Path("/api/v2/booking-grant-receipts")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class BookingGrantReceiptResource(
    private val identity: SecurityIdentity,
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.catalog.booking.account-client")
    private val accountClient: Optional<String>,
    @ConfigProperty(name = "openbank.catalog.booking.account-subject")
    private val accountSubject: Optional<String>,
) {
    @POST
    @Blocking
    fun consume(request: BookingGrantConsumption?): Response {
        val token = identity.principal as? JsonWebToken ?: throw ForbiddenException()
        val client = accountClient.orElseThrow { ForbiddenException() }
        val subject = accountSubject.orElseThrow { ForbiddenException() }
        val verifiedAccountCaller = token.getClaim<String>("azp") == client && token.subject == subject
        if (client.isBlank() || subject.isBlank() || !verifiedAccountCaller) {
            throw ForbiddenException()
        }
        requireNotNull(request) { "Booking grant consumption is required" }
        val operationId = requireNotNull(request.operationId) { "operationId is required" }
        val subjectId = requireNotNull(request.subjectId) { "subjectId is required" }
        val productRevisionId = requireNotNull(request.productRevisionId) { "productRevisionId is required" }
        val grantRevisionId = requireNotNull(request.grantRevisionId) { "grantRevisionId is required" }

        val receipt = try {
            dataSource.connection.use { connection ->
                val sql = "SELECT * FROM catalog_consume_booking_grant(?, ?, ?, ?)"
                connection.prepareStatement(sql).use { statement ->
                    statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                    statement.setObject(1, operationId)
                    statement.setObject(2, subjectId)
                    statement.setObject(3, productRevisionId)
                    statement.setObject(4, grantRevisionId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "Booking grant consumption returned no receipt" }
                        BookingGrantReceipt(
                            operationId,
                            subjectId,
                            productRevisionId,
                            grantRevisionId,
                            rows.getString("approval_digest"),
                            mapper.readTree(rows.getString("booked_context")),
                            rows.getObject("consumed_at", OffsetDateTime::class.java),
                        )
                    }
                }
            }
        } catch (error: SQLException) {
            val conflict = when (error.sqlState) {
                "22023" -> "BOOKING_GRANT_UNAVAILABLE"
                "23505" -> "BOOKING_OPERATION_CONFLICT"
                else -> throw error
            }
            return Response.status(Response.Status.CONFLICT).entity(mapOf("code" to conflict)).build()
        }
        return Response.ok(receipt).build()
    }

    private companion object {
        const val QUERY_TIMEOUT_SECONDS = 5
    }
}

data class BookingGrantConsumption(
    val operationId: UUID? = null,
    val subjectId: UUID? = null,
    val productRevisionId: UUID? = null,
    val grantRevisionId: UUID? = null,
)

@RegisterForReflection
data class BookingGrantReceipt(
    val operationId: UUID,
    val subjectId: UUID,
    val productRevisionId: UUID,
    val grantRevisionId: UUID,
    val approvalDigest: String,
    val bookedContext: JsonNode,
    val consumedAt: OffsetDateTime,
)
