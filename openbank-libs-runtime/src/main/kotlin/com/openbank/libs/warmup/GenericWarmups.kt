// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import io.quarkus.arc.Arc
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant

/** The framework-touching warm-up steps [StartupWarmup] runs; each returns a short log detail. */
internal object GenericWarmups {
    /** Action name of the synthetic authz probe; no policy grants it, so the decision is a deny. */
    const val WARMUP_ACTION = "openbank.warmup.probe"

    fun resourceTypes(objectMappers: Instance<ObjectMapper>): String {
        if (!objectMappers.isResolvable) return "no ObjectMapper bean"
        val beanClasses = Arc.container().beanManager()
            .getBeans(Object::class.java, jakarta.enterprise.inject.Any.Literal.INSTANCE)
            .map { it.beanClass }
        return ResourceTypeWarmup.warm(objectMappers.get(), beanClasses)
    }

    fun entities(deadline: Instant, perEntity: Duration): String {
        try {
            Class.forName(HibernateReactiveWarmup.ENTRY_CLASS, false, javaClass.classLoader)
        } catch (@Suppress("SwallowedException") e: ClassNotFoundException) {
            return "no Hibernate Reactive on classpath"
        }
        return HibernateReactiveWarmup.warm(deadline, perEntity)
    }

    /**
     * One decision for a synthetic ANONYMOUS principal on [WARMUP_ACTION], so nothing is
     * authorized; what it warms is the PDP client (HTTP client, input serialization, response
     * parsing) the first real `@Authorize` call otherwise pays for. The sidecar's decision log
     * records it, attributable by its action name.
     */
    fun authz(pdps: Instance<PolicyDecisionPoint>, timeout: Duration): String {
        if (!pdps.isResolvable) return "no PolicyDecisionPoint bean"
        val pdp = pdps.get()
        val decision = runBlocking {
            withTimeout(timeout.toMillis()) {
                pdp.allow(AuthzQuery(Principal(id = "openbank-warmup", type = "ANONYMOUS"), action = WARMUP_ACTION))
            }
        }
        return "decision allow=${decision.allow}"
    }
}
