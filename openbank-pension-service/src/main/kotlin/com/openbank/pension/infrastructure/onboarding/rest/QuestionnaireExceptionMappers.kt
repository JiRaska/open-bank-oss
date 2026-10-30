// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.pension.application.onboarding.InconsistentAnswersException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Contradictory answers are a 422 carrying the codes to review — not a 400: the request is well
 * formed, the participant only has to look again (ESMA consistency check, issue #12384).
 */
class QuestionnaireExceptionMappers {

    @ServerExceptionMapper
    fun inconsistent(e: InconsistentAnswersException): Response = Response.status(UNPROCESSABLE).entity(
        mapOf(
            "error" to e.message,
            "inconsistencies" to e.inconsistencies.map { mapOf("code" to it.code, "questions" to it.questions) },
        ),
    ).build()

    /** 409: the strategy change needs a renewed assessment; the client re-answers on applicationId. */
    @ServerExceptionMapper
    fun reassessment(e: com.openbank.pension.application.port.out.ReassessmentRequiredException): Response =
        Response.status(Response.Status.CONFLICT).entity(
            mapOf(
                "error" to e.message,
                "code" to "REASSESSMENT_REQUIRED",
                "reason" to e.reason.name,
                "applicationId" to e.applicationId.toString(),
            ),
        ).build()

    @ServerExceptionMapper
    fun warningsRequired(e: com.openbank.pension.application.port.out.StrategyWarningsRequiredException): Response =
        Response.status(Response.Status.CONFLICT).entity(
            mapOf("error" to e.message, "code" to "WARNINGS_REQUIRED", "warnings" to e.warnings.map { it.name }),
        ).build()

    @ServerExceptionMapper
    fun notPermitted(e: com.openbank.pension.application.port.out.StrategyNotPermittedException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(
            mapOf("error" to e.message, "code" to "STRATEGY_NOT_PERMITTED"),
        ).build()

    @ServerExceptionMapper
    fun strategySca(e: com.openbank.pension.application.usecase.StrategyChangeScaFailedException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message, "code" to "SCA_REJECTED")).build()

    private companion object {
        const val UNPROCESSABLE = 422
    }
}
