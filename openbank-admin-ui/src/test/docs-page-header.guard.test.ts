import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const docsRoot = path.resolve(__dirname, '../app/docs')
const pages = [
  'page.tsx',
  'adr/page.tsx',
  'adr/[slug]/page.tsx',
  'api/page.tsx',
  'bcp/page.tsx',
  'changelog/[service]/page.tsx',
  'cloud-architecture/page.tsx',
  'cluster/page.tsx',
  'compliance/page.tsx',
  'control-tower/page.tsx',
  'customer-app/page.tsx',
  'document-management/page.tsx',
  'flags/page.tsx',
  'identity-dedup/page.tsx',
  'lineage/page.tsx',
  'qrlesspay/page.tsx',
  'qrlesspay-readiness/page.tsx',
  'release-notes/[service]/page.tsx',
  'service-map/page.tsx',
  'threat-models/page.tsx',
  'threat-models/[service]/page.tsx',
  'zero-trust/page.tsx',
].map(file => readFileSync(path.join(docsRoot, file), 'utf8'))

const sharedHeader = readFileSync(path.resolve(__dirname, '../components/docs/DocsPageHeader.tsx'), 'utf8')
const processView = readFileSync(path.resolve(__dirname, '../components/docs/ProcessView.tsx'), 'utf8')
const bpmnView = readFileSync(path.resolve(__dirname, '../components/docs/BpmnView.tsx'), 'utf8')

describe('documentation header contract', () => {
  it('routes use the shared docs header and no legacy page-header markup', () => {
    for (const page of pages) {
      expect(page).toContain('DocsPageHeader')
      expect(page).not.toContain('className="page-header"')
    }
    expect(processView).toContain('<DocsPageHeader')
    expect(bpmnView).toContain('<DocsPageHeader')
    expect(sharedHeader).toContain('className="breadcrumb"')
  })

  it('keeps a single accessible icon contract in the shared wrapper', () => {
    expect(sharedHeader).toContain('breadcrumb={<div className="breadcrumb">')
    for (const source of [...pages, processView, bpmnView]) {
      if (source.includes('icon={<')) expect(source).toContain('aria-hidden="true"')
    }
  })
})
