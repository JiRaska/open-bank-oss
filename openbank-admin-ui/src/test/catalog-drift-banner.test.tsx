// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const catalog = { services: [{ short: 'pension-service', kind: 'service' }] }
const wrap = (present: string[]) => <LanguageProvider initialLanguage="en"><CatalogDriftBanner present={present} /></LanguageProvider>

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('catalog drift evidence', () => {
  it('ignores a late response for the empty initial roster after the catalog nodes arrive', async () => {
    let resolveInitial!: (response: Response) => void
    const initial = new Promise<Response>(resolve => { resolveInitial = resolve })
    const fetchMock = vi.fn().mockReturnValueOnce(initial).mockResolvedValue(Response.json(catalog))
    vi.stubGlobal('fetch', fetchMock)
    const view = render(wrap([]))
    view.rerender(wrap(['pension-service']))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await act(async () => { resolveInitial(Response.json(catalog)); await initial })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('still reports a service genuinely omitted from the rendered roster', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(catalog)))
    render(wrap(['wealth-service']))
    expect(await screen.findByRole('status')).toHaveTextContent('pension-service')
  })
})
