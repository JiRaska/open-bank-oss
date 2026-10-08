import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const read = (path: string) => readFileSync(join(process.cwd(), 'src/app/docs', path), 'utf8')

describe('architecture pages distinguish declarations from live evidence', () => {
  it('labels the cluster topology as GitOps-derived rather than runtime health', () => {
    const source = read('cluster/page.tsx')
    expect(source).toContain('Počty a štítky níže nepotvrzují aktuální stav běžícího clusteru')
    expect(source).toContain('deklarováno v GitOps')
    expect(source).not.toContain('nasazeno, aktivace probíhá')
  })

  it('does not present the curated cloud diagram as a complete live roster', () => {
    const source = read('cloud-architecture/page.tsx')
    expect(source).toContain('Jen štítky sond ukazují aktuální dosažitelnost')
    expect(source).toContain('<Link href="/services"')
    expect(source).not.toContain('~24 zbývajících služeb')
    expect(source).not.toContain('18 podů')
    expect(source).toContain("id: 'gateway', label: 'ns: envoy-gateway-system'")
    expect(source).toContain("id: 'ai', label: 'ns: ai-platform'")
    expect(source).not.toContain('ns: ingress-nginx')
    expect(source).not.toContain("node('clickhouse', ['ClickHouse', 'ClickHouse'], 'planned'")
  })
})
