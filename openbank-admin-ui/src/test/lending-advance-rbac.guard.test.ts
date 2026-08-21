import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = fs.readFileSync(path.join(process.cwd(), 'src/app/lending/applications/[id]/page.tsx'), 'utf8')

describe('lending advance RBAC truthfulness', () => {
  it('does not promise an action with no backend policy grant', () => {
    expect(page).not.toContain('permission="lending:advance"')
    expect(page).toContain('Manual advance is not configured in this installation.')
    expect(page).not.toContain("/api/v1/lending/applications/${id}/advance")
  })
})
