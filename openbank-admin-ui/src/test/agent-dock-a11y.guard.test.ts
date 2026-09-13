import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('AgentDock accessibility contract', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/components/agent/AgentDock.tsx'), 'utf8')

  it('exposes stateful, named controls and a stable panel target', () => {
    expect(source).toContain('aria-expanded={open}')
    expect(source).toContain("aria-controls={open ? 'agent-dock-panel' : undefined}")
    expect(source).toContain('id="agent-dock-panel"')
    expect(source).toContain('role="region"')
    expect(source).toContain("aria-label={t('Zpráva pro asistenta', 'Message for assistant')}")
    expect(source).toContain("aria-label={t('Odeslat zprávu', 'Send message')}")
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-hidden="true"')
  })
})
