import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/system/agent/page.tsx'), 'utf8')

describe('system agent tool cards accessibility', () => {
  it('exposes controlled tool accordion and busy run action semantics', () => {
    const source = read()
    expect(source).toContain('type="button"\n        aria-expanded={expanded}\n        aria-controls={expanded ? panelId : undefined}')
    expect(source).toContain('const panelId = `agent-tool-${tool.name.replace(')
    expect(source).toContain('id={panelId} role="region"')
    expect(source).toContain('aria-busy={running}')
    expect(source).toContain('disabled={running}')
    expect(source).toContain('<Play size={13} aria-hidden="true"')
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
  })
})
