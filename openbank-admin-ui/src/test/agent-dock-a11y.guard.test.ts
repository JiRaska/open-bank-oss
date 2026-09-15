import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('AgentDock accessibility contract', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/components/agent/AgentDock.tsx'), 'utf8')
  const launcher = fs.readFileSync(path.join(process.cwd(), 'src/components/agent/LazyAgentDock.tsx'), 'utf8')

  it('exposes stateful, named controls and a stable panel target', () => {
    expect(launcher).toContain('aria-expanded={open}')
    expect(launcher).toContain("aria-controls={open ? 'agent-dock-panel' : undefined}")
    expect(source).toContain('id="agent-dock-panel"')
    expect(source).toContain('role="dialog"')
    expect(source).toContain('aria-modal="false"')
    expect(launcher).toContain('aria-haspopup="dialog"')
    expect(source).toContain("aria-label={t('Zpráva pro asistenta', 'Message for assistant')}")
    expect(source).toContain("aria-label={t('Odeslat zprávu', 'Send message')}")
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-hidden="true"')
    expect(source).toContain("event.key !== 'Escape'")
    expect(source).toContain('inputRef.current?.focus()')
    expect(source).toContain('triggerRef.current?.focus()')
    expect(launcher).toContain("dynamic(")
    expect(launcher).toContain("import('./AgentDock')")
    expect(launcher).toContain('onMouseEnter={warmAgentDock}')
    expect(launcher).toContain('onFocus={warmAgentDock}')
  })
})
