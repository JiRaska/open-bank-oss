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

    @Column(name = "root_ref")
    var rootRef: String? = null

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

    @Column(name = "root_ref")
    var rootRef: String? = null

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant
}

data class ProposeAssignmentRequest(
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val validFrom: Instant?,
    val validTo: Instant,
    val rootRef: String? = null,
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
    val rootRef: String? = null,
)

data class ActiveAssignmentResponse(
    val id: UUID,
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val validFrom: Instant,
    val validTo: Instant,
    val rootRef: String? = null,
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
            rootRef = request.rootRef?.let { root ->
                when (request.purpose) {
                    "AUTHORIZATION_REVIEW" -> "delegation:${UUID.fromString(root.removePrefix("delegation:"))}"
                    "AML_INVESTIGATION" -> "aml-case:${UUID.fromString(root.removePrefix("aml-case:"))}"
                    "KYB_OWNERSHIP_REVIEW" -> "kyb-case:${UUID.fromString(root.removePrefix("kyb-case:"))}"
                    "LENDING_EXPOSURE_REVIEW" -> "lending-loan:${UUID.fromString(root.removePrefix("lending-loan:"))}"
                    "INCIDENT_IMPACT" -> "incident:${UUID.fromString(root.removePrefix("incident:"))}"
                    else -> root
                }
            }
            this.validFrom = validFrom
            validTo = request.validTo
            status = "PENDING"
            makerId = maker
            createdAt = now
        }
        transaction { operation ->
            operation.sql { session -> session.persist(proposal) }.flatMap {
                operation.sql { session -> session.persist(audit(proposal, "PROPOSED", maker, null)) }
            }
        }
        return proposal.response()
    }

    suspend fun pending(limit: Int): List<AssignmentProposalResponse> = read { session ->
        session.createQuery(
            "from AssignmentProposalEntity where bankScope = :bankScope and status = 'PENDING' order by createdAt",
            AssignmentProposalEntity::class.java,
        ).setParameter("bankScope", bankScope).setMaxResults(limit.coerceIn(1, MAX_QUEUE_SIZE)).resultList
    }.map { it.response() }

    suspend fun active(limit: Int): List<ActiveAssignmentResponse> {
        val now = clock.instant()
        return read { session ->
            session.createQuery(
                "from CaseAssignmentEntity where bankScope = :bankScope and validFrom <= :now and validTo > :now order by validTo",
                CaseAssignmentEntity::class.java,
            ).setParameter("bankScope", bankScope).setParameter("now", now)
                .setMaxResults(limit.coerceIn(1, MAX_QUEUE_SIZE)).resultList
        }.map {
            ActiveAssignmentResponse(it.id, it.principalId, it.caseId, it.purpose, it.validFrom, it.validTo, it.rootRef)
        }
    }

    @Suppress("ThrowsCount")
    suspend fun decide(id: UUID, approve: Boolean, checker: String): AssignmentProposalResponse {
        var assignmentId: UUID? = null
        val proposal = transactionResult { operation ->
            operation.sql { session ->
                session.find(AssignmentProposalEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE)
            }.flatMap { found ->
                val value = found ?: throw NotFoundException("assignment proposal not found")
                if (value.bankScope != bankScope) throw NotFoundException("assignment proposal not found")
                if (value.status != "PENDING") throw AssignmentStateConflict("assignment proposal was already decided")
                if (value.makerId == checker) throw MakerCheckerViolation("maker cannot decide their own assignment")
                val now = clock.instant()
                value.status = if (approve) "APPROVED" else "REJECTED"
                value.checkerId = checker
                value.decidedAt = now
                if (!approve) {
                    operation.sql { session -> session.persist(audit(value, "REJECTED", checker, null)) }
                        .replaceWith(value)
                } else {
                    val assignment = CaseAssignmentEntity().apply {
                        this.id = Ids.newId()
                        this.bankScope = this@AssignmentAdministrationService.bankScope
                        principalId = value.principalId
                        caseId = value.caseId
                        purpose = value.purpose
                        rootRef = value.rootRef
                        validFrom = value.validFrom
                        validTo = value.validTo
                        createdAt = now
                    }
                    assignmentId = assignment.id
                    operation.sql { session -> session.persist(assignment) }.flatMap {
                        value.assignmentId = assignment.id
                        operation.sql { session -> session.persist(audit(value, "APPROVED", checker, assignment.id)) }
                    }.replaceWith(value)
                }
            }
        }
        return proposal.response(assignmentId)
    }

    suspend fun revoke(id: UUID, actor: String) {
        transaction { operation ->
            operation.sql { session ->
                session.find(CaseAssignmentEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE)
            }.flatMap { found ->
                val assignment = found ?: throw NotFoundException("assignment not found")
                if (assignment.bankScope != bankScope) throw NotFoundException("assignment not found")
                val now = clock.instant()
                if (assignment.validTo > now) assignment.validTo = now
                operation.sql { session -> session.persist(audit(assignment, actor, now)) }
            }
        }
    }

    private fun validate(request: ProposeAssignmentRequest, validFrom: Instant, now: Instant) {
        require(request.principalId.isNotBlank() && request.principalId.length <= MAX_PRINCIPAL_LENGTH) {
            "invalid principalId"
        }
        require(request.caseId.isNotBlank() && request.caseId.length <= MAX_CASE_LENGTH) { "invalid caseId" }
        require(request.purpose in ALLOWED_PURPOSES) { "invalid purpose" }
        validateRoot(request)
        require(
            validFrom >= now.minus(MAX_CLOCK_SKEW) &&
                request.validTo > validFrom &&
                request.validTo <= validFrom.plus(Duration.ofDays(MAX_VALIDITY_DAYS)),
        ) {
            "validity must start now or later and last no more than 31 days"
        }
    }

    private fun validateRoot(request: ProposeAssignmentRequest) {
        if (request.purpose == "AUTHORIZATION_REVIEW") {
            val root = requireNotNull(request.rootRef) { "AUTHORIZATION_REVIEW requires a delegation root" }
            require(root.startsWith("delegation:") && root.length == DELEGATION_ROOT_LENGTH) {
                "invalid delegation root"
            }
            UUID.fromString(root.removePrefix("delegation:"))
        } else if (request.purpose == "AML_INVESTIGATION") {
            val root = requireNotNull(request.rootRef) { "AML_INVESTIGATION requires an AML case root" }
            require(root.startsWith("aml-case:") && root.length == AML_CASE_ROOT_LENGTH) { "invalid AML case root" }
            val id = UUID.fromString(root.removePrefix("aml-case:"))
            require(request.caseId == id.toString()) { "the investigation case must match the AML source case" }
        } else if (request.purpose == "KYB_OWNERSHIP_REVIEW") {
            val root = requireNotNull(request.rootRef) { "KYB_OWNERSHIP_REVIEW requires a KYB case root" }
            require(root.startsWith("kyb-case:") && root.length == KYB_CASE_ROOT_LENGTH) {
                "invalid KYB case root"
            }
            val id = UUID.fromString(root.removePrefix("kyb-case:"))
            require(request.caseId == id.toString()) { "the investigation case must match the KYB source case" }
        } else if (request.purpose == "LENDING_EXPOSURE_REVIEW") {
            val root = requireNotNull(request.rootRef) { "LENDING_EXPOSURE_REVIEW requires a Lending loan root" }
            require(root.startsWith("lending-loan:") && root.length == LENDING_LOAN_ROOT_LENGTH) {
                "invalid Lending loan root"
            }
            val id = UUID.fromString(root.removePrefix("lending-loan:"))
            require(request.caseId == id.toString()) { "the investigation case must match the Lending loan" }
        } else if (request.purpose == "INCIDENT_IMPACT") {
            val root = requireNotNull(request.rootRef) { "INCIDENT_IMPACT requires an incident root" }
            require(root.startsWith("incident:") && root.length == INCIDENT_ROOT_LENGTH) { "invalid incident root" }
            UUID.fromString(root.removePrefix("incident:"))
        } else if (request.purpose == "PAYMENT_COMPLAINT") {
            val root = requireNotNull(request.rootRef) { "PAYMENT_COMPLAINT requires a complaint root" }
            require(
                root.startsWith("complaint:") &&
                    root.length in MIN_COMPLAINT_ROOT_LENGTH..MAX_COMPLAINT_ROOT_LENGTH &&
                    COMPLAINT_ROOT_PATTERN.matches(root),
            ) { "invalid complaint root" }
        } else {
            require(request.rootRef == null) { "root scope is not supported for this purpose" }
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
            rootRef = p.rootRef
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
        rootRef = a.rootRef
        occurredAt = now
    }

    private suspend fun transaction(block: (ContextSqlOperation) -> Uni<*>): Unit =
        ContextSqlOperation.execute(sessions, timeoutMs) { operation ->
            block(operation).flatMap { operation.sql { session -> session.flush() } }
        }.awaitSuspending().let { }

    private suspend fun <T> transactionResult(block: (ContextSqlOperation) -> Uni<T>): T =
        ContextSqlOperation.execute(sessions, timeoutMs) { operation ->
            block(operation).flatMap { result -> operation.sql { session -> session.flush() }.replaceWith(result) }
        }.awaitSuspending()

    private suspend fun <T> read(statement: (Mutiny.Session) -> Uni<T>): T =
        ContextSqlOperation.execute(sessions, timeoutMs) { operation -> operation.sql(statement) }.awaitSuspending()

    private companion object {
        const val DELEGATION_ROOT_LENGTH = 47
        const val AML_CASE_ROOT_LENGTH = 45
        const val KYB_CASE_ROOT_LENGTH = 45
        const val LENDING_LOAN_ROOT_LENGTH = 49
        const val INCIDENT_ROOT_LENGTH = 45
        const val MIN_COMPLAINT_ROOT_LENGTH = 11
        const val MAX_COMPLAINT_ROOT_LENGTH = 210
        val COMPLAINT_ROOT_PATTERN = Regex("complaint:[A-Za-z0-9._:-]+")
        const val MAX_QUEUE_SIZE = 200
        const val MAX_PRINCIPAL_LENGTH = 200
        const val MAX_CASE_LENGTH = 200
        const val MAX_VALIDITY_DAYS = 31L
        val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        val ALLOWED_PURPOSES =
            setOf(
                "PAYMENT_COMPLAINT",
                "INCIDENT_IMPACT",
                "AUTHORIZATION_REVIEW",
                "AML_INVESTIGATION",
                "KYB_OWNERSHIP_REVIEW",
                "LENDING_EXPOSURE_REVIEW",
            )
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
    rootRef,
)
