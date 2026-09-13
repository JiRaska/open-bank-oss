// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const APP = path.resolve(__dirname, '../app')

describe('FinOps anomaly truthfulness', () => {
  it('does not present Alertmanager alerts as reviewable HITL proposals', () => {
    for (const page of ['iaops/page.tsx', 'finops/page.tsx']) {
      const source = readFileSync(path.join(APP, page), 'utf8')
      expect(source, page).toMatch(/read-only|pouze pro čtení/)
      expect(source, page).not.toMatch(/console\.log\('HITL/)
    }
  })
})
