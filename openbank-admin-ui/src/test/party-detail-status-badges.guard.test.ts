// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('party detail status presentation', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/parties/[id]/page.tsx'), 'utf8')

  it('uses the shared semantic status badge for party, KYC and message states', () => {
    expect(source).toContain("import { PageHeader, StatusBadge } from '@/components/ui'")
    expect(source.match(/<StatusBadge status=/g)).toHaveLength(4)
    expect(source).not.toMatch(/(?:STATUS_COLOR|KYC_COLOR|MSG_STATUS_COLOR)/)
  })
})
