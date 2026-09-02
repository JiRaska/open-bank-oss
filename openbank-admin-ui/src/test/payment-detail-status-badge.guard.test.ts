// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('payment detail status presentation', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/payments/[id]/page.tsx'), 'utf8')

  it('uses the shared status badge while preserving payment-specific in-flight meanings', () => {
    expect(source).toContain("import { PageHeader, StatusBadge, statusTone, type Tone } from '@/components/ui'")
    expect(source).toContain("if (status === 'RECEIVED') return 'info'")
    expect(source).toContain("if (status === 'SENT_TO_CLEARING') return 'warning'")
    expect(source).toContain('<StatusBadge status={payment.status} tone={paymentStatusTone(payment.status)} />')
    expect(source).not.toMatch(/STATUS_COLOR/)
  })
})
