// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('PID status presentation', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/pid/page.tsx'), 'utf8')

  it('uses the shared badge and preserves security-sensitive lifecycle meanings', () => {
    expect(source).toMatch(/import \{[^}]*\bStatusBadge\b[^}]*\bstatusTone\b[^}]*\btype Tone\b[^}]*\} from '@\/components\/ui'/)
    expect(source).toContain("if (status === 'EXPIRED') return 'warning'")
    expect(source).toContain("if (status === 'REVOKED') return 'danger'")
    expect(source).toContain('<StatusBadge status={r.status} tone={pidStatusTone(r.status)} />')
    expect(source).not.toMatch(/STATUS_COLORS/)
  })
})
