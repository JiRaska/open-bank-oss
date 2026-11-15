// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.qualification

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SEEDED DEFECT — exists ONLY on the qualification/flaky-test-hunter branch, never on main
 * (ADR-0031 D9 amendment 2026-11-14). The function below deliberately omits ': Unit', so JUnit5
 * silently drops it. It is the one shape the deployed writer may repair; its PR into this branch
 * is the phase-3 live-write proof. It is not an organic finding.
 */
class SeededQualificationTest {
    @Test
    fun seededLostTest() = runBlocking { assertTrue(true) }
}
