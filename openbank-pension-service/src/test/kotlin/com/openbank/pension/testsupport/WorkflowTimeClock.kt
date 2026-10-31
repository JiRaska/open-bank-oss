// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.testsupport

import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * The service clock for tests. By default it IS the system clock. A test profile that sets
 * `pension.test.clock-follows-workflow-time=true` (the journey E2E) makes it move with the
 * time-skipping Temporal environment: [PensionTemporalTestEnvironment.advance] skips workflow time
 * AND this clock together. Without that, a workflow whose cooling-off timer has fired calls an
 * activity whose aggregate — correctly — still checks the cooling-off end against the wall clock
 * and refuses, which is a property of the test harness, not of production (where the two agree).
 */
@ApplicationScoped
@Alternative
@Priority(1)
class WorkflowTimeClock {

    @ConfigProperty(name = "pension.test.clock-follows-workflow-time", defaultValue = "false")
    var followsWorkflowTime: Boolean = false

    private val offset = AtomicReference(Duration.ZERO)

    fun advance(duration: Duration) {
        if (followsWorkflowTime) offset.updateAndGet { it.plus(duration) }
    }

    @Produces
    @Singleton
    fun clock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.now().plus(offset.get())
    }
}
