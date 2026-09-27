// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory

/**
 * Decides what a Testcontainers-backed test does when Docker cannot be reached.
 *
 * Locally, no Docker means the test is SKIPPED (aborted). Under `CI=true` it FAILS instead: a
 * skipped integration test in CI is not evidence of anything, and a skip reads as a pass in every
 * summary anyone looks at. The same rule covers a Docker that IS running but refuses the client —
 * e.g. an old Testcontainers negotiating an API version the daemon no longer accepts — which
 * `isDockerAvailable` also reports as `false`.
 */
object DockerRequirement {

    /** Fails under CI, aborts (skips) otherwise, when Docker is not usable. */
    fun require(
        available: Boolean = DockerClientFactory.instance().isDockerAvailable,
        ci: String? = System.getenv("CI"),
    ) {
        if (available) return
        if (ci.equals("true", ignoreCase = true)) {
            throw AssertionFailedError("Docker is required for Testcontainers ITs when CI=true, but is not available")
        }
        throw TestAbortedException("Docker not available — skipping Testcontainers IT")
    }
}
