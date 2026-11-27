// SPDX-License-Identifier: Apache-2.0

'use client'

import { useEffect, useState, type FormEvent } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface Policy {
  minimumAgeMinutes: number
  expiresAt: string
  reason: string
}

export function McpPasskeyAgeException({ partyId }: { partyId: string }) {
  const { t, language } = useLanguage()
  const url = `/api/parties/${encodeURIComponent(partyId)}/mcp-native-passkey-age-exception`
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [available, setAvailable] = useState(false)
  const [minutes, setMinutes] = useState('1')
  const [validFor, setValidFor] = useState('15')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    fetch(url, { cache: 'no-store', signal: controller.signal })
      .then(async response => {
        if (controller.signal.aborted) return
        if (response.ok) setPolicy(await response.json() as Policy)
        setAvailable(response.ok || response.status === 404)
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => controller.abort()
  }, [url])

  async function change(method: 'PUT' | 'DELETE') {
    setBusy(true)
    setFailed(false)
    try {
      const response = await fetch(url, {
        method,
        ...(method === 'PUT' ? {
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            minimumAgeMinutes: Number(minutes),
            expiresAt: new Date(Date.now() + Number(validFor) * 60_000).toISOString(),
            reason,
          }),
        } : {}),
        signal: AbortSignal.timeout(8000),
      })
      if (!response.ok) {
        setFailed(true)
        return
      }
      setPolicy(method === 'PUT' ? await response.json() as Policy : null)
      setReason('')
    } catch {
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!busy) void change('PUT')
  }

  if (!available) return null

  return (
    <section className="card space-y-3 p-5" aria-labelledby="mcp-passkey-age-title">
      <h2 id="mcp-passkey-age-title" className="font-semibold">
        {t('Stáří passkey pro MCP v sandboxu', 'MCP passkey age in the sandbox')}
      </h2>
      <p className="text-sm text-muted-foreground">
        {t('Dočasná výjimka pro tento syntetický účet. Samostatné schválení v aplikaci je stále povinné.',
          'Temporary exception for this synthetic account. Separate approval in the app is still required.')}
      </p>
      {policy && (
        <p role="status">
          {t('Minimum v minutách', 'Minimum in minutes')}{' '}{policy.minimumAgeMinutes}
          {' · '}{t('Platí do', 'Expires at')}{' '}
          <time dateTime={policy.expiresAt}>{new Date(policy.expiresAt).toLocaleString(language)}</time>
        </p>
      )}
      <form className="space-y-3" onSubmit={save}>
        <label className="block">
          {t('Minimální stáří v minutách', 'Minimum age in minutes')}{' '}
          <input className="input" type="number" min="1" max="4319" required value={minutes}
            onChange={event => setMinutes(event.target.value)} />
        </label>
        <label className="block">
          {t('Platnost výjimky v minutách', 'Exception duration in minutes')}{' '}
          <select className="input" value={validFor} onChange={event => setValidFor(event.target.value)}>
            <option value="15">15</option>
            <option value="30">30</option>
            <option value="60">60</option>
          </select>
        </label>
        <label className="block">
          {t('Důvod změny', 'Reason for change')}{' '}
          <input className="input" required maxLength={250} value={reason}
            onChange={event => setReason(event.target.value)} />
        </label>
        <button className="btn btn-primary" type="submit" disabled={busy || !reason.trim()}>
          {t('Uložit výjimku', 'Save exception')}
        </button>
        {policy && (
          <button className="btn btn-secondary ml-2" type="button" disabled={busy} onClick={() => void change('DELETE')}>
            {t('Zrušit výjimku', 'Revoke exception')}
          </button>
        )}
      </form>
      {failed && <p role="alert">{t('Změnu se nepodařilo potvrdit.', 'Could not confirm the change.')}</p>}
    </section>
  )
}
