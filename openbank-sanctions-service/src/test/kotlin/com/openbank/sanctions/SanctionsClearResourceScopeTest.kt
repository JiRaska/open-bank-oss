// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions

import com.openbank.libs.authz.Authorize
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.infrastructure.rest.SanctionsResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties

/**
 * `sanctions.clear` must bind its four-eyes approval to the specific check being decided.
 *
 * `AuthorizeInterceptor.satisfies` matches an approval on the triple (action, resourceId, maker).
 * With `resource = ""` every approval a maker held satisfied every review they attempted — a
 * checker approving "clear check A" unknowingly authorised "clear check B", reachable by replaying
 * the same `X-Approval-Id` against a different `checkId`. Harmless while nothing drove the flow;
 * #3465 gave it a UI.
 *
 * Asserted against the ANNOTATION VALUE and the resolvability of the field it names — not a
 * whole-file grep. A grep for `#cmd.checkId` would also match this file's own KDoc, and one for
 * `resource = ""` would keep passing on any endpoint that still has it. The construct is the thing.
 */
class SanctionsClearResourceScopeTest {

    private val reviewAnnotation: Authorize = SanctionsResource::class.functions
        .single { it.name == "review" }
        .findAnnotation<Authorize>()
        ?: error("SanctionsResource.review lost its @Authorize annotation")

    @Test
    fun `review is gated on sanctions clear, scoped to the check being decided`() {
        assertThat(reviewAnnotation.action).isEqualTo("sanctions.clear")
        assertThat(reviewAnnotation.resource)
            .describedAs("an empty resource makes one approval a bearer token for every review by that maker")
            .isEqualTo("#cmd.checkId")
    }

    @Test
    fun `the resource expression names a field the interceptor can actually resolve`() {
        // ADR-0206 dotted-path extraction is reflective and FAILS CLOSED: an unknown field name
        // silently yields no resource, which would put this endpoint straight back to the
        // unscoped behaviour with nothing going red. So the expression is only as good as the
        // field existing — assert that, not just the string.
        val expression = reviewAnnotation.resource.removePrefix("#")
        val (paramName, fieldName) = expression.split(".", limit = 2)

        val parameter = SanctionsResource::class.functions
            .single { it.name == "review" }
            .parameters
            .single { it.name == paramName }
        assertThat(parameter.type.classifier).isEqualTo(ReviewCommand::class)

        assertThat(ReviewCommand::class.memberProperties.map { it.name })
            .describedAs("%s.%s must resolve, or the interceptor falls back to no resource", paramName, fieldName)
            .contains(fieldName)
    }
}
