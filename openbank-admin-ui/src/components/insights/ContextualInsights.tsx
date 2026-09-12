// SPDX-License-Identifier: Apache-2.0
'use client'

import { Children, cloneElement, isValidElement, useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { Activity, ChevronDown, ExternalLink, RefreshCw } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import styles from './ContextualInsights.module.css'

export interface InsightPanel {
  id: number
  titleCs: string
  titleEn: string
  descriptionCs: string
  descriptionEn: string
  height?: 'stat' | 'trend'
}

interface Props {
  dashboardUid: string
  titleCs: string
  titleEn: string
  descriptionCs: string
  descriptionEn: string
  panels: InsightPanel[]
  from?: string
  to?: string
  defaultOpen?: boolean
}

const DEFAULT_FROM = 'now-24h'
const DEFAULT_TO = 'now'

function utcBoundary(value: string | undefined, end: boolean) {
  if (!value) return end ? DEFAULT_TO : DEFAULT_FROM
  const parsed = Date.parse(`${value}T${end ? '23:59:59.999' : '00:00:00'}Z`)
  return Number.isFinite(parsed) ? String(parsed) : (end ? DEFAULT_TO : DEFAULT_FROM)
}

export function ContextualInsights({
  dashboardUid, titleCs, titleEn, descriptionCs, descriptionEn, panels,
  from, to, defaultOpen = false,
}: Props) {
  const { t } = useLanguage()
  const [open, setOpen] = useState(defaultOpen)
  const [theme, setTheme] = useState('light')
  const [refresh, setRefresh] = useState(0)
  const contentId = useId()

  useEffect(() => {
    const sync = () => setTheme(document.documentElement.classList.contains('dark') ? 'dark' : 'light')
    sync()
    const observer = new MutationObserver(sync)
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    return () => observer.disconnect()
  }, [])

  const commonParams = useMemo(() => new URLSearchParams({
    kiosk: '1', theme, timezone: 'utc', from: utcBoundary(from, false), to: utcBoundary(to, true),
  }), [from, theme, to])
  const detailUrl = `/tools/grafana/d/${dashboardUid}?${new URLSearchParams({ theme, timezone: 'utc', from: utcBoundary(from, false), to: utcBoundary(to, true) })}`

  return <section className={styles.section}>
    <div className={styles.header}>
      <button type="button" className={styles.toggle} onClick={() => setOpen(value => !value)} aria-expanded={open} aria-controls={contentId}>
        <span className={styles.icon}><Activity size={17} aria-hidden="true" /></span>
        <span className={styles.copy}>
          <span className={styles.eyebrow}>{t('Živý provozní přehled', 'Live operational insight')}</span>
          <span className={styles.title}>{t(titleCs, titleEn)}</span>
          <span className={styles.description}>{t(descriptionCs, descriptionEn)}</span>
        </span>
        <ChevronDown size={18} aria-hidden="true" className={open ? styles.chevronOpen : styles.chevron} />
      </button>
      <div className={styles.actions}>
        {open && <button type="button" className="btn btn-secondary btn-sm" onClick={() => setRefresh(value => value + 1)}>
          <RefreshCw size={13} aria-hidden="true" />{t('Obnovit', 'Refresh')}
        </button>}
        <a className="btn btn-secondary btn-sm" href={detailUrl} target="_blank" rel="noreferrer">
          <ExternalLink size={13} aria-hidden="true" />{t('Detailní analýza', 'Detailed analysis')}
        </a>
      </div>
    </div>
    {open && <div id={contentId} className={styles.body} role="region" aria-label={t(titleCs, titleEn)}>
      <p className={styles.freshness}>{from && to
        ? t('Živá data · UTC · období je převzaté z této stránky', 'Live data · UTC · period follows this page')
        : t('Živá data · UTC · posledních 24 hodin', 'Live data · UTC · last 24 hours')}</p>
      <GrafanaPanelGrid key={`${dashboardUid}-${theme}-${refresh}-${from}-${to}`}>
        {panels.map(panel => {
          const params = new URLSearchParams(commonParams)
          params.set('panelId', String(panel.id))
          params.set('refresh', '1m')
          return <GrafanaPanelCard key={panel.id} panel={panel}
            src={`/tools/grafana/d-solo/${dashboardUid}?${params}`} />
        })}
      </GrafanaPanelGrid>
    </div>}
  </section>
}

function GrafanaPanelGrid({ children }: { children: ReactNode }) {
  const [activeCount, setActiveCount] = useState(1)
  return <div className={styles.grid}>{Children.map(children, (card, index) => {
    if (!isValidElement<GrafanaPanelCardProps>(card)) return card
    return cloneElement(card, {
      enabled: index < activeCount,
      onReady: () => setActiveCount(value => Math.max(value, index + 2)),
    })
  })}</div>
}

interface GrafanaPanelCardProps {
  panel: InsightPanel
  src: string
  enabled?: boolean
  onReady?: () => void
}

function GrafanaPanelCard({ panel, src, enabled = false, onReady }: GrafanaPanelCardProps) {
  const { t } = useLanguage()
  return <article className={styles.panel}>
    <div className={styles.panelCopy}>
      <h3>{t(panel.titleCs, panel.titleEn)}</h3>
      <p>{t(panel.descriptionCs, panel.descriptionEn)}</p>
    </div>
    <GrafanaPanel src={src} title={t(panel.titleCs, panel.titleEn)} tall={panel.height === 'trend'} enabled={enabled} onReady={onReady} />
  </article>
}

function GrafanaPanel({ src, title, tall, enabled, onReady }: {
  src: string
  title: string
  tall: boolean
  enabled: boolean
  onReady?: () => void
}) {
  const { t } = useLanguage()
  const [state, setState] = useState<'loading' | 'ready' | 'slow' | 'unavailable'>('loading')
  const frame = useRef<HTMLIFrameElement>(null)
  const onReadyRef = useRef(onReady)

  useEffect(() => {
    onReadyRef.current = onReady
  }, [onReady])

  const markReady = useCallback(() => {
    try {
      const path = frame.current?.contentWindow?.location.pathname
      const doc = frame.current?.contentDocument
      if (!path?.startsWith('/tools/grafana/d-solo/') || !doc) return false
      setState('ready')
      onReadyRef.current?.()
      return true
    } catch { return false }
  }, [])

  useEffect(() => {
    if (!enabled) return
    const started = Date.now()
    let slowShown = false
    const poll = window.setInterval(() => {
      try {
        if (markReady()) {
          window.clearInterval(poll)
          return
        }
      } catch { /* Keycloak is cross-origin while the authenticated session is established. */ }
      const elapsed = Date.now() - started
      if (elapsed >= 20_000 && !slowShown) {
        slowShown = true
        setState('slow')
      }
      if (elapsed >= 120_000) {
        setState('unavailable')
        window.clearInterval(poll)
      }
    }, 500)
    return () => window.clearInterval(poll)
  }, [enabled, markReady, src])

  return <div className={`${styles.frameWrap} ${tall ? styles.frameTrend : ''}`}>
    {state === 'loading' && <div className={styles.skeleton} role="status"><span>{t('Načítám data…', 'Loading data…')}</span></div>}
    {state === 'slow' && <div className={styles.unavailable} role="status">
      <RefreshCw size={18} aria-hidden="true" />
      <span>{t('Připojení trvá déle, stále to zkouším…', 'Connection is taking longer; still trying…')}</span>
    </div>}
    {state === 'unavailable' && <div className={styles.unavailable} role="status">
      <Activity size={18} aria-hidden="true" />
      <span>{t('Panel teď není dostupný', 'Panel is unavailable right now')}</span>
    </div>}
    {enabled && <iframe ref={frame} src={src} title={title} scrolling="no" onLoad={markReady}
      className={state === 'ready' ? styles.frameReady : styles.frameHidden} />}
  </div>
}
