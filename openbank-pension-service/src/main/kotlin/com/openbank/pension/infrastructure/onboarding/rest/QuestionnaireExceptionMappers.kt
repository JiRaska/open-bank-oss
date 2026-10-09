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
    fun inconsistent(e: InconsistentAnswersException): Response =
        Response.status(UNPROCESSABLE).entity(
            mapOf(
                "error" to e.message,
                "inconsistencies" to e.inconsistencies.map { mapOf("code" to it.code, "questions" to it.questions) },
            ),
        ).build()

    private companion object {
        const val UNPROCESSABLE = 422
    }
}
