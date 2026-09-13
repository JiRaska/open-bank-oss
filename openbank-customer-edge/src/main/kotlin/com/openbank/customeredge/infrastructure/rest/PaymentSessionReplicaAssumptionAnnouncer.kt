// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Announce, once at boot, that [PaymentSessionStore] depends on `customer-edge` running
 * `replicas: 1` (issue #4728).
 *
 * [PaymentSessionStore] itself already documents the tradeoff in its class doc — "In-memory is
 * acceptable because the loss mode is benign… For multi-replica edge the token would need a
 * shared TTL cache (Redis) — tracked as a follow-up; single-replica sandbox is fine." That
 * follow-up is issue #4728, and #3806 proposes raising exactly the replica counts this depends
 * on. What was missing is not the reasoning — it is anything a person changing `replicas` would
 * actually encounter. A doc comment on a class nobody opens before editing gitops is silent in
 * exactly the way that matters.
 *
 * **This is a declared assumption, not a live check.** A pod has no way to learn its own current
 * replica count from inside the process: there is no Kubernetes downward-API field for it (it is
 * a property of the parent Rollout/Deployment, not the pod), `customer-edge` carries no
 * Kubernetes API client to read the `Rollout` object, and nothing in this fleet does either
 * (checked before writing this). So this warning fires on **every** boot, regardless of the live
 * replica count — it cannot conditionally fire only when the assumption is actually violated.
 * Building a fake conditional check would be worse than an honest unconditional one: it would
 * look like verification and would not be.
 *
 * `@Startup` is load-bearing, not decoration, per the same reasoning as
 * `AuthzModeAnnouncer`/`PdfBoxPadesSealAdapter`: `@ApplicationScoped` is lazy, so a
 * `@PostConstruct` that exists purely to state a boot-time fact never runs until something else
 * touches the bean — for a bean nothing injects, that is never (#1299).
 *
 * Deliberately WARN, not ERROR: no service in this fleet logs `.error` from a `@Startup` bean
 * (checked before writing this), and `replicas: 1` is the CORRECT, intended state today — this is
 * a standing reminder for whoever changes it next, not a report of something already wrong.
 *
 * Does not fail boot. A hard crash here would be a worse outage than the latent correctness risk
 * it guards against — `customer-edge` is money-path (nearby-pay creates a real payment
 * instruction) and this store's failure mode even under N replicas is a benign 404 + re-share,
 * never a wrong or duplicate payment.
 */
@Startup
@ApplicationScoped
class PaymentSessionReplicaAssumptionAnnouncer {

    @PostConstruct
    fun announce() {
        log.warn(
            "PaymentSessionStore (nearby-pay sessions) keeps its state in a per-pod, non-shared " +
                "ConcurrentHashMap with no cross-replica propagation and no durable row — it is " +
                "correct ONLY while customer-edge runs replicas: 1 " +
                "(openbank-infra/gitops/components/customer-edge/customer-edge.yaml). This message " +
                "cannot detect the live replica count from inside the pod, so it logs on every " +
                "boot regardless — it is a standing reminder, not a violation report. Before " +
                "raising customer-edge above 1 replica (see #3806), move PaymentSessionStore to a " +
                "shared TTL cache — this service already runs the Redis it would need, for " +
                "ChallengeStore/DeviceSessionStore/PendingOnboardingStore/ThemePreferenceStore — " +
                "or a token created on one pod will 404 on resolve whenever the payer's request " +
                "lands on a different pod (issue #4728).",
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(PaymentSessionReplicaAssumptionAnnouncer::class.java)
    }
}
