// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('party detail actions contract', () => {
  it('keeps messaging/retry/pagination actions explicit and accessible', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/parties/[id]/page.tsx'), 'utf8')
    expect(source).toContain("aria-label={t('Zkusit znovu odeslat zprávu', 'Retry sending message')}")
    expect(source).toContain("aria-label={t('Poslat zprávu', 'Send message')}")
    expect(source).toContain("aria-label={t('Načíst další zprávy', 'Load more messages')}")
    expect(source).toContain('aria-busy={sending}')
    expect(source).toContain('retrySubmit')
    expect(source).toContain('sendMessage')
  })
})
