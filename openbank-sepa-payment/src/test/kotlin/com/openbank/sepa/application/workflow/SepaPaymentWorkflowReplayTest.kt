// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Replays every committed pre-change SEPA workflow history against the CURRENT
 * [SepaPaymentWorkflowImpl] (#15724). Any change to the workflow's command sequence that is not
 * guarded by `Workflow.getVersion` fails here, before it can wedge an in-flight production payment.
 *
 * The histories under `src/test/resources/temporal-histories/` were recorded from the shipped
 * workflow and activities by [SepaPaymentWorkflowHistoryRecorder]; see the README there. Never
 * regenerate an existing history from changed code.
 */
class SepaPaymentWorkflowReplayTest {

    companion object {
        @JvmStatic
        fun histories(): List<String> = Files.list(SepaPaymentWorkflowHistoryFixtures.HISTORY_DIR).use { s ->
            s.map { it.name }.filter { it.endsWith(".json") }.sorted().toList()
        }
    }

    @Test
    fun `every expected workflow path has a committed history`() {
        val present = histories().map { it.removeSuffix(".json") }
        assertThat(present).containsAll(SepaPaymentWorkflowHistoryFixtures.EXPECTED_HISTORIES)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("histories")
    fun `committed history replays deterministically against the current workflow`(file: String) {
        val json = SepaPaymentWorkflowHistoryFixtures.HISTORY_DIR.resolve(file).readText()
        val history = WorkflowExecutionHistory.fromJson(json)
        assertThat(history.events).isNotEmpty
        TestWorkflowEnvironment.newInstance(SepaPaymentWorkflowHistoryFixtures.environmentOptions()).use { env ->
            // Throws on any non-determinism; a clean return is the proof.
            WorkflowReplayer.replayWorkflowExecution(history, env, SepaPaymentWorkflowImpl::class.java)
        }
    }
}
