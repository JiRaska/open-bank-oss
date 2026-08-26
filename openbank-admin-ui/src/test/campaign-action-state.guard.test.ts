import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

describe('campaign action state accessibility guard', () => {
  it('exposes truthful progress for draft and detail mutations', () => {
    const composer = read('../app/campaigns/new/page.tsx')
    const detail = read('../app/campaigns/[id]/page.tsx')

    expect(composer).toMatch(/type="button"[\s\S]*?onClick=\{submit\}[\s\S]*?aria-busy=\{saving\}/)
    expect(detail).toContain('const [actingAction, setActingAction] = useState<string | null>(null)')
    expect(detail).toContain('aria-busy={actingAction === a}')
    expect(detail).toContain('aria-busy={duplicating}')
    expect(detail).toContain("aria-label={t('Předchozí stránka logu odeslání', 'Previous send-log page')}")
    expect(detail).toContain("aria-label={t('Další stránka logu odeslání', 'Next send-log page')}")
  })

  it('keeps non-submit campaign controls explicit buttons', () => {
    const portfolio = read('../app/campaigns/page.tsx')
    const stepEditor = read('../components/campaigns/StepEditor.tsx')

    expect(portfolio).toContain('<button type="button" className="btn btn-secondary"')
    expect(portfolio).toContain('data-testid="clear-state"')
    expect(stepEditor).toMatch(/<button type="button" onClick=\{onClose\}/)
  })
})
