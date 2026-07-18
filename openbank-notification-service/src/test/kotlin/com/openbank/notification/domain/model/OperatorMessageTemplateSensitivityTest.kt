// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Executable form of the guarantee behind issue #1386: [OperatorMessageTemplate] has its own
 * secret classifier, the same shape as [TemplateSensitivity] for [NotificationTemplate], so a
 * future secret-bearing operator-message template has somewhere real to be classified instead of
 * silently inheriting `NotificationResource.bodyForRead`'s fail-open path.
 */
class OperatorMessageTemplateSensitivityTest {

    @Test
    fun `no current OperatorMessageTemplate is classified secret`() {
        for (t in OperatorMessageTemplate.entries) {
            assertThat(OperatorMessageTemplateSensitivity.isSecret(t)).isFalse()
        }
    }

    @Test
    fun `the classification set is pinned, so widening it is deliberate`() {
        assertThat(OperatorMessageTemplateSensitivity.SECRET_TEMPLATES).isEmpty()
    }
}
