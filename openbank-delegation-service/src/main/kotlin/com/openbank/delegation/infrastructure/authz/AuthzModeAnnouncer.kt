// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.authz

import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Announce the effective authorization mode once, at boot.
 *
 * `AuthorizeInterceptor` reads `authz.enforce` with `defaultValue = "true"`, and this service ships
 * its first rollout with `AUTHZ_ENFORCE=false` (ADR-0034 D5 phased rollout): every `@Authorize`
 * decision is evaluated and recorded, and none of them block. That is a deliberate window, and it
 * ends when the `outcome=deny, enforced=false` population is confirmed empty — but **nothing said
 * which mode a running pod was in**. You had to read the GitOps manifest and trust it matched the
 * pod, which is exactly the kind of claim that drifts silently.
 *
 * `@Startup` is load-bearing, not decoration. `@ApplicationScoped` is LAZY: Quarkus creates the
 * bean through a client proxy on first use, so a `@PostConstruct` that exists to state a boot-time
 * fact never runs until something else touches the bean — and for a bean nothing injects, that is
 * never. `PdfBoxPadesSealAdapter` warned that every PAdES seal was "worthless as evidence" without
 * a real keystore, and that warning had never once appeared in a pod log (#1299). `@Startup` forces
 * instantiation at boot, which is the only thing that makes this line appear at all.
 *
 * Deliberately logged at WARN while advisory: an authorization layer that is evaluating but not
 * blocking is a temporary state someone has to come back to, and INFO is where such things go to
 * die in a log aggregator.
 *
 * Belongs in `openbank-libs-runtime` next to the interceptor so the whole fleet gets it — every
 * service carries the same advisory window and the same blind spot. Kept here for now because a
 * libs change rebuilds every consumer; see the follow-up note in the PR.
 */
@Startup
@ApplicationScoped
class AuthzModeAnnouncer {

    @ConfigProperty(name = "authz.enforce", defaultValue = "true")
    var enforce: Boolean = true

    @ConfigProperty(name = "authz.four-eyes.enforce", defaultValue = "false")
    var fourEyesEnforce: Boolean = false

    @PostConstruct
    fun announce() {
        if (enforce) {
            log.info("authz: ENFORCING — a policy deny returns 403 (four-eyes enforce=$fourEyesEnforce)")
        } else {
            log.warn(
                "authz: ADVISORY (authz.enforce=false) — every @Authorize decision is evaluated and " +
                    "recorded but NONE of them block. Deliberate for the phased rollout (ADR-0034 D5); " +
                    "flip to enforce once openbank.authz.decisions{outcome=\"deny\",enforced=\"false\"} " +
                    "is confirmed empty (four-eyes enforce=$fourEyesEnforce)",
            )
        }
    }

    private companion object {
        val log: Logger = Logger.getLogger(AuthzModeAnnouncer::class.java)
    }
}
