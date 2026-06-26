// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// NO 'use client' here. This is a SERVER component — the entire markdown
// pipeline runs at request time on the server. Benefits:
//   1. No client-side react-markdown crashes (v9 API differences, hydration,
//      etc.) — what reaches the browser is plain pre-rendered HTML.
//   2. Smaller JS payload (no react-markdown / SyntaxHighlighter on client).
//   3. SSR-safe — no SSR/CSR mismatch possible because there is no CSR
//      rendering of this content.
//
// Mermaid is the ONE thing that must run in the browser (it touches DOM/SVG).
// We emit `<pre data-mermaid data-mermaid-src="…">` placeholders here and let
// the tiny client-side <MermaidEnhancer> swap them for SVG after mount.

import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeRaw from 'rehype-raw'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism'

interface Props {
  markdown: string
  /** Service name — used to rewrite relative .md links to /services/<name>/docs/<slug>. */
  serviceName: string
}

export function MarkdownView({ markdown, serviceName }: Props) {
  const components: Components = {
    a({ node: _node, href, children, ...rest }) {
      if (!href || href.startsWith('#')) {
        return <a href={href} {...rest}>{children}</a>
      }
      if (href.startsWith('http://') || href.startsWith('https://')) {
        return <a href={href} target="_blank" rel="noreferrer" {...rest}>{children}</a>
      }
      if (href.startsWith('/')) {
        return <a href={href} {...rest}>{children}</a>
      }
      const cleaned = href.replace(/^\.\//, '').replace(/\.md$/, '').replace(/^README$/i, 'index')
      if (cleaned.startsWith('../')) {
        // Out-of-repo-tree link (e.g. ../../docs/adr/...) — render as muted
        // tooltip-only span. The target is not exposed via this proxy, so a
        // live link would 404 noisily.
        return (
          <span style={{ color: 'var(--text-tertiary)' }} title={`Out-of-tree: ${href}`}>
            {children}
          </span>
        )
      }
      return <a href={`/services/${serviceName}/docs/${cleaned}`} {...rest}>{children}</a>
    },

    code({ node: _node, className, children, ...rest }) {
      const text = String(children ?? '')
      const match = /language-(\w+)/.exec(className ?? '')
      const lang = match?.[1]
      // react-markdown v9 dropped the `inline` prop. We detect block code by
      // either a language class or a trailing newline (set by markdown for
      // fenced blocks).
      const isBlock = Boolean(lang) || text.endsWith('\n')
      const value = text.replace(/\n$/, '')

      if (isBlock && lang === 'mermaid') {
        return <pre data-mermaid="" data-mermaid-src={value} />
      }
      if (isBlock && lang) {
        return (
          <SyntaxHighlighter
            language={lang}
            style={oneLight}
            PreTag="div"
            customStyle={{
              fontSize: '12.5px',
              borderRadius: '6px',
              padding: '12px 14px',
              background: 'var(--surface-2)',
              margin: '12px 0',
            }}
          >
            {value}
          </SyntaxHighlighter>
        )
      }
      if (isBlock) {
        return (
          <pre style={{
            fontSize: '12.5px', borderRadius: '6px', padding: '12px 14px',
            background: 'var(--surface-2)', margin: '12px 0', overflowX: 'auto',
          }}><code>{value}</code></pre>
        )
      }
      return <code className={className} {...rest}>{children}</code>
    },

    table({ node: _node, children, ...rest }) {
      return (
        <div style={{ overflowX: 'auto', marginBottom: '16px' }}>
          <table
            style={{ borderCollapse: 'collapse', width: '100%', fontSize: '13px' }}
            {...rest}
          >
            {children}
          </table>
        </div>
      )
    },
    th({ node: _node, children, ...rest }) {
      return (
        <th
          style={{
            textAlign: 'left', padding: '8px 12px',
            borderBottom: '2px solid var(--border)',
            background: 'var(--surface-2)', fontWeight: 600,
          }}
          {...rest}
        >
          {children}
        </th>
      )
    },
    td({ node: _node, children, ...rest }) {
      return (
        <td
          style={{ padding: '8px 12px', borderBottom: '1px solid var(--border)' }}
          {...rest}
        >
          {children}
        </td>
      )
    },
  }

  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeRaw]}
        components={components}
      >
        {markdown}
      </ReactMarkdown>
    </div>
  )
}
