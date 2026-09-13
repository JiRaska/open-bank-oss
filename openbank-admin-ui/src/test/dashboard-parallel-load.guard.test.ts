import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('dashboard health loading', () => {
  it('starts governance and live health requests concurrently', () => {
    const source = fs.readFileSync(path.join(process.cwd(), 'src/app/dashboard/page.tsx'), 'utf8')
    expect(source).toContain("const governanceRequest = fetch('/api/services/governance'")
    expect(source).toContain("const healthRequest = fetch('/api/services/health'")
    expect(source).toContain('const [govRes, res] = await Promise.all([governanceRequest, healthRequest])')
    expect(source).toContain(".catch(() => null)")
  })
})
