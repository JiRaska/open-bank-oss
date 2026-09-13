// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const read = () => readFileSync(path.resolve(__dirname, '../app/accounts/[id]/page.tsx'), 'utf8')

describe('account lifecycle mutation guard', () => {
  it('prevents duplicate lifecycle writes before React state can rerender', () => {
    const source = read()
    expect(source).toContain("import { useEffect, useRef, useState } from 'react'")
    expect(source).toContain('const actionInFlight = useRef(false)')
    expect(source).toContain('if (!account || actionInFlight.current) return')
    expect(source).toContain('actionInFlight.current = true')
    expect(source).toContain('actionInFlight.current = false')
  })

  it('requires a meaningful audit reason before freeze, unfreeze, or close', () => {
    const source = read()
    expect(source).toContain('const normalizedReason = reason.trim()')
    expect(source).toContain('if (!normalizedReason)')
    expect(source).toContain("disabled={acting || !actionReason.trim()}")
    expect(source).toContain('role="alert"')
    expect(source).toContain('accountApi.freeze(id, normalizedReason)')
    expect(source).toContain('accountApi.unfreeze(id, normalizedReason)')
    expect(source).toContain('accountApi.close(id, normalizedReason)')
  })
})
