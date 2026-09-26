import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = fs.readFileSync(path.join(process.cwd(), 'src/app/settings/page.tsx'), 'utf8')
const tabs = fs.readFileSync(path.join(process.cwd(), 'src/components/ui/Tabs.tsx'), 'utf8')

describe('settings tabs accessibility', () => {
  it('keeps every tab target mounted and keyboard navigable', () => {
    expect(page).toContain('<Tabs')
    expect(tabs).toContain('role="tablist"')
    expect(tabs).toContain('role="tab"')
    expect(page).toContain('role="tabpanel"')
    expect(tabs).toContain('tabIndex={selected ? 0 : -1}')
    expect(tabs).toContain("event.key === 'ArrowDown'")
    expect(tabs).toContain("event.key === 'ArrowUp'")
    expect(tabs).toContain("event.key === 'Home'")
    expect(tabs).toContain("event.key === 'End'")
    for (const id of ['profile', 'notifications', 'security', 'api', 'regional']) {
      expect(page).toContain(`id="settings-panel-${id}"`)
      expect(page).toContain(`hidden={tab !== '${id}'}`)
    }
  })
})
