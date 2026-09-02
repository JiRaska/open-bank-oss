// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('screen feedback timestamp locale contract', () => {
  it('formats report timestamps with the active locale and preserves invalid raw values', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/feedback/page.tsx'), 'utf8')

    expect(source).toContain("const dateLocale = cs ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain("toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })")
    expect(source).toContain('if (Number.isNaN(date.getTime())) return value')
    expect(source).toContain('formatFeedbackTimestamp(r.occurredAt, dateLocale)')
    expect(source).not.toContain('<td>{r.occurredAt}</td>')
  })
})
