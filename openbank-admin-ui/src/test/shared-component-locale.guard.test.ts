import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.join(process.cwd(), 'src', 'components')
const files = [
  'docs/BpmnView.tsx',
  'lending/OriginationFlow.tsx',
  'devops/QualityGateHealthPanel.tsx',
  'agent/AgentInsightsPanel.tsx',
]

describe('shared operator components use the active locale', () => {
  it('does not fall back to the browser locale for visible dates', () => {
    for (const relative of files) {
      const source = fs.readFileSync(path.join(root, relative), 'utf8')
      expect(source, relative).not.toMatch(/toLocale(?:String|DateString|TimeString)\(\)/)
    }
    expect(fs.readFileSync(path.join(root, files[0]), 'utf8')).toContain('toLocaleTimeString(dateLocale)')
    expect(fs.readFileSync(path.join(root, files[2]), 'utf8')).toContain('toLocaleDateString(dateLocale)')
    expect(fs.readFileSync(path.join(root, files[3]), 'utf8')).toContain('toLocaleString(dateLocale)')
  })
})
