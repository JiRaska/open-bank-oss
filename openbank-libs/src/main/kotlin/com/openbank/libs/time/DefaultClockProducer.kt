// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.time

import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock

/**
 * Fleet-wide fallback [Clock] producer (ADR-0100 — deterministic time / DST harness).
 *
 * Several libs beans on EVERY service's classpath inject a `java.time.Clock`:
 * [com.openbank.libs.web.ServiceInfoResource], [com.openbank.libs.authz.AuthorizeInterceptor]
 * and [com.openbank.libs.idempotency.impl.RedisIdempotencyStore]. Once the ADR-0100 sweep
 * moved wall-clock reads behind an injected `Clock`, ArC validation requires a `Clock` bean
 * in the deployment — otherwise the build fails with
 * `Unsatisfied dependency for type java.time.Clock` at `quarkusAppPartsBuild` (ArcProcessor#validate).
 * Services that never grew their own `ClockProducer` (e.g. anacredit, consent, dispute, interest)
 * therefore could not compile, yet path-scoped CI never rebuilt them so the breakage only
 * surfaced on a full-fleet build.
 *
 * Providing the producer HERE makes a `Clock` resolvable in every service for free. It is marked
 * [DefaultBean] (`io.quarkus.arc.DefaultBean`) so the ~24 services that already ship their own
 * `@Produces Clock` keep winning — ArC vetoes this default whenever an alternative `Clock` bean
 * with the same `@Default` qualifier exists, so there is no `AmbiguousResolutionException`. The
 * default and any service override both return `Clock.systemUTC()`, so behaviour is identical;
 * the per-service producer remains the seam a DST/timezone test swaps for a fixed clock.
 */
@ApplicationScoped
class DefaultClockProducer {

    @Produces
    @DefaultBean
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}
