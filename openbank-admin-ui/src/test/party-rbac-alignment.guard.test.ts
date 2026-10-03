import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '..')
const read = (file: string) => readFileSync(resolve(root, file), 'utf8')

describe('party PII links respect the party-service role boundary', () => {
  it('keeps route roles aligned with PartyResource and gates cross-page links', () => {
    const roles = read('lib/auth/roles.ts')
    expect(roles).toContain('"parties:view":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.KYC]')
    expect(roles).toContain('"parties:view-detail":  [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.KYC, ROLES.RISK]')
    expect(read('components/balance-sheet/InstrumentsPanel.tsx')).toContain("'parties:view-detail'")
    // The detail permission must equal getParty's @RolesAllowed (minus ROLE_API, a machine role),
    // and the broad directory permission must NOT admit RISK, which search/list refuse.
    const kt = readFileSync(resolve(root, '../../openbank-party-service/src/main/kotlin/com/openbank/party/infrastructure/rest/PartyResource.kt'), 'utf8')
    const allowed = (kt.match(/@Path\("\/\{id\}"\)\s*(?:\/\/.*\n\s*)*@RolesAllowed\(([^)]*)\)\s*@Operation\(summary = "Get party by ID"/) ?? [])[1] ?? ''
    expect(allowed).toContain('"ROLE_RISK"')
    const kind = (r: string) => r.replace(/"/g, '').split(',').map(x => x.trim()).filter(x => x && x !== 'ROLE_API').sort()
    const ui = (roles.match(/"parties:view-detail":\s*\[([^\]]*)\]/) ?? [])[1] ?? ''
    expect(ui.split(',').map(x => 'ROLE_' + x.trim().replace('ROLES.', '')).sort()).toEqual(kind(allowed))
    expect(kt.match(/@RolesAllowed\([^)]*\)\s*@Operation\(\s*summary = "List parties/)?.[0] ?? '').not.toContain('ROLE_RISK')
    expect(read('app/kyc/page.tsx')).toMatch(/<Can permission="parties:view">[\s\S]*href=\{`\/parties\//)
    expect(read('app/onboarding/page.tsx')).toMatch(/<Can permission="parties:view">[\s\S]*href=\{`\/parties\//)
    expect(read('app/pid/page.tsx')).toMatch(/<Can permission="parties:create">[\s\S]*href="\/parties\/new"/)
    expect(read('components/entities/EntityChip.tsx')).toContain("'parties:view'")
    expect(read('components/entities/EntityChip.tsx')).toContain('canOpenParty')
  })
})
