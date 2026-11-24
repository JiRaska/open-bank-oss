// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException

class DockerRequirementTest {
    @Test
    fun `no Docker in CI fails instead of skipping`() {
        assertThatThrownBy { DockerRequirement.require("x", ci = true) { false } }
            .isInstanceOf(AssertionFailedError::class.java)
    }

    @Test
    fun `no Docker locally skips, Docker present passes`() {
        assertThatThrownBy { DockerRequirement.require("x", ci = false) { false } }
            .isInstanceOf(TestAbortedException::class.java)
        assertThatCode { DockerRequirement.require("x", ci = true) { true } }.doesNotThrowAnyException()
    }
}
