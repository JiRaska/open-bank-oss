// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.libs.authz.Authorize
import com.openbank.pensionfund.infrastructure.rest.ContractUnitResource
import com.openbank.pensionfund.infrastructure.rest.FundReportingResource
import com.openbank.pensionfund.infrastructure.rest.FundResource
import com.openbank.pensionfund.infrastructure.rest.LegacyContractUnitResource
import com.openbank.pensionfund.infrastructure.rest.LegacyFundReportingResource
import com.openbank.pensionfund.infrastructure.rest.LegacyFundResource
import com.openbank.pensionfund.infrastructure.rest.LegacyNavResource
import com.openbank.pensionfund.infrastructure.rest.LegacyPositionClassificationResource
import com.openbank.pensionfund.infrastructure.rest.LegacyStrategyChangeResource
import com.openbank.pensionfund.infrastructure.rest.LegacyStrategyResource
import com.openbank.pensionfund.infrastructure.rest.NavResource
import com.openbank.pensionfund.infrastructure.rest.PositionClassificationResource
import com.openbank.pensionfund.infrastructure.rest.StrategyChangeResource
import com.openbank.pensionfund.infrastructure.rest.StrategyResource
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.openapi.annotations.Operation
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** Explicit compatibility boundaries must not drift to a different authorization action or role. */
class PensionFundVersionAuthorizationParityTest {
    @Test
    fun `each retained legacy operation declares the same security boundary as v2`() {
        val pairs = listOf(
            FundResource::class.java to LegacyFundResource::class.java,
            NavResource::class.java to LegacyNavResource::class.java,
            StrategyResource::class.java to LegacyStrategyResource::class.java,
            StrategyChangeResource::class.java to LegacyStrategyChangeResource::class.java,
            ContractUnitResource::class.java to LegacyContractUnitResource::class.java,
            FundReportingResource::class.java to LegacyFundReportingResource::class.java,
            PositionClassificationResource::class.java to LegacyPositionClassificationResource::class.java,
        )
        pairs.forEach { (current, legacy) ->
            assertThat(legacy.getAnnotation(Path::class.java).value)
                .isEqualTo(current.getAnnotation(Path::class.java).value.replace("/api/v2", "/api/v1"))
            // Kotlin's open suspend functions also emit static $suspendImpl helpers. They are
            // implementation details, not endpoint declarations, even when annotations are copied.
            val methods = legacy.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    !method.isSynthetic &&
                    !method.isBridge &&
                    method.annotations.any { it.annotationClass.java.isAnnotationPresent(HttpMethod::class.java) }
            }
            assertThat(methods).isNotEmpty
            methods.forEach { method ->
                val canonical = current.declaredMethods.single { candidate ->
                    !Modifier.isStatic(candidate.modifiers) &&
                        !candidate.isSynthetic &&
                        !candidate.isBridge &&
                        candidate.name == method.name &&
                        (
                            candidate.parameterTypes.toList() == method.parameterTypes.toList() ||
                                candidate.parameters.filterNot {
                                    it.getAnnotation(HeaderParam::class.java)?.value == "Idempotency-Key"
                                }
                                    .map { it.type } == method.parameterTypes.toList()
                            )
                }
                val roles = canonical.getAnnotation(RolesAllowed::class.java)
                val action = canonical.getAnnotation(Authorize::class.java)
                assertThat(roles).isNotNull
                assertThat(action).isNotNull
                assertThat(method.getAnnotation(RolesAllowed::class.java)).isEqualTo(roles)
                assertThat(method.getAnnotation(Authorize::class.java)).isEqualTo(action)
                assertThat(method.getAnnotation(Operation::class.java).deprecated).isTrue()
            }
        }
        assertThat(LegacyFundResource::class.java.declaredMethods.map { it.name })
            .doesNotContain("create", "amend")
    }
}
