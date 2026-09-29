// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "context_assignment_proposals")
class AssignmentProposalEntity {
    @Id
    @Column(name = "proposal_id")
    lateinit var id: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "principal_id")
    lateinit var principalId: String

    @Column(name = "case_id")
    lateinit var caseId: String

    @Column(name = "purpose")
    lateinit var purpose: String

    @Column(name = "valid_from")
    lateinit var validFrom: Instant

    @Column(name = "valid_to")
    lateinit var validTo: Instant

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "maker_id")
    lateinit var makerId: String

    @Column(name = "checker_id")
    var checkerId: String? = null

    @Column(name = "assignment_id")
    var assignmentId: UUID? = null

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @Column(name = "decided_at")
    var decidedAt: Instant? = null
}

@Entity
@Table(name = "context_assignment_change_audit")
class AssignmentChangeAuditEntity {
    @Id
    @Column(name = "audit_id")
    lateinit var id: UUID

    @Column(name = "bank_scope")
    lateinit var bankScope: String

    @Column(name = "assignment_id")
    var assignmentId: UUID? = null

    @Column(name = "proposal_id")
    var proposalId: UUID? = null

    @Column(name = "action")
    lateinit var action: String

    @Column(name = "actor_id")
    lateinit var actorId: String

    @Column(name = "subject_id")
    lateinit var subjectId: String

    @Column(name = "case_id")
    lateinit var caseId: String

    @Column(name = "purpose")
    lateinit var purpose: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant
}

data class ProposeAssignmentRequest(
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val validFrom: Instant?,
    val validTo: Instant,
)

data class DecideAssignmentRequest(val approve: Boolean)

data class AssignmentProposalResponse(
    val id: UUID,
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val validFrom: Instant,
    val validTo: Instant,
    val status: String,
    val makerId: String,
    val checkerId: String?,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val assignmentId: UUID? = null,
)

data class ActiveAssignmentResponse(
    val id: UUID,
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val validFrom: Instant,
    val validTo: Instant,
)

class MakerCheckerViolation(message: String) : ClientErrorException(message, Response.Status.CONFLICT)
class AssignmentStateConflict(message: String) : ClientErrorException(message, Response.Status.CONFLICT)

@ApplicationScoped
@Suppress("TooManyFunctions")
class AssignmentAdministrationService(
    private val sessions: Mutiny.SessionFactory,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun propose(request: ProposeAssignmentRequest, maker: String): AssignmentProposalResponse {
        val now = clock.instant()
        val validFrom = request.validFrom ?: now
        validate(request, validFrom, now)
        val proposal = AssignmentProposalEntity().apply {
            id = Ids.newId()
            bankScope = this@AssignmentAdministrationService.bankScope
            principalId = request.principalId.trim()
            caseId = request.caseId.trim()
            purpose = request.purpose.trim()
            this.validFrom = validFrom
            validTo = request.validTo
            status = "PENDING"
            makerId = maker
            createdAt = now
        }
        transaction { session ->
            session.persist(proposal).flatMap {
                session.persist(audit(proposal, "PROPOSED", maker, null))
            }
        }
        return proposal.response()
    }

    suspend fun pending(limit: Int): List<AssignmentProposalResponse> = timed(
        sessions.withSession { session ->
            session.createQuery(
                "from AssignmentProposalEntity where bankScope = :bankScope and status = 'PENDING' order by createdAt",
                AssignmentProposalEntity::class.java,
            ).setParameter("bankScope", bankScope).setMaxResults(limit.coerceIn(1, MAX_QUEUE_SIZE)).resultList
        },
    ).map { it.response() }

    suspend fun active(limit: Int): List<ActiveAssignmentResponse> {
        val now = clock.instant()
        return timed(
            sessions.withSession { session ->
                session.createQuery(
                    "from CaseAssignmentEntity where bankScope = :bankScope and validFrom <= :now and validTo > :now order by validTo",
                    CaseAssignmentEntity::class.java,
                ).setParameter("bankScope", bankScope).setParameter("now", now)
                    .setMaxResults(limit.coerceIn(1, MAX_QUEUE_SIZE)).resultList
            },
        ).map { ActiveAssignmentResponse(it.id, it.principalId, it.caseId, it.purpose, it.validFrom, it.validTo) }
    }

    @Suppress("ThrowsCount")
    suspend fun decide(id: UUID, approve: Boolean, checker: String): AssignmentProposalResponse {
        var assignmentId: UUID? = null
        val proposal = transactionResult { session ->
            session.find(AssignmentProposalEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE).flatMap { found ->
                val value = found ?: throw NotFoundException("assignment proposal not found")
                if (value.bankScope != bankScope) throw NotFoundException("assignment proposal not found")
                if (value.status != "PENDING") throw AssignmentStateConflict("assignment proposal was already decided")
                if (value.makerId == checker) throw MakerCheckerViolation("maker cannot decide their own assignment")
                val now = clock.instant()
                value.status = if (approve) "APPROVED" else "REJECTED"
                value.checkerId = checker
                value.decidedAt = now
                if (!approve) {
                    session.persist(audit(value, "REJECTED", checker, null)).replaceWith(value)
                } else {
                    val assignment = CaseAssignmentEntity().apply {
                        this.id = Ids.newId()
                        this.bankScope = this@AssignmentAdministrationService.bankScope
                        principalId = value.principalId
                        caseId = value.caseId
                        purpose = value.purpose
                        validFrom = value.validFrom
                        validTo = value.validTo
                        createdAt = now
                    }
                    assignmentId = assignment.id
                    value.assignmentId = assignment.id
                    session.persist(assignment).flatMap {
                        session.persist(audit(value, "APPROVED", checker, assignment.id))
                    }.replaceWith(value)
                }
            }
        }
        return proposal.response(assignmentId)
    }

    suspend fun revoke(id: UUID, actor: String) {
        transaction { session ->
            session.find(CaseAssignmentEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE).flatMap { found ->
                val assignment = found ?: throw NotFoundException("assignment not found")
                if (assignment.bankScope != bankScope) throw NotFoundException("assignment not found")
                val now = clock.instant()
                if (assignment.validTo > now) assignment.validTo = now
                session.persist(audit(assignment, actor, now))
            }
        }
    }

    private fun validate(request: ProposeAssignmentRequest, validFrom: Instant, now: Instant) {
        require(request.principalId.isNotBlank() && request.principalId.length <= MAX_PRINCIPAL_LENGTH) {
            "invalid principalId"
        }
        require(request.caseId.isNotBlank() && request.caseId.length <= MAX_CASE_LENGTH) { "invalid caseId" }
        require(request.purpose in ALLOWED_PURPOSES) { "invalid purpose" }
        require(
            validFrom >= now.minus(MAX_CLOCK_SKEW) &&
                request.validTo > validFrom &&
                request.validTo <= validFrom.plus(Duration.ofDays(MAX_VALIDITY_DAYS)),
        ) {
            "validity must start now or later and last no more than 31 days"
        }
    }

    private fun audit(p: AssignmentProposalEntity, action: String, actor: String, assignmentId: UUID?) =
        AssignmentChangeAuditEntity().apply {
            id = Ids.newId()
            bankScope = p.bankScope
            this.assignmentId = assignmentId
            proposalId = p.id
            this.action = action
            actorId = actor
            subjectId = p.principalId
            caseId = p.caseId
            purpose = p.purpose
            occurredAt = clock.instant()
        }

    private fun audit(a: CaseAssignmentEntity, actor: String, now: Instant) = AssignmentChangeAuditEntity().apply {
        id = Ids.newId()
        bankScope = a.bankScope
        assignmentId = a.id
        action = "REVOKED"
        actorId = actor
        subjectId = a.principalId
        caseId = a.caseId
        purpose = a.purpose
        occurredAt = now
    }

    private suspend fun transaction(block: (Mutiny.Session) -> Uni<*>): Unit =
        timed(sessions.withTransaction { session, _ -> block(session) }).let { }

    private suspend fun <T> transactionResult(block: (Mutiny.Session) -> Uni<T>): T =
        timed(sessions.withTransaction { session, _ -> block(session) })

    private suspend fun <T> timed(operation: Uni<T>): T = operation
        .ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()

    private companion object {
        const val MAX_QUEUE_SIZE = 200
        const val MAX_PRINCIPAL_LENGTH = 200
        const val MAX_CASE_LENGTH = 200
        const val MAX_VALIDITY_DAYS = 31L
        val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        val ALLOWED_PURPOSES = setOf("PAYMENT_COMPLAINT", "INCIDENT_IMPACT")
    }
}

@Path("/api/v1/context/assignment-proposals")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN")
class AssignmentAdministrationResource(
    private val service: AssignmentAdministrationService,
    private val identity: SecurityIdentity,
) {
    @POST
    @Authorize(action = "context.assignment.propose")
    suspend fun propose(request: ProposeAssignmentRequest?): Response =
        Response.status(Response.Status.CREATED).entity(service.propose(requireNotNull(request), actor())).build()

    @GET
    @Authorize(action = "context.assignment.read")
    suspend fun pending(@QueryParam("limit") limit: Int?): List<AssignmentProposalResponse> =
        service.pending(limit ?: DEFAULT_LIMIT)

    @GET
    @Path("/assignments")
    @Authorize(action = "context.assignment.read")
    suspend fun active(@QueryParam("limit") limit: Int?): List<ActiveAssignmentResponse> =
        service.active(limit ?: DEFAULT_LIMIT)

    @PATCH
    @Path("/{id}")
    @Authorize(action = "context.assignment.decide", resource = "#id")
    suspend fun decide(@PathParam("id") id: UUID, request: DecideAssignmentRequest?): AssignmentProposalResponse =
        service.decide(id, requireNotNull(request).approve, actor())

    @DELETE
    @Path("/assignments/{id}")
    @Authorize(action = "context.assignment.revoke", resource = "#id")
    suspend fun revoke(@PathParam("id") id: UUID): Response {
        service.revoke(id, actor())
        return Response.noContent().build()
    }

    private fun actor(): String = identity.principal.name

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

private fun AssignmentProposalEntity.response(assignmentId: UUID? = null) = AssignmentProposalResponse(
    id,
    principalId,
    caseId,
    purpose,
    validFrom,
    validTo,
    status,
    makerId,
    checkerId,
    createdAt,
    decidedAt,
    assignmentId ?: this.assignmentId,
)
