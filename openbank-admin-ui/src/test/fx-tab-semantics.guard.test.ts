// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/fx/page.tsx'), 'utf8')

describe('FX rate-sheet tab semantics', () => {
  it('keeps the mutually exclusive rate-sheet controls named and stateful', () => {
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-pressed={activeTab === tab}')
    expect(source).toContain("t('Bankovní lístek', 'Bank rate sheet')")
    expect(source).toContain("t('Kurzy ČNB', 'CNB rates')")
    expect(source).toContain("t('Kurzy ECB', 'ECB rates')")
  })
})
