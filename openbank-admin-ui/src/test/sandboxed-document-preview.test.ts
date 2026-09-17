// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { sandboxedPreviewDocument } from '@/lib/documents/sandboxedPreview'

describe('sandboxed document preview popup', () => {
  it('keeps authored HTML inside an escaped, scriptless iframe boundary', () => {
    const authored = '</iframe><script>window.opener.location="https://attacker.example"</script>'
    const html = sandboxedPreviewDocument(authored, 'Preview "one"')

    expect(html).toContain('default-src \'none\'')
    expect(html).toContain('<iframe sandbox=""')
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('</iframe><script>')
    expect(html).toContain('&lt;/iframe&gt;&lt;script&gt;')
    expect(html).toContain('<title>Preview &quot;one&quot;</title>')
    expect(html).not.toContain('allow-scripts')
    expect(html).not.toContain('allow-top-navigation')
  })
})
