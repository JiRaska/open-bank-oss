// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.infrastructure.rest

import com.openbank.ap2.application.Ap2MandateVerifier
import com.openbank.ap2.application.port.out.Ap2AuthorizationOutcome
import com.openbank.ap2.application.port.out.Ap2MetricsPort
import com.openbank.ap2.domain.Ap2Mandate
import com.openbank.ap2.domain.MandateVerdict
import com.openbank.ap2.domain.PresentedPayment
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import io.quarkus.security.Authenticated
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.jwt.JsonWebToken
import org.jboss.logging.Logger

/**
 * The AP2 mandate verification surface (ADR-0193): POST /ap2/verify takes a presented mandate + the
 * payment it is offered to authorize, and returns a [MandateVerdict] (valid + evidence + failures).
 * It moves NO funds — it is authorization evidence only.
 *
 * Every call is authorized through the SHARED ADR-0034 PDP as an `AI_AGENT` principal (action
 * `verify.mandate`), the same plane as openbank-mcp-service (ADR-0181). Deny-by-default via a single
 * capability; a PDP outage fails CLOSED. The acting agent is the validated OIDC access token's
 * `sub`, never a caller-controlled header. Until a real agent is onboarded, this surface is closed.
 *
 * Because the PDP is called **directly** rather than through the `@Authorize` interceptor, this
 * surface emits no `openbank_authz_decisions_total` — so its own decisions are counted on
 * [Ap2MetricsPort]. `pdp_unavailable` matters most: it denies every agent, correctly and silently.
 */
@Path("/ap2/verify")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class Ap2VerifyEndpoint(
    private val verifier: Ap2MandateVerifier,
    private val pdp: PolicyDecisionPoint,
    private val metrics: Ap2MetricsPort,
    private val jwt: JsonWebToken,
) {

    private val log = Logger.getLogger(Ap2VerifyEndpoint::class.java)

    @POST
    @Authenticated
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun verify(request: VerifyRequest): Response {
        val agentId = jwt.subject?.takeIf { it.startsWith(AGENT_PREFIX) && it.length > AGENT_PREFIX.length }
            ?: return Response.status(Response.Status.FORBIDDEN)
                .entity(mapOf("error" to "agent identity required"))
                .build()

        val decision = try {
            runBlocking {
                pdp.allow(
                    AuthzQuery(
                        principal = Principal(id = agentId, type = "AI_AGENT"),
                        action = VERIFY_CAPABILITY,
                        resource = null,
                        attributes = mapOf("mandateKind" to request.mandate.kind.name),
                    ),
                )
            }
        } catch (ex: Exception) {
            // Fail closed: a PDP outage denies (never fail-open on a payment-authorization surface).
            log.warnf("PDP error authorizing verify.mandate: %s — denying", ex.message)
            metrics.authorizationDecision(Ap2AuthorizationOutcome.PDP_UNAVAILABLE)
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(mapOf("error" to "authorization unavailable")).build()
        }
        if (!decision.allow) {
            metrics.authorizationDecision(Ap2AuthorizationOutcome.DENIED)
            return Response.status(Response.Status.FORBIDDEN)
                .entity(mapOf("error" to "denied by policy", "reason" to (decision.reason ?: "no matching allow rule")))
                .build()
        }
        metrics.authorizationDecision(Ap2AuthorizationOutcome.ALLOWED)

        val verdict: MandateVerdict = verifier.verify(request.mandate, request.payment)
        return Response.ok(verdict).build()
    }

    private companion object {
        const val AGENT_PREFIX = "agent:"
        const val VERIFY_CAPABILITY = "verify.mandate"
    }
}

/** Request body: the presented mandate and the payment it is offered to authorize. */
data class VerifyRequest(val mandate: Ap2Mandate, val payment: PresentedPayment)
