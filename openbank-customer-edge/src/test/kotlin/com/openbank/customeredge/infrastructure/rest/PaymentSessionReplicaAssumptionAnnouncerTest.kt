// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The thing worth protecting here is `@Startup`, not the log text.
 *
 * `@ApplicationScoped` is lazy — without `@Startup` the bean is only constructed on first
 * injection, nothing injects this one, and the announcement never runs: the class would look
 * completely correct and produce nothing, forever (same failure shape as #1299 /
 * `PdfBoxPadesSealAdapter`, and the same fix as `AuthzModeAnnouncer`). A test that only called
 * `announce()` directly would pass against exactly that bug, because the direct call supplies the
 * instantiation the container would otherwise skip — so assert the annotations themselves, not
 * just the method's behavior.
 */
class PaymentSessionReplicaAssumptionAnnouncerTest {

    @Test
    fun `carries @Startup, without which the announcement never runs`() {
        assertThat(PaymentSessionReplicaAssumptionAnnouncer::class.java.isAnnotationPresent(Startup::class.java))
            .`as`("@ApplicationScoped is lazy — @Startup is what makes a boot-time statement happen at boot")
            .isTrue()
    }

    @Test
    fun `the announcement is a @PostConstruct callback, so the container invokes it`() {
        val method = PaymentSessionReplicaAssumptionAnnouncer::class.java.declaredMethods.single {
            it.name == "announce"
        }
        assertThat(method.isAnnotationPresent(PostConstruct::class.java)).isTrue()
    }

    @Test
    fun `announce runs unconditionally without throwing`() {
        // No replica count is available inside the pod (no downward-API field, no Kubernetes
        // client in this fleet) — the announcement cannot be conditional on it, so this must
        // simply not fail on every boot.
        PaymentSessionReplicaAssumptionAnnouncer().announce()
    }
}
