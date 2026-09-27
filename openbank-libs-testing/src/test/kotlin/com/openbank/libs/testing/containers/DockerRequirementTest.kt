// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException

class DockerRequirementTest {

    @Test
    fun `no Docker under CI fails rather than skips`() {
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "true") }
            .isInstanceOf(AssertionFailedError::class.java)
    }

    @Test
    fun `no Docker under a differently-cased CI value still fails`() {
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "True") }
            .isInstanceOf(AssertionFailedError::class.java)
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "TRUE") }
            .isInstanceOf(AssertionFailedError::class.java)
    }

    @Test
    fun `no Docker under CI=1 still fails`() {
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "1") }
            .isInstanceOf(AssertionFailedError::class.java)
    }

    @Test
    fun `no Docker outside CI skips`() {
        assertThatThrownBy { DockerRequirement.require(available = false, ci = null) }
            .isInstanceOf(TestAbortedException::class.java)
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "false") }
            .isInstanceOf(TestAbortedException::class.java)
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "0") }
            .isInstanceOf(TestAbortedException::class.java)
        assertThatThrownBy { DockerRequirement.require(available = false, ci = "") }
            .isInstanceOf(TestAbortedException::class.java)
    }

    @Test
    fun `available Docker proceeds regardless of CI`() {
        assertThatCode { DockerRequirement.require(available = true, ci = "true") }.doesNotThrowAnyException()
        assertThatCode { DockerRequirement.require(available = true, ci = null) }.doesNotThrowAnyException()
    }
}
