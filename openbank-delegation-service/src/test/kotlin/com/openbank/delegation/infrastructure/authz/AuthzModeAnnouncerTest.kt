// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.authz

import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The thing worth protecting here is not the log text — it is `@Startup`.
 *
 * Without it the bean is lazy, nothing injects it, and the announcement never runs: the class would
 * look completely correct and produce nothing, forever (#1299). A test that only called `announce()`
 * directly would pass against exactly that bug, because the direct call supplies the instantiation
 * the container would not. So assert the annotation itself.
 */
class AuthzModeAnnouncerTest {

    @Test
    fun `carries @Startup, without which the announcement never runs`() {
        assertThat(AuthzModeAnnouncer::class.java.isAnnotationPresent(Startup::class.java))
            .`as`("@ApplicationScoped is lazy — @Startup is what makes a boot-time statement happen at boot")
            .isTrue()
    }

    @Test
    fun `the announcement is a @PostConstruct callback, so the container invokes it`() {
        val method = AuthzModeAnnouncer::class.java.declaredMethods.single { it.name == "announce" }
        assertThat(method.isAnnotationPresent(PostConstruct::class.java)).isTrue()
    }

    @Test
    fun `defaults to enforcing when nothing sets the property`() {
        // Mirrors AuthorizeInterceptor's own defaultValue = "true": an unset flag must never read
        // as advisory, or a misconfigured service would quietly stop blocking.
        assertThat(AuthzModeAnnouncer().enforce).isTrue()
        assertThat(AuthzModeAnnouncer().fourEyesEnforce).isFalse()
    }

    @Test
    fun `announce runs in both modes without throwing`() {
        AuthzModeAnnouncer().apply { enforce = true }.announce()
        AuthzModeAnnouncer().apply { enforce = false }.announce()
    }
}
