import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const pid = readFileSync(path.resolve(__dirname, '../app/pid/page.tsx'), 'utf8')
const regulatory = readFileSync(path.resolve(__dirname, '../app/regulatory/page.tsx'), 'utf8')

describe('operator page header contract', () => {
  it('uses the shared header with a real breadcrumb and accessible decorative icons', () => {
    for (const page of [pid, regulatory]) {
      expect(page).toContain('<PageHeader')
      expect(page).toContain('breadcrumb={<div className="breadcrumb">')
      expect(page).toContain('aria-hidden="true"')
      expect(page).not.toContain('className="page-header"')
    }
  })

  it('keeps PID and regulatory actions as native controls/links', () => {
    expect(pid).toContain('onClick={load}')
    expect(pid).toContain('href="/parties/new"')
    expect(regulatory).toContain('href="https://www.cnb.cz/cs/dohled-financni-trh/vykaznictvi/"')
  })
})
