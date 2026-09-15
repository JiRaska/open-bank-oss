// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { THEME_BOOTSTRAP_SCRIPT } from '@/lib/theme/bootstrap'

describe('pre-paint theme bootstrap', () => {
  it('reads only the canonical key and allowlists dark rather than executing stored data', () => {
    expect(THEME_BOOTSTRAP_SCRIPT).toContain("localStorage.getItem('ob-admin-theme')")
    expect(THEME_BOOTSTRAP_SCRIPT).toContain("t==='dark'")
    expect(THEME_BOOTSTRAP_SCRIPT).not.toContain('eval')
    expect(THEME_BOOTSTRAP_SCRIPT).not.toContain('innerHTML')
  })
})
