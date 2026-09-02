// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Incident = { id: string; title: string; severity: string; status: string; category: string; detectedAt: string; affectedServices: string[]; reportedToRegulator: boolean }
type Envelope = { available: true; incidents: Incident[] } | { available: false; reason: string }

export default function IncidentsPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<Envelope | null>(null)
  const [loading, setLoading] = useState(true)
  const load = useCallback(async () => {
    setLoading(true)
    try { setData(await (await fetch('/api/security/incidents', { cache: 'no-store' })).json() as Envelope) }
    catch { setData({ available: false, reason: 'unreachable' }) }
    finally { setLoading(false) }
  }, [])
  useEffect(() => { void load() }, [load])
  let unavailableDetail: string | null = null
  if (data?.available === false) {
    if (data.reason === 'unauthorized') {
      unavailableDetail = t('Vaše role nemá oprávnění zobrazit registr. Požádejte správce o přístup system:view.', 'Your role cannot view this register. Ask an administrator for system:view access.')
    } else if (data.reason === 'not_deployed') {
      unavailableDetail = t('Zdroj incidentů není v tomto prostředí nasazen. Nejde o potvrzení, že žádné incidenty neexistují.', 'The incident source is not deployed in this environment. This does not confirm that no incidents exist.')
    } else if (data.reason === 'unreachable') {
      unavailableDetail = t('Zdroj incidentů momentálně neodpovídá. Zkuste načtení zopakovat; data mohou být neúplná.', 'The incident source is not responding. Try again; the data may be incomplete.')
    } else {
      unavailableDetail = t('Registr nelze nyní načíst. Zkuste načtení zopakovat nebo ověřte stav služby.', 'The register cannot be loaded right now. Try again or check the service status.')
    }
  }
  return <AuthGuard permission="system:view"><div style={{ padding: '28px 32px', maxWidth: 1400 }}>
    <PageHeader icon={<AlertTriangle size={20} aria-hidden="true" />} title={t('ICT incidenty', 'ICT incidents')} subtitle={t('DORA registr incidentů (trvalá evidence)', 'DORA incident register (durable evidence)')} actions={<button type="button" onClick={() => void load()} disabled={loading} aria-busy={loading} aria-label={t('Obnovit ICT incidenty', 'Refresh ICT incidents')} className="btn btn-secondary btn-sm"><RefreshCw aria-hidden="true" size={13} /> {t('Obnovit', 'Refresh')}</button>} />
    {loading && !data && <p role="status" aria-live="polite">{t('Načítám registr ICT incidentů…', 'Loading the ICT incident register…')}</p>}
    {unavailableDetail && <div role="alert"><strong>{t('Registr incidentů není k dispozici', 'Incident register unavailable')}</strong><p>{unavailableDetail}</p></div>}
    {data?.available && data.incidents.length === 0 && <p role="status">{t('Žádné evidované incidenty. Tento stav znamená, že se registr podařilo načíst a neobsahuje žádný záznam.', 'No recorded incidents. This means the register loaded successfully and contains no records.')}</p>}
    {data?.available && data.incidents.length > 0 && <div style={{ overflowX: 'auto' }}><table aria-busy={loading}><caption className="sr-only">{t('DORA registr ICT incidentů', 'DORA ICT incident register')}</caption><thead><tr><th>{t('Název', 'Title')}</th><th>{t('Kategorie', 'Category')}</th><th>{t('Závažnost', 'Severity')}</th><th>{t('Stav', 'Status')}</th><th>{t('Zjištěno', 'Detected')}</th><th>{t('Regulátor', 'Regulator')}</th><th>{t('Služby', 'Services')}</th></tr></thead><tbody>{data.incidents.map(i => <tr key={i.id}><td>{i.title}</td><td>{i.category || '—'}</td><td>{i.severity}</td><td>{i.status}</td><td>{new Date(i.detectedAt).toLocaleString(dateLocale)}</td><td>{i.reportedToRegulator ? t('Oznámeno', 'Reported') : t('Neoznámeno', 'Not reported')}</td><td>{i.affectedServices.join(', ')}</td></tr>)}</tbody></table></div>}
  </div></AuthGuard>
}
