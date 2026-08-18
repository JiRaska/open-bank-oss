import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.join(process.cwd(), 'src', 'components')

describe('shared inputs have accessible names', () => {
  it('names the SBOM filter, party resolver and assistant model selector', () => {
    const sbom = fs.readFileSync(path.join(root, 'sbom/SbomViewer.tsx'), 'utf8')
    const party = fs.readFileSync(path.join(root, 'party/PartySearch.tsx'), 'utf8')
    const agent = fs.readFileSync(path.join(root, 'agent/AgentDock.tsx'), 'utf8')
    expect(sbom).toContain("aria-label={t('Filtro komponent SBOM', 'Filter SBOM components')}")
    expect(party).toContain("aria-label={t('Vyhledat stranu', 'Search parties')}")
    expect(agent).toContain("aria-label={t('Model asistenta', 'Assistant model')}")
  })
})
