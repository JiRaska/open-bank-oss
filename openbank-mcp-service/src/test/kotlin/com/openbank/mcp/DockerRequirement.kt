// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory

/**
 * Docker gate for the customer-channel store ITs (review of #16791). Locally a missing Docker skips;
 * in CI (`CI=true`) it FAILS, because a skipped security IT reads as a pass in a test report and the
 * replay/boot guarantees would then be unproven on the very run that is meant to prove them.
 */
object DockerRequirement {
    fun require(what: String, ci: Boolean = System.getenv("CI") == "true", available: () -> Boolean = ::docker) {
        val ok = available()
        if (!ok && ci) fail<Unit>("Docker is required for $what and CI=true: refusing to skip")
        assumeTrue(ok, "Docker is required for $what")
    }

    @Suppress("TooGenericExceptionCaught") // any probe failure means "not available"
    private fun docker(): Boolean = try {
        DockerClientFactory.instance().isDockerAvailable
    } catch (_: Exception) {
        false
    }
}
