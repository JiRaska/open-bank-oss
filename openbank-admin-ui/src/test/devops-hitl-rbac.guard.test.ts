import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const page = fs.readFileSync(path.join(root, 'app/devops/page.tsx'), 'utf8')
const roles = fs.readFileSync(path.join(root, 'lib/auth/roles.ts'), 'utf8')

describe('DevOps HITL authority contract', () => {
  it('keeps read access broad but gates decisions to the backend-authorized role', () => {
    expect(roles).toContain('"system:view":              [ROLES.ADMIN, ROLES.OPERATOR, ROLES.DEMO]')
    expect(roles).toContain('"devops:decide":             [ROLES.ADMIN]')
    expect(page).toContain("<AuthGuard permission=\"system:view\">")
    expect(page).toContain("hasPermission('devops:decide')")
    expect(page).toContain('onApprove={canDecide ? id => {')
    expect(page).toContain("setPendingDecision({ finding, action: 'approve' })")
    expect(page).toContain('onReject={canDecide ? id => {')
    expect(page).toContain("setPendingDecision({ finding, action: 'reject' })")
    expect(page).toContain('onClick={() => void decide(pendingDecision.finding.id, pendingDecision.action)}')
  })

  it('reports failed decision writes instead of silently refreshing', () => {
    expect(page).toContain('if (!res.ok)')
    expect(page).toContain('decisionError')
    expect(page).toContain('role="alert"')
  })
})
