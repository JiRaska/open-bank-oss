// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useRef, useState } from 'react'
import { ExternalLink, RefreshCw } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// Keep the frame on the authenticated tools ingress. External origins require a separate CSP
// and SSO integration; accepting a build-time URL here silently bypassed that assumption.
const DASHBOARD = '/tools/grafana/d/openbank-business-warehouse'
const DASHBOARD_MIN_HEIGHT = 1420

export function WarehouseDashboard({ from, to }: { from: string; to: string }) {
  const { t } = useLanguage()
  const frame = useRef<HTMLIFrameElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<'loading' | 'ready' | 'unavailable'>('loading')
  const [theme, setTheme] = useState('light')
  const [frameHeight, setFrameHeight] = useState(DASHBOARD_MIN_HEIGHT)

  useEffect(() => {
    const sync = () => setTheme(document.documentElement.classList.contains('dark') ? 'dark' : 'light')
    sync()
    const observer = new MutationObserver(sync)
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    return () => observer.disconnect()
  }, [])

  // Grafana 13 accepts `1` as the explicit full-kiosk value. Empty and `tv` values are ignored.
  const params = new URLSearchParams({ kiosk: '1', theme, from: String(Date.parse(`${from}T00:00:00Z`)), to: String(Date.parse(`${to}T23:59:59.999Z`)), timezone: 'utc' })
  const url = `${DASHBOARD}?${params}`

  useEffect(() => {
    // load fires for a blocked frame and a login page too. Verify the actual same-origin
    // dashboard DOM, allowing OAuth to finish before exposing it as ready.
    const started = Date.now()
    const reset = window.setTimeout(() => setState('loading'), 0)
    let dashboardObserver: ResizeObserver | null = null
    const sizeDashboard = () => {
      const doc = frame.current?.contentDocument
      if (!doc) return
      setFrameHeight(Math.max(DASHBOARD_MIN_HEIGHT, doc.documentElement.scrollHeight, doc.body.scrollHeight))
    }
    const timer = window.setInterval(() => {
      try {
        const doc = frame.current?.contentDocument
        if (doc?.querySelector('[data-testid="data-testid Panel header"], [data-testid^="data-testid Panel header "], .react-grid-layout')) {
          sizeDashboard()
          dashboardObserver = new ResizeObserver(() => sizeDashboard())
          dashboardObserver.observe(doc.documentElement)
          setState('ready')
          window.clearInterval(timer)
          return
        }
      } catch { /* Cross-origin Keycloak redirect is expected during SSO. */ }
      if (Date.now() - started >= 25_000) {
        setState('unavailable')
        window.clearInterval(timer)
      }
    }, 500)
    return () => { window.clearInterval(timer); window.clearTimeout(reset); dashboardObserver?.disconnect() }
  }, [url, attempt])

  return <section className="card" style={{ padding: 20 }} aria-label={t('Průzkum dat', 'Data exploration')}>
    <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
      <div>
        <h2 className="font-semibold">{t('Průzkum dat', 'Data exploration')}</h2>
        <p className="text-sm text-[var(--text-secondary)]">{t('Objem událostí, onboarding a kvalita dat za vybrané období (UTC).', 'Event volume, onboarding and data quality for the selected period (UTC).')}</p>
      </div>
      <div className="flex gap-2">
        <button className="btn btn-secondary" onClick={() => { setState('loading'); setAttempt((n) => n + 1) }}><RefreshCw size={14} />{t('Obnovit grafy', 'Refresh charts')}</button>
        <a href={url.replace('kiosk=1&', '')} target="_blank" rel="noreferrer" className="btn btn-secondary"><ExternalLink size={14} />{t('Detail v Grafaně', 'Explore in Grafana')}</a>
      </div>
    </div>
    {state === 'loading' && <div role="status" className="p-6 text-sm text-[var(--text-secondary)]">{t('Načítání grafů a ověření přihlášení…', 'Loading charts and verifying your session…')}</div>}
    {state === 'unavailable' && <div role="status" className="p-6 rounded-xl bg-[var(--surface-2)] text-sm">
      <p className="font-semibold">{t('Grafy se zatím nepodařilo načíst', 'Charts could not be loaded yet')}</p>
      <p className="mt-2 text-[var(--text-secondary)]">{t('Otevřete detail v Grafaně pro dokončení přihlášení a potom obnovte grafy. Reporty výše můžete používat nezávisle.', 'Open Grafana to complete sign-in, then refresh the charts. The reports above remain available independently.')}</p>
    </div>}
    <iframe key={`${url}-${attempt}`} ref={frame} src={url} title={t('Trendy a kvalita dat', 'Trends and data quality')}
      scrolling="no"
      style={{ width: '100%', height: state === 'ready' ? frameHeight : 0, border: 0, overflow: 'hidden', visibility: state === 'ready' ? 'visible' : 'hidden', display: state === 'unavailable' ? 'none' : 'block', colorScheme: theme }} />
  </section>
}
