// SPDX-License-Identifier: Apache-2.0

import type { Page } from '@playwright/test'

/**
 * Frames the shell must hold a single `#main-content` before a scan may run.
 *
 * `waitForFunction` polls on requestAnimationFrame, so this is ~10 frames of stability.
 * The observed duplicate window is ~50 ms (measured below), and 10 frames clears it with
 * room for a loaded CI machine without becoming a disguised sleep: the condition is
 * "stable", not "waited".
 */
const STABLE_FRAMES = 10

/**
 * Wait until the operator shell has settled to exactly one `#main-content` landmark.
 *
 * WHY THIS EXISTS. For roughly 50 ms after `page.goto` returns, TWO `#main-content`
 * elements are in the document — both visible. Only one component renders that id
 * (`AppShell.tsx`), so two AppShell instances are mounted at once while the client tree
 * replaces the server-rendered one. Measured on an unmodified checkout, sampling every
 * 25 ms across all ten core-workflow routes:
 *
 *     /parties: 0ms n=1 | 25ms n=2 | 50ms n=2 | 75ms n=1 | ... (n=1 thereafter)
 *     /kyc:     0ms n=1 | 25ms n=2 | 50ms n=2 | 75ms n=1 | ... (n=1 thereafter)
 *
 * Two consequences the specs were getting wrong:
 *
 *  - `expect(page.locator('#main-content')).toBeVisible()` is a STRICT locator, so landing
 *    inside that window fails with "resolved to 2 elements" — and whether it lands there
 *    depends on machine load, which is why the failing subset differed on every run.
 *  - Waiting for the H1 first is NOT enough. Measured: on `/kyc` the count was still 2
 *    immediately after the level-1 heading became visible.
 *
 * And the reason a simple `toHaveCount(1)` does not work either: the count is already 1 at
 * t=0, so it passes before the window opens. The condition has to be stability, not a
 * single observation. Once settled it never reopened (0 of 20 samples on each of 5 routes).
 */
export async function waitForSettledShell(page: Page): Promise<void> {
  await page.waitForFunction(frames => {
    const w = window as unknown as { __obShellStableFrames?: number }
    const count = document.querySelectorAll('#main-content').length
    w.__obShellStableFrames = count === 1 ? (w.__obShellStableFrames ?? 0) + 1 : 0
    return (w.__obShellStableFrames ?? 0) >= frames
  }, STABLE_FRAMES)
}
