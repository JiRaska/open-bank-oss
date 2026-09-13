import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/system/config/page.tsx'), 'utf8')

describe('service configuration accessibility', () => {
  it('exposes stateful refresh and controlled service accordions', () => {
    const source = read()
    expect(source).toContain('type="button"\n            aria-busy={loading}')
    expect(source).toContain('<RefreshCw size={12} aria-hidden="true"')
    expect(source).toContain('const panelId = `service-config-${snap.name.replace(')
    expect(source).toContain('type="button"\n                aria-expanded={isOpen}\n                aria-controls={isOpen ? panelId : undefined}')
    expect(source).toContain('id={panelId} role="region" aria-label={t(\'Detail konfigurace služby\', \'Service configuration details\')}')
    expect(source).toContain('<ChevronDown size={14} aria-hidden="true"')
    expect(source).toContain('<ChevronRight size={14} aria-hidden="true"')
  })
})
