// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.application.port.out.TestScanPort
import com.openbank.flakytest.domain.model.RunBlockingViolation
import com.openbank.flakytest.domain.model.TestScanSnapshot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [CollectTestScanActivityImpl] delegates to the scan port through a Vert.x-context bridge. The
 * bridge itself needs a real Vert.x context and is not what this activity decides; the test
 * substitutes it (the seam the production class leaves `protected open` for exactly this) and
 * asserts the delegation and failure propagation, which are the activity's own behaviour.
 */
class CollectTestScanActivityImplTest {

    private val port = mockk<TestScanPort>()

    /** Runs the suspending block on the caller's thread instead of a Vert.x duplicated context. */
    private class DirectCollectActivity(port: TestScanPort) : CollectTestScanActivityImpl(port) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private val activity = DirectCollectActivity(port)

    private val snapshot = TestScanSnapshot(
        testFilesScanned = 3,
        runBlockingViolations = listOf(RunBlockingViolation("Foo.kt", 12, "runBlocking", "fun f() = runBlocking {")),
        pactGatedClasses = emptyList(),
        pactProviderDeclarations = emptyList(),
        testCountSamples = emptyList(),
    )

    @Test
    fun `collect returns the port's snapshot unchanged`() {
        coEvery { port.scan() } returns snapshot

        assertThat(activity.collect()).isEqualTo(snapshot)
    }

    @Test
    fun `a scan failure propagates so Temporal can retry the activity`() {
        coEvery { port.scan() } throws IllegalStateException("checkout unreadable")

        assertThatThrownBy { activity.collect() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("checkout unreadable")
    }
}
