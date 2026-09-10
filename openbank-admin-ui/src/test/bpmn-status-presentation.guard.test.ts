// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = () => readFileSync(path.resolve(__dirname, '../components/docs/BpmnView.tsx'), 'utf8')

describe('BPMN service status presentation', () => {
  it('uses the shared semantic status system without changing operator labels', () => {
    const component = source()

    expect(component).toContain("import { StatusBadge } from '@/components/ui'")
    expect(component).toContain('<StatusBadge status="up" label={t(\'AKTIVNÍ\', \'UP\')}')
    expect(component).toContain('<StatusBadge status="down" label={t(\'NEDOSTUPNÉ\', \'DOWN\')}')
    expect(component).toContain('<StatusBadge status="loading" tone="warning" label={t(\'OVĚŘUJI\', \'CHECKING\')}')
    expect(component).toContain('<StatusBadge status="unknown" label={t(\'N/A\', \'N/A\')}')
    expect(component).toContain("{ shape: 'status-up', color: 'var(--success-text)'")
    expect(component).toContain("{ shape: 'status-down', color: 'var(--danger-text)'")
    expect(component).not.toContain("color: '#16a34a', fontWeight: 600, background: '#dcfce7'")
    expect(component).not.toContain("color: '#dc2626', fontWeight: 600, background: '#fee2e2'")
  })
})
