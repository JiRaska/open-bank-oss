// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { usePathname } from 'next/navigation'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { isPublicSurface } from '@/lib/auth/publicSurface'
import { Bot, Send, X, Loader2, Wrench, ShieldCheck, ShieldAlert, AlertTriangle } from 'lucide-react'

interface ToolCall { tool: string; allowed: boolean; resultPreview: string }
/** isProposal (D4 ADR-0031): true when the assistant recommends an action requiring operator review. */
interface Msg { role: 'user' | 'assistant'; content: string; toolCalls?: ToolCall[]; isProposal?: boolean }
interface ModelInfo { id: string; provider: string; sensitivity: string }

/**
 * Human label for a model id. The VALUE stays the raw id — that is what agent-service resolves and
 * what every audit event records, so the option value must never diverge from it. Only the visible
 * text is friendlier.
 *
 * An unknown id falls through to its own last path segment rather than to a generic "model":
 * whoever adds a route to litellm-config should get something readable in the picker without having
 * to remember to edit this file, and a stale mapping must never hide which model is actually
 * selected.
 */
const MODEL_LABELS: Record<string, string> = {
  'mock-echo': 'mock (offline)',
  'llama-3.3-70b-versatile': 'Llama 3.3 70B · free tier',
  'openai/gpt-oss-120b': 'GPT-OSS 120B · fast',
  'deepseek-ai/DeepSeek-V4-Pro': 'DeepSeek V4 Pro · strongest',
}

export function modelLabel(id: string): string {
  // `??` is not enough on its own: 'vendor/'.split('/').pop() is an EMPTY STRING, not undefined,
  // so it would pass through and render a blank option the operator cannot identify. The final
  // literal covers the same shape for an empty id — agent-service should never send one, and if it
  // ever does the picker must still show a row you can see and report, not an invisible one.
  return MODEL_LABELS[id] || id.split('/').filter(Boolean).pop() || id || '(unnamed model)'
}

export function AgentDock() {
  const pathname = usePathname()
  const publicSurface = isPublicSurface(pathname)
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [models, setModels] = useState<ModelInfo[]>([])
  const [model, setModel] = useState<string>('')
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (publicSurface || !open || models.length) return
    fetch('/api/agent/chat')
      .then(r => r.json())
      .then(d => { setModels(d.models ?? []); setModel(d.default ?? '') })
      .catch(() => {})
  }, [open, models.length, publicSurface])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, busy])

  if (publicSurface) return null

  async function send() {
    const text = input.trim()
    if (!text || busy) return
    const next: Msg[] = [...messages, { role: 'user', content: text }]
    setMessages(next)
    setInput('')
    setBusy(true)
    try {
      const res = await fetch('/api/agent/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          messages: next.map(m => ({ role: m.role, content: m.content })),
          model: model || undefined,
          context: `admin page ${pathname}`,
        }),
      })
      const data = await res.json()
      setMessages(m => [...m, {
        role: 'assistant',
        content: data.error ? `⚠ ${data.error}` : (data.reply || '(no reply)'),
        toolCalls: data.toolCalls,
        isProposal: data.isProposal === true,
      }])
    } catch (e) {
      setMessages(m => [...m, { role: 'assistant', content: `⚠ ${e instanceof Error ? e.message : 'error'}` }])
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {/* Floating bot button — present on every page via root layout */}
      <button
        type="button"
        aria-label={open ? t('Zavřít asistenta', 'Close assistant') : t('Otevřít asistenta', 'Open assistant')}
        aria-expanded={open}
        aria-controls={open ? 'agent-dock-panel' : undefined}
        onClick={() => setOpen(o => !o)}
        style={{
          position: 'fixed', bottom: 24, right: 24, zIndex: 1000,
          width: 52, height: 52, borderRadius: '50%', border: 'none', cursor: 'pointer',
          background: 'var(--accent)', color: '#fff',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 6px 20px rgba(0,0,0,0.18)',
        }}
      >
        {open ? <X size={22} aria-hidden="true" /> : <Bot size={22} aria-hidden="true" />}
      </button>

      {open && (
        <div
          id="agent-dock-panel"
          role="region"
          aria-label={t('Panel asistenta OpenBank', 'OpenBank assistant panel')}
          style={{
            position: 'fixed', bottom: 88, right: 24, zIndex: 1000,
            width: 400, maxWidth: 'calc(100vw - 48px)', height: 540, maxHeight: 'calc(100vh - 120px)',
            display: 'flex', flexDirection: 'column',
            background: 'var(--surface)', border: '1px solid var(--border)',
            borderRadius: 'var(--r-lg)', boxShadow: '0 12px 40px rgba(0,0,0,0.22)', overflow: 'hidden',
          }}
        >
          {/* Header */}
          <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 8 }}>
            <Bot size={16} aria-hidden="true" style={{ color: 'var(--accent)' }} />
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' }}>{t('OpenBank asistent', 'OpenBank Assistant')}</div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('jen pro čtení · řízeno politikou · auditováno', 'read-only · policy-gated · audited')}</div>
            </div>
            {models.length > 0 && (
              <select
                value={model}
                onChange={e => setModel(e.target.value)}
                aria-label={t('Model asistenta', 'Assistant model')}
                style={{ fontSize: 11, padding: '3px 6px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface-2)', color: 'var(--text-secondary)' }}
              >
                {models.map(m => <option key={m.id} value={m.id} title={m.id}>{modelLabel(m.id)}</option>)}
              </select>
            )}
          </div>

          {/* Messages */}
          <div ref={scrollRef} style={{ flex: 1, overflowY: 'auto', padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {messages.length === 0 && (
              <div style={{ fontSize: 12, color: 'var(--text-tertiary)', lineHeight: 1.6 }}>
                {t(
                  'Zeptejte se na účet, zůstatek nebo transakci. Umím pouze číst, každé volání nástroje je prověřeno politikou a auditováno.',
                  'Ask about an account, balance or transaction. I can only read, every tool call is policy-checked and audited.',
                )}
              </div>
            )}
            {messages.map((m, i) => (
              <div key={i} style={{ alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '88%' }}>
                {/* D4 proposal banner: visible when the assistant recommends an action (ADR-0031 D4).
                    Charter requires_human: every proposal — operator must explicitly confirm before acting. */}
                {m.isProposal && (
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 5,
                    fontSize: 10.5, fontWeight: 600, color: 'var(--warning, #b45309)',
                    background: 'var(--warning-bg, #fffbeb)', border: '1px solid var(--warning-border, #fcd34d)',
                    borderRadius: '10px 10px 0 0', padding: '4px 8px',
                  }}>
                    <AlertTriangle size={11} />
                    {t('Vyžaduje vaši kontrolu před provedením', 'Requires your review before acting')}
                  </div>
                )}
                <div style={{
                  fontSize: 12.5, lineHeight: 1.5, padding: '8px 11px', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                  background: m.role === 'user' ? 'var(--accent)' : 'var(--surface-2)',
                  color: m.role === 'user' ? '#fff' : 'var(--text-primary)',
                  border: m.isProposal
                    ? '1px solid var(--warning-border, #fcd34d)'
                    : m.role === 'user' ? 'none' : '1px solid var(--border)',
                  borderRadius: m.isProposal ? '0 0 10px 10px' : 10,
                  borderTop: m.isProposal ? 'none' : undefined,
                }}>
                  {m.content}
                </div>
                {m.toolCalls && m.toolCalls.length > 0 && (
                  <div style={{ marginTop: 5, display: 'flex', flexDirection: 'column', gap: 3 }}>
                    {m.toolCalls.map((tc, j) => (
                      <div key={j} style={{ fontSize: 11, color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: 5 }}>
                        <Wrench size={11} aria-hidden="true" />
                        <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>{tc.tool}</span>
                        {tc.allowed
                          ? <ShieldCheck size={11} aria-hidden="true" style={{ color: 'var(--success)' }} />
                          : <ShieldAlert size={11} aria-hidden="true" style={{ color: 'var(--danger)' }} />}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
            {busy && (
              <div style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 6, color: 'var(--text-tertiary)', fontSize: 12 }}>
                <Loader2 size={13} aria-hidden="true" className="animate-spin" /> {t('přemýšlím…', 'thinking…')}
              </div>
            )}
          </div>

          {/* Input */}
          <div style={{ padding: 10, borderTop: '1px solid var(--border)', display: 'flex', gap: 8 }}>
            <input
              id="agent-dock-input"
              aria-label={t('Zpráva pro asistenta', 'Message for assistant')}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
              placeholder={t('Zeptejte se asistenta…', 'Ask the assistant…')}
              disabled={busy}
              style={{ flex: 1, fontSize: 12.5, padding: '8px 10px', borderRadius: 8, border: '1px solid var(--border)', background: 'var(--surface-2)', color: 'var(--text-primary)' }}
            />
            <button
              type="button"
              aria-label={t('Odeslat zprávu', 'Send message')}
              onClick={send}
              disabled={busy || !input.trim()}
              style={{
                width: 38, borderRadius: 8, border: 'none', cursor: busy ? 'default' : 'pointer',
                background: 'var(--accent)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
                opacity: busy || !input.trim() ? 0.5 : 1,
              }}
            >
              <Send size={15} aria-hidden="true" />
            </button>
          </div>
        </div>
      )}
    </>
  )
}
