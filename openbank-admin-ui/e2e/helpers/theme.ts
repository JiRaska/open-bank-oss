// SPDX-License-Identifier: Apache-2.0

import type { Page } from '@playwright/test'

export type OperatorTheme = 'light' | 'dark'

/** Apply the same persisted preference the shipped useTheme hook consumes. */
export async function setOperatorTheme(page: Page, theme: OperatorTheme): Promise<void> {
  await page.addInitScript(selectedTheme => {
    window.localStorage.setItem('ob-admin-theme', selectedTheme)
  }, theme)
}
