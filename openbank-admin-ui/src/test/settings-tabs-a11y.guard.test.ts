import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = fs.readFileSync(path.join(process.cwd(), 'src/app/settings/page.tsx'), 'utf8')

describe('settings tabs accessibility', () => {
  it('keeps every tab target mounted and keyboard navigable', () => {
    expect(page).toContain('role="tablist"')
    expect(page).toContain('role="tab"')
    expect(page).toContain('role="tabpanel"')
    expect(page).toContain('tabIndex={tab === item.id ? 0 : -1}')
    expect(page).toContain("event.key === 'ArrowDown'")
    expect(page).toContain("event.key === 'ArrowUp'")
    expect(page).toContain("event.key === 'Home'")
    expect(page).toContain("event.key === 'End'")
    for (const id of ['profile', 'notifications', 'security', 'api', 'regional']) {
      expect(page).toContain(`id="settings-panel-${id}"`)
      expect(page).toContain(`hidden={tab !== '${id}'}`)
    }
  })
})
