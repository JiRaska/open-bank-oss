import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/infrastructure/topology/page.tsx'), 'utf8')

describe('infrastructure topology controls accessibility contract', () => {
  it('labels layer filters and exposes node selection state', () => {
    expect(page).toContain('role="group" aria-label={t(\'Filtrování vrstev infrastruktury\', \'Infrastructure layer filters\')}')
    expect(page).toContain('type="button" onClick={() => setFilter(key)} aria-pressed={filter === key}')
    expect(page).toContain('role="button" tabIndex={0} aria-label={n.label} aria-pressed={isSel} aria-controls={isSel ? \'infra-topology-selection\' : undefined}')
    expect(page).toContain('id="infra-topology-selection" className="card" role="region"')
  })

  it('announces flow/refresh state and labels detail actions', () => {
    expect(page).toContain('aria-label={t(flow ? \'Pozastavit tok dat\' : \'Spustit tok dat\', flow ? \'Pause data flow\' : \'Start data flow\')}')
    expect(page).toContain('type="button" onClick={load} disabled={isChecking} aria-busy={isChecking}')
    expect(page).toContain('aria-label={t(\'Zavřít detail infrastruktury\', \'Close infrastructure details\')}')
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('<Play aria-hidden="true"')
    expect(page).toContain('<Pause aria-hidden="true"')
    expect(page).toContain('<X aria-hidden="true"')
  })

  it('uses the shared semantic status vocabulary for live probes', () => {
    expect(page).toContain("import { PageHeader, StatusBadge, statusTone } from '@/components/ui'")
    expect(page).toContain('const tone = statusTone(status)')
    expect(page).toContain("return 'var(--success)'")
    expect(page).toContain("return 'var(--danger)'")
    expect(page).toContain('<StatusBadge')
    expect(page).toContain('status={statusOf(selectedNode.id)}')
    expect(page).toContain('withDot')
    expect(page).not.toContain('const statusColor')
  })
})
