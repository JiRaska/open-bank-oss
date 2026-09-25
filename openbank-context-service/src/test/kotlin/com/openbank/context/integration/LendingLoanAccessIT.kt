// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class LendingLoanAccessIT {
    @Inject lateinit var assignments: AssignmentAdministrationService

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_CREDIT_RISK"])
    fun `loan access requires exact approved assignment and stops after revocation`() {
        val loanId = UUID.randomUUID()
        request(loanId).then().statusCode(403)
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(
                    ACTOR,
                    loanId.toString(),
                    PURPOSE,
                    null,
                    Instant.now().plusSeconds(3600),
                    "lending-loan:$loanId",
                ),
                "lending-maker",
            )
        }
        val assignmentId = requireNotNull(
            onVertx { assignments.decide(proposal.id, true, "lending-checker") }.assignmentId,
        )
        request(loanId).then().statusCode(204).header("Cache-Control", "no-store")
            .header("Content-Type", nullValue())
        request(UUID.randomUUID()).then().statusCode(403)
        given().header("X-Investigation-Case-Id", UUID.randomUUID().toString())
            .header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/context/lending-loans/$loanId/access").then().statusCode(400)
        onVertx { assignments.revoke(assignmentId, "lending-revoker") }
        request(loanId).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `operator cannot check Lending loan access`() {
        request(UUID.randomUUID()).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-lending", roles = ["ROLE_ADMIN"])
    fun `service identity cannot substitute for investigator`() {
        request(UUID.randomUUID()).then().statusCode(403)
    }

    private fun request(loanId: UUID) = given()
        .header("X-Investigation-Case-Id", loanId.toString())
        .header("X-Investigation-Purpose", PURPOSE)
        .get("/api/v1/context/lending-loans/$loanId/access")

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val ACTOR = "lending-loan-reviewer"
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
