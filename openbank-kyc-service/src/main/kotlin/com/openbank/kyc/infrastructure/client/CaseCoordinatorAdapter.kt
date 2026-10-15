// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.CaseCoordinatorPort
import com.openbank.kyc.application.port.out.CaseOpenOutcome
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.Optional
import java.util.UUID

/**
 * Maps case-coordinator-agent's case-open endpoint onto [CaseCoordinatorPort], implementing the
 * ADR-0256 contract semantics:
 *
 * - `201` → [CaseOpenOutcome.Opened] with the returned case id.
 * - `409` → [CaseOpenOutcome.AlreadyExists] — an idempotent duplicate, treated as success so
 *   Kafka at-least-once delivery and the (publication, party) fan-out never double-book work.
 * - `429` / `503` / timeout / exhausted retries → [CaseOpenOutcome.Retryable] — the caller
 *   persists the failure durably; the retry job re-attempts after backoff.
 * - other 4xx (bad request, asserted-identity 403) → [CaseOpenOutcome.Rejected] — deterministic,
 *   parked immediately without burning the retry budget.
 *
 * The base URL is [Optional] per the fleet convention (`@ConfigProperty` optional fields must be
 * `Optional<String>`): local/dev boots without a coordinator. That is NOT a silent skip — a
 * missing URL maps to [ScreeningTriggerFailure.NOT_CONFIGURED] so every affected trigger lands
 * in the durable failure path, visible in the operator queue. Production wiring must set the URL
 * (gitops env) and keep the adapter behind `openbank.kyc.screening-triggers.enabled` until the
 * rollout criteria in the ADR-0256 contract are met.
 */
@ApplicationScoped
class CaseCoordinatorAdapter(
    @RestClient private val client: CaseCoordinatorClient,
    @ConfigProperty(name = "openbank.kyc.case-coordinator.url") private val baseUrl: Optional<String>,
    private val objectMapper: ObjectMapper,
) : CaseCoordinatorPort {

    @Inject
    lateinit var self: CaseCoordinatorAdapter

    private val log = Logger.getLogger(CaseCoordinatorAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun openAmlAlertCase(partyId: UUID): CaseOpenOutcome {
        val url = baseUrl.filter { it.isNotBlank() }.orElse(null)
            ?: return CaseOpenOutcome.Rejected(
                ScreeningTriggerFailure.NOT_CONFIGURED,
                "openbank.kyc.case-coordinator.url is not set",
            )
        return try {
            classify(self.openWithResilience(partyId, url), partyId)
        } catch (e: Exception) {
            // Transport fault, timeout, open circuit breaker, exhausted @Retry — all mean "the
            // coordinator may recover", which is the Retryable contract. TooGenericExceptionCaught
            // is deliberate: the outcome taxonomy exists precisely so callers don't enumerate
            // exception types (mirrors SanctionsScreeningAdapter's degradation catch).
            CaseOpenOutcome.Retryable("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @CircuitBreaker(
        requestVolumeThreshold = CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = CB_FAILURE_RATIO,
        delay = CB_DELAY_MS,
        successThreshold = CB_SUCCESS_THRESHOLD,
    )
    @Retry(
        maxRetries = RETRY_MAX_RETRIES,
        delay = RETRY_DELAY_MS,
        jitter = RETRY_JITTER_MS,
        retryOn = [Exception::class],
    )
    @Timeout(TIMEOUT_MS)
    open suspend fun openWithResilience(partyId: UUID, url: String): Response {
        // The reactive rest-client resolves its base URL from quarkus.rest-client.case-coordinator.url;
        // the explicit guard above exists so an UNSET url fails visible here instead of deep inside
        // the client stack with a less diagnosable fault.
        log.debugf("case-coordinator base url: %s", url)
        return client.openCase(
            OpenCaseRequest(
                caseClass = CASE_CLASS,
                subjectRef = partyId.toString(),
                openedBy = OPENED_BY,
                dispositionTarget = DISPOSITION_TARGET,
            ),
        ).awaitSuspending()
    }

    private fun classify(response: Response, partyId: UUID): CaseOpenOutcome = when (response.status) {
        Response.Status.CREATED.statusCode -> {
            val caseId = runCatching {
                objectMapper.readValue(response.readEntity(String::class.java), JsonNode::class.java)
                    .path("caseId").asText(null)
            }.getOrNull()
            if (caseId.isNullOrBlank()) {
                log.errorf("case-coordinator returned 201 without a caseId for party %s", partyId)
                CaseOpenOutcome.Rejected(
                    ScreeningTriggerFailure.COORDINATOR_REJECTED,
                    "201 response without a caseId",
                )
            } else {
                CaseOpenOutcome.Opened(caseId)
            }
        }
        Response.Status.CONFLICT.statusCode -> CaseOpenOutcome.AlreadyExists
        Response.Status.TOO_MANY_REQUESTS.statusCode,
        Response.Status.SERVICE_UNAVAILABLE.statusCode,
        -> CaseOpenOutcome.Retryable("HTTP ${response.status}")
        else -> if (response.status >= HTTP_SERVER_ERROR_THRESHOLD) {
            CaseOpenOutcome.Retryable("HTTP ${response.status}")
        } else {
            CaseOpenOutcome.Rejected(ScreeningTriggerFailure.COORDINATOR_REJECTED, "HTTP ${response.status}")
        }
    }

    private companion object {
        // First server-error status: anything at or above it that is not 503 is still a
        // retryable coordinator-side fault.
        const val HTTP_SERVER_ERROR_THRESHOLD = 500

        // Wire values pinned by docs/design/adr-0256-kyc-case-coordinator-contract.md.
        const val CASE_CLASS = "AML_ALERT"
        const val OPENED_BY = "kyc-service"
        const val DISPOSITION_TARGET = "kyc-rescreen"

        // Resilience tuning mirrors SanctionsScreeningAdapter (ADR-0032 §D), which protects the
        // sibling sanctions call from the same failure modes.
        const val CB_REQUEST_VOLUME_THRESHOLD = 4
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 10_000L
        const val CB_SUCCESS_THRESHOLD = 2
        const val RETRY_MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 300L
        const val RETRY_JITTER_MS = 150L
        const val TIMEOUT_MS = 5_000L
    }
}
