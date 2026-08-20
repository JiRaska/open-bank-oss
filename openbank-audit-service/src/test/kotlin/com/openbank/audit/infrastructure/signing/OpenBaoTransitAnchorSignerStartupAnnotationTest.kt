// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import io.quarkus.runtime.Startup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `@ApplicationScoped` is lazy in Quarkus: a bean nothing injects is never constructed, so a
 * boot-time gate on it never fires. That has already shipped here once — `PdfBoxPadesSealAdapter`
 * warned that every seal was "worthless as evidence" and the warning had never appeared in a pod
 * log. Removing `@Startup` from the anchor signer would silently restore that: the fail-closed
 * check would only run at the first capture, an hour after a deploy went green.
 *
 * No behavioural test can see the annotation's absence (the observer still works when called), so
 * this asserts it directly.
 */
class OpenBaoTransitAnchorSignerStartupAnnotationTest {

    @Test
    fun `the anchor signer is eagerly started so its fail-closed gate runs at boot`() {
        assertThat(OpenBaoTransitAnchorSigner::class.java.getAnnotation(Startup::class.java))
            .describedAs("@Startup must stay on OpenBaoTransitAnchorSigner — @ApplicationScoped alone is lazy")
            .isNotNull()
    }
}
