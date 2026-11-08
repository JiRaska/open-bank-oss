// SPDX-License-Identifier: Apache-2.0
'use client'
/* eslint-disable @next/next/no-img-element -- The library displays unchanged game sprites. */
import { useCallback, useEffect, useState } from 'react'
import { useSession } from 'next-auth/react'
import Link from 'next/link'
import { ArrowLeft, ArrowRight, Copy, Plus, Save, Layers3, GripVertical, Eye, Trash2, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { hasPermission } from '@/lib/auth/roles'
import { classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import {
  art,
  components,
  courseProblems,
  parseCatalog,
  worlds,
  type Catalog,
  type Course,
  type Item,
  type Level,
} from '@/lib/game/editor'
import { ScenePreview } from './ScenePreview'
import styles from './page.module.css'

async function readCatalog(): Promise<{ value: Catalog } | { kind: BffFailure }> {
  const response = await fetch('/api/game/catalog', { cache: 'no-store', signal: AbortSignal.timeout(10000) })
  if (!response.ok) return { kind: await classifyBffFailure(response) }
  return { value: parseCatalog(await response.json()) }
}

export default function GameCatalogPage() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const canManage = hasPermission(session?.user?.roles ?? [], 'game:edit')
  const canPublish = hasPermission(session?.user?.roles ?? [], 'game:manage')
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: BffFailure } | null>(null)
  const [busy, setBusy] = useState(true)
  const [dirty, setDirty] = useState(false)
  const [message, setMessage] = useState<'saved' | 'conflict' | 'invalid' | 'save' | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState('ALL')
  const [camera, setCamera] = useState(0)
  const [tool, setTool] = useState<string | null>(null)
  const [item, setItem] = useState<{ key: string; index: number } | null>(null)
  const [templates, setTemplates] = useState<Record<string, Course>>({})
  const load = useCallback(async () => {
    setBusy(true)
    setUnavailable(null)
    try {
      const result = await readCatalog()
      if ('kind' in result) {
        setUnavailable({ kind: result.kind })
        return
      }
      setCatalog(result.value)
      setDirty(false)
      setMessage(null)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setBusy(false)
    }
  }, [])
  useEffect(() => {
    let active = true
    void readCatalog()
      .then((result) => {
        if (!active) return
        if ('kind' in result) setUnavailable({ kind: result.kind })
        else setCatalog(result.value)
      })
      .catch(() => {
        if (active) setUnavailable({ kind: 'unreachable' })
      })
      .finally(() => {
        if (active) setBusy(false)
      })
    return () => {
      active = false
    }
  }, [])
  useEffect(() => {
    let active = true
    void Promise.all(
      worlds.map(async (w) => {
        const r = await fetch(`/lion-quest/${w.id}.json`)
        if (!r.ok) throw new Error('template')
        return [w.id, await r.json()] as const
      }),
    )
      .then((entries) => {
        if (active) setTemplates(Object.fromEntries(entries))
      })
      .catch(() => {})
    return () => {
      active = false
    }
  }, [])
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  const levels = catalog?.levels.toSorted((a, b) => a.order - b.order) ?? []
  const level = levels.find((l) => l.id === selected)
  const course = level?.content ?? (level ? templates[level.theme] : null)
  const problems = course ? courseProblems(course) : []
  const selectedItem =
    item && course
      ? item.key === 'piggies'
        ? { x: course.piggies[item.index] }
        : (course[item.key as keyof Course] as Item[] | undefined)?.[item.index]
      : null
  function modify(next: Level[]) {
    if (!canManage || busy || !catalog) return
    setCatalog({ ...catalog, levels: next })
    setDirty(true)
    setMessage(null)
  }
  function change(patch: Partial<Level>) {
    if (level && (canPublish || level.state !== 'LIVE'))
      modify(levels.map((l) => (l.id === level.id ? { ...l, ...patch } : l)))
  }
  function changeCourse(patch: Partial<Course>) {
    if (course && level) change({ content: { ...course, ...patch, id: level.id, theme: level.theme } })
  }
  function open(id: string) {
    setSelected(id)
    setCamera(0)
    setTool(null)
    setItem(null)
  }
  function add(theme: string, from?: Level) {
    if (!catalog || levels.length >= 30 || !templates[theme]) return
    const id = `${theme}_${crypto.randomUUID().replaceAll('-', '').slice(0, 12)}`
    const order = Math.max(-1, ...levels.map((l) => l.order)) + 1
    if (order > 99) return
    const world = worlds.find((w) => w.id === theme)!
    const base = from ?? {
      theme,
      titleCs: world.cs,
      titleEn: world.en,
      startsAt: null,
      endsAt: null,
      requirement: {
        kind: 'PREVIOUS_LEVEL' as const,
        source: null,
        count: 1,
        hintCs: 'Dokonči předchozí kolo',
        hintEn: 'Complete previous chapter',
      },
    }
    modify([...levels, { ...base, id, order, state: 'OFF', content: { ...(from?.content ?? templates[theme]), id } }])
    open(id)
  }
  function move(id: string, direction: number) {
    const index = levels.findIndex((l) => l.id === id),
      next = index + direction
    if (
      next < 0 ||
      next >= levels.length ||
      (!canPublish && (levels[index].state === 'LIVE' || levels[next].state === 'LIVE'))
    )
      return
    modify(
      levels.map((l, i) =>
        i === index ? { ...l, order: levels[next].order } : i === next ? { ...l, order: levels[index].order } : l,
      ),
    )
  }
  function place(x: number, y: number) {
    if (!tool || !course) return
    if (tool === 'piggies') {
      changeCourse({ piggies: [...course.piggies, x] })
      setItem({ key: 'piggies', index: course.piggies.length })
      return
    }
    const component = components.find((c) => c.key === tool)!
    const list = course[component.key] ?? []
    if (list.length >= 200) return
    const object = {
      ...component.defaults,
      ...(tool === 'bats' ? { cx: x, cy: y } : tool === 'bridgeTraps' ? { x } : { x, y: tool === 'thieves' ? 0 : y }),
    }
    changeCourse({ [tool]: [...list, object] })
    setItem({ key: tool, index: list.length })
  }
  async function save() {
    if (!catalog || !canManage) return
    if (catalog.levels.some((l) => l.content && courseProblems(l.content).length)) {
      setMessage('invalid')
      return
    }
    setBusy(true)
    setMessage(null)
    try {
      const response = await fetch('/api/game/catalog', {
        method: 'PUT',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(catalog),
        signal: AbortSignal.timeout(10000),
      })
      if (!response.ok) {
        setMessage(response.status === 409 ? 'conflict' : response.status === 400 ? 'invalid' : 'save')
        return
      }
      setCatalog(parseCatalog(await response.json()))
      setDirty(false)
      setMessage('saved')
    } catch {
      setMessage('save')
    } finally {
      setBusy(false)
    }
  }
  const stateName = (state: string) =>
    state === 'LIVE'
      ? t('Publikováno', 'Published')
      : state === 'BETA'
        ? t('Testování', 'Testing')
        : t('Rozpracováno', 'Draft')
  const fieldName = (key: string) =>
    ({
      x: t('Vzdálenost od startu', 'Distance from start'),
      cx: t('Střed letu', 'Flight centre'),
      y: t('Výška', 'Height'),
      cy: t('Výška letu', 'Flight height'),
      w: t('Šířka', 'Width'),
      span: t('Rozsah letu', 'Flight range'),
      boss: t('Hlavní nepřítel', 'Boss'),
      fake: t('Falešná mince', 'Fake coin'),
      dx: t('Vodorovný pohyb', 'Horizontal travel'),
      dy: t('Svislý pohyb', 'Vertical travel'),
      period: t('Doba pohybu (s)', 'Motion period (s)'),
      offset: t('Posun v cyklu', 'Cycle offset'),
      collapseAfter: t('Zřícení po (s, 0 = nikdy)', 'Collapse after (s, 0 = never)'),
      direction: t('Směr (−1 vlevo, 1 vpravo)', 'Direction (−1 left, 1 right)'),
      baseY: t('Spodní hrana', 'Bottom edge'),
      kind: t('Druh překážky', 'Obstacle type'),
    })[key] ?? key
  const title = (l: Level) => (language === 'cs' ? l.titleCs : l.titleEn)
  return (
    <AuthGuard permission="game:view">
      <section className={styles.page} aria-labelledby="game-catalog-title">
        <Link href="/loyalty" className={styles.subtle}>
          ← {t('Lípa', 'Lípa')}
        </Link>
        <header className={styles.header}>
          <div>
            <p className={styles.eyebrow}>{t('HERNÍ STUDIO · PRAHA', 'GAME STUDIO · PRAGUE')}</p>
            <h1 id="game-catalog-title">{t('Lví stezka', 'Lion Quest')}</h1>
            <p className={styles.subtle}>
              {t('Malá dobrodružství. Velká motivace objevovat.', 'Small adventures. A big reason to explore.')}
            </p>
          </div>
          <div className={styles.actions}>
            {dirty && <span className={styles.badge}>{t('Neuložené změny', 'Unsaved changes')}</span>}
            {canManage && (
              <button className={styles.primary} disabled={busy || !dirty} onClick={() => void save()}>
                <Save size={16} />
                {t('Uložit změny', 'Save changes')}
              </button>
            )}
          </div>
        </header>
        {unavailable && (
          <DataUnavailable
            kind={unavailable.kind}
            service="engagement-service"
            feature={t('Herní studio', 'Game studio')}
            lang={language}
            dense
          />
        )}
        {message && (
          <p
            className={`${styles.notice} ${message === 'saved' ? styles.success : ''}`}
            role={message === 'saved' ? 'status' : 'alert'}
          >
            {message === 'saved'
              ? t('Změny jsou uložené a zapsané v auditu.', 'Changes saved and recorded in the audit.')
              : message === 'conflict'
                ? t(
                    'Katalog mezitím někdo změnil. Vaše úpravy zůstaly zachované; načtěte aktuální verzi před dalším uložením.',
                    'Someone changed the catalogue. Your edits remain here; reload before saving again.',
                  )
                : message === 'invalid'
                  ? t(
                      'Trať není připravena k uložení. Zkontrolujte terén, cíl a obsah kol.',
                      'The course is not ready to save. Check ground, finish and chapter content.',
                    )
                  : t(
                      'Změny se nepodařilo uložit. Vaše práce zůstává zde.',
                      'Changes could not be saved. Your work remains here.',
                    )}
          </p>
        )}
        {!level ? (
          <>
            <div className={styles.toolbar}>
              <div className={styles.tabs}>
                {['ALL', 'LIVE', 'BETA', 'OFF'].map((s) => (
                  <button key={s} className={styles.tab} aria-pressed={filter === s} onClick={() => setFilter(s)}>
                    {s === 'ALL' ? t('Všechna kola', 'All chapters') : stateName(s)}{' '}
                    {levels.filter((l) => s === 'ALL' || l.state === s).length}
                  </button>
                ))}
              </div>
              <span className={styles.subtle}>
                <Layers3 size={14} /> {t('Číslujeme od nuly', 'Numbered from zero')}
              </span>
            </div>
            <div className={styles.catalog}>
              {levels
                .filter((l) => filter === 'ALL' || l.state === filter)
                .map((l, i) => (
                  <article className={styles.card} key={l.id}>
                    <button
                      className={styles.cover}
                      onClick={() => open(l.id)}
                      aria-label={`${t('Otevřít', 'Open')} ${title(l)}`}
                    >
                      <ScenePreview theme={l.theme} course={l.content ?? templates[l.theme]} compact label={title(l)} />
                    </button>
                    <div className={styles.cardBody}>
                      <div className={styles.cardHeading}>
                        <div>
                          <p className={styles.number}>
                            {t('KOLO', 'CHAPTER')} {l.order.toString().padStart(2, '0')}
                          </p>
                          <h2>{title(l)}</h2>
                        </div>
                        <span className={styles.badge} data-state={l.state}>
                          {stateName(l.state)}
                        </span>
                      </div>
                      <p className={styles.subtle}>
                        {worlds.find((w) => w.id === l.theme)?.[language === 'cs' ? 'cs' : 'en'] ?? l.theme} ·{' '}
                        {Math.round((l.content ?? templates[l.theme])?.levelEnd ?? 0).toLocaleString(language)}{' '}
                        {t('herních jednotek', 'game units')}
                      </p>
                      <div className={styles.cardFooter}>
                        <div className={styles.actions}>
                          {canManage && (
                            <>
                              <button
                                className={styles.small}
                                disabled={
                                  i === 0 ||
                                  busy ||
                                  (!canPublish && (l.state === 'LIVE' || levels[i - 1]?.state === 'LIVE'))
                                }
                                onClick={() => move(l.id, -1)}
                                aria-label={`${t('Posunout výš', 'Move up')} ${title(l)}`}
                              >
                                <GripVertical size={14} /> ↑
                              </button>
                              <button
                                className={styles.small}
                                disabled={
                                  i === levels.length - 1 ||
                                  busy ||
                                  (!canPublish && (l.state === 'LIVE' || levels[i + 1]?.state === 'LIVE'))
                                }
                                onClick={() => move(l.id, 1)}
                                aria-label={`${t('Posunout níž', 'Move down')} ${title(l)}`}
                              >
                                ↓
                              </button>
                              <button
                                className={styles.small}
                                disabled={busy || !templates[l.theme]}
                                onClick={() => add(l.theme, l)}
                                aria-label={`${t('Duplikovat', 'Duplicate')} ${title(l)}`}
                              >
                                <Copy size={14} />
                              </button>
                            </>
                          )}
                        </div>
                        <button className={styles.small} onClick={() => open(l.id)}>
                          {t('Otevřít kolo', 'Open chapter')}
                          <ArrowRight size={14} />
                        </button>
                      </div>
                    </div>
                  </article>
                ))}
              {canManage && catalog && levels.length < 30 && (
                <div className={styles.newCard}>
                  <Plus size={30} />
                  <h2>{t('Nové dobrodružství', 'New adventure')}</h2>
                  <p className={styles.subtle}>{t('Začněte výběrem prostředí', 'Start by choosing a world')}</p>
                  {worlds.map((w) => (
                    <button
                      className={styles.small}
                      key={w.id}
                      disabled={busy || !templates[w.id]}
                      onClick={() => add(w.id)}
                    >
                      {language === 'cs' ? w.cs : w.en} <Plus size={13} />
                    </button>
                  ))}
                </div>
              )}
            </div>
          </>
        ) : (
          <>
            <div className={styles.toolbar}>
              <button className={styles.button} onClick={() => setSelected(null)}>
                <ArrowLeft size={15} />
                {t('Všechna kola', 'All chapters')}
              </button>
              <span className={styles.subtle}>
                {t('KOLO', 'CHAPTER')} {level.order.toString().padStart(2, '0')} / {title(level)}
              </span>
              <span className={styles.badge} data-state={level.state}>
                {stateName(level.state)}
              </span>
            </div>
            {!canPublish && (
              <p className={styles.notice}>
                {t(
                  'Marketing připravuje rozpracovaná a testovací kola. Publikaci a změny již publikovaných kol provádí administrátor.',
                  'Marketing prepares drafts and testing chapters. An administrator publishes chapters and changes published content.',
                )}
              </p>
            )}
            <div className={styles.editor}>
              <div className={styles.mainColumn}>
                <div className={styles.previewPanel}>
                  <div className={`${styles.row} ${styles.previewTitle}`}>
                    <h2>
                      <Eye size={17} /> {t('Náhled scény', 'Scene preview')}
                    </h2>
                    <span className={styles.subtle}>{t('Skutečné herní podklady', 'Original game artwork')}</span>
                  </div>
                  <ScenePreview
                    theme={level.theme}
                    course={course}
                    camera={camera}
                    label={t('Umístit vybraný prvek do scény', 'Place selected component in scene')}
                    onPlace={canManage && tool && (canPublish || level.state !== 'LIVE') ? place : undefined}
                    onSelect={(key, index) => {
                      setItem({ key, index })
                      setTool(null)
                    }}
                  />
                  <div className={styles.timeline}>
                    <div className={styles.row}>
                      <strong>{t('Průběh kola', 'Chapter timeline')}</strong>
                      <span className={styles.subtle}>
                        {Math.round(camera)} — {Math.min(Math.round(camera + 1800), course?.levelEnd ?? 0)}
                      </span>
                    </div>
                    <div className={styles.track}>
                      {course?.coins.map((c, i) => (
                        <span key={i} style={{ left: `${(Number(c.x) / course.levelEnd) * 100}%` }} />
                      ))}
                    </div>
                    <label>
                      {t('Posun náhledu po trati', 'Move preview along the course')}
                      <input
                        type="range"
                        min={0}
                        max={Math.max(0, (course?.levelEnd ?? 1800) - 1800)}
                        step={10}
                        value={camera}
                        onChange={(e) => setCamera(Number(e.target.value))}
                      />
                    </label>
                  </div>
                </div>
                <div className={styles.panel}>
                  <div className={styles.row}>
                    <h2>{t('Knihovna herních prvků', 'Game component library')}</h2>
                    <span className={styles.subtle}>
                      {t('Vyberte prvek a klikněte do scény', 'Choose a component, then click the scene')}
                    </span>
                  </div>
                  <div className={styles.library}>
                    <button
                      className={styles.component}
                      disabled={!canManage || busy || !course || (!canPublish && level.state === 'LIVE')}
                      aria-pressed={tool === 'piggies'}
                      onClick={() => {
                        setTool(tool === 'piggies' ? null : 'piggies')
                        setItem(null)
                      }}
                    >
                      <img src={art('atm_f01')} alt="" />
                      {t('Vkladomat', 'Deposit kiosk')}
                    </button>
                    {components
                      .filter(
                        (c) =>
                          (!['thieves', 'pigeons', 'rooftopObjects', 'dropZones'].includes(c.key) ||
                            level.theme === 'rooftops') &&
                          (!['bridgePlatforms', 'bridgeTraps'].includes(c.key) || level.theme === 'charles_bridge'),
                      )
                      .map((c) => (
                        <button
                          key={c.key}
                          className={styles.component}
                          disabled={!canManage || busy || !course || (!canPublish && level.state === 'LIVE')}
                          aria-pressed={tool === c.key}
                          onClick={() => {
                            setTool(tool === c.key ? null : c.key)
                            setItem(null)
                          }}
                        >
                          <img src={art(c.asset)} alt="" />
                          {language === 'cs' ? c.cs : c.en}
                        </button>
                      ))}
                  </div>
                </div>
                {course && (
                  <fieldset
                    className={styles.panel}
                    disabled={!canManage || busy || (!canPublish && level.state === 'LIVE')}
                  >
                    <legend>{t('Terén a bezpečné dopady', 'Terrain and safe landings')}</legend>
                    <p className={styles.subtle}>
                      {t(
                        'Úseky pevné země oddělují mezery. Cíl musí stát na pevné zemi.',
                        'Gaps separate solid ground sections. The finish must stand on solid ground.',
                      )}
                    </p>
                    <div className={styles.fields}>
                      {course.ground.map((g, index) => (
                        <div key={index} className={styles.fields}>
                          <label>
                            {t('Úsek', 'Section')} {index + 1} · {t('Od', 'From')}
                            <input
                              type="number"
                              value={Number(g.x0)}
                              onChange={(e) =>
                                changeCourse({
                                  ground: course.ground.map((v, i) =>
                                    i === index ? { ...v, x0: Number(e.target.value) } : v,
                                  ),
                                })
                              }
                            />
                          </label>
                          <label>
                            {t('Do', 'To')}
                            <input
                              type="number"
                              value={Number(g.x1)}
                              onChange={(e) =>
                                changeCourse({
                                  ground: course.ground.map((v, i) =>
                                    i === index ? { ...v, x1: Number(e.target.value) } : v,
                                  ),
                                })
                              }
                            />
                          </label>
                        </div>
                      ))}
                    </div>
                    <label>
                      {t('Umístění cíle', 'Finish location')}
                      <input
                        type="number"
                        value={course.vaultX}
                        onChange={(e) => changeCourse({ vaultX: Number(e.target.value) })}
                      />
                    </label>
                  </fieldset>
                )}
                {selectedItem && item && (
                  <fieldset
                    disabled={!canManage || busy || (!canPublish && level.state === 'LIVE')}
                    className={styles.panel}
                  >
                    <legend>
                      {t('Vybraný prvek', 'Selected component')} · {item.index + 1}
                    </legend>
                    <div className={styles.fields}>
                      {Object.entries(selectedItem).map(([key, value]) => (
                        <label key={key}>
                          {fieldName(key)}
                          {key === 'kind' ? (
                            <select
                              value={String(value)}
                              onChange={(e) => {
                                if (!course) return
                                changeCourse({
                                  [item.key]: (course[item.key as keyof Course] as Item[]).map((v, i) =>
                                    i === item.index ? { ...v, kind: e.target.value } : v,
                                  ),
                                })
                              }}
                            >
                              {[
                                ['CHIMNEY', t('Komín', 'Chimney')],
                                ['BALCONY', t('Balkón', 'Balcony')],
                                ['LAUNDRY', t('Prádlo', 'Laundry')],
                                ['CRUMBLE', t('Propadající se tašky', 'Crumbling tiles')],
                                ['DORMER', t('Vikýř', 'Dormer')],
                              ].map(([v, label]) => (
                                <option key={v} value={v}>
                                  {label}
                                </option>
                              ))}
                            </select>
                          ) : (
                            <input
                              type={
                                typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'checkbox' : 'text'
                              }
                              value={typeof value === 'boolean' ? undefined : value}
                              checked={typeof value === 'boolean' ? value : undefined}
                              onChange={(e) => {
                                if (!course) return
                                if (item.key === 'piggies') {
                                  changeCourse({
                                    piggies: course.piggies.map((v, i) =>
                                      i === item.index ? Number(e.target.value) : v,
                                    ),
                                  })
                                  return
                                }
                                const list = course[item.key as keyof Course] as Item[]
                                changeCourse({
                                  [item.key]: list.map((v, i) =>
                                    i === item.index
                                      ? {
                                          ...v,
                                          [key]:
                                            typeof value === 'number'
                                              ? Number(e.target.value)
                                              : typeof value === 'boolean'
                                                ? e.target.checked
                                                : e.target.value,
                                        }
                                      : v,
                                  ),
                                })
                              }}
                            />
                          )}
                        </label>
                      ))}
                    </div>
                    <button
                      className={styles.small}
                      onClick={() => {
                        if (course)
                          changeCourse({
                            [item.key]: (course[item.key as keyof Course] as Item[]).filter((_, i) => i !== item.index),
                          })
                        setItem(null)
                      }}
                    >
                      <Trash2 size={14} />
                      {t('Odebrat prvek', 'Remove component')}
                    </button>
                  </fieldset>
                )}
                {problems.length > 0 && (
                  <p className={styles.notice} role="alert">
                    {t('Trať potřebuje úpravu před uložením:', 'This course needs changes before saving:')}{' '}
                    {problems
                      .map(
                        (p) =>
                          ({
                            length: t('délka trati', 'course length'),
                            finish: t('cíl na pevné zemi', 'finish on solid ground'),
                            ground: t('úseky terénu', 'ground sections'),
                            gap: t('šířka mezer', 'gap widths'),
                            coins: t('alespoň jedna pravá mince', 'at least one real coin'),
                            count: t('příliš mnoho prvků', 'too many components'),
                            coordinate: t('umístění prvků', 'component positions'),
                          })[p] ?? p,
                      )
                      .join(', ')}
                  </p>
                )}
              </div>
              <aside className={styles.mainColumn}>
                <fieldset
                  className={styles.panel}
                  disabled={!canManage || busy || (!canPublish && level.state === 'LIVE')}
                >
                  <legend>{t('Nastavení kola', 'Chapter settings')}</legend>
                  <label>
                    {t('Český název', 'Czech title')}
                    <input
                      maxLength={80}
                      required
                      value={level.titleCs}
                      onChange={(e) => change({ titleCs: e.target.value })}
                    />
                  </label>
                  <label>
                    {t('Anglický název', 'English title')}
                    <input
                      maxLength={80}
                      required
                      value={level.titleEn}
                      onChange={(e) => change({ titleEn: e.target.value })}
                    />
                  </label>
                  <label>
                    {t('Dostupnost', 'Availability')}
                    <select value={level.state} onChange={(e) => change({ state: e.target.value as Level['state'] })}>
                      <option value="OFF">{stateName('OFF')}</option>
                      <option value="BETA">{stateName('BETA')}</option>
                      <option value="LIVE" disabled={!canPublish}>
                        {stateName('LIVE')}
                      </option>
                    </select>
                  </label>
                  <label>
                    {t('Délka trati (herní jednotky)', 'Course length (game units)')}
                    <input
                      type="number"
                      min={1000}
                      max={50000}
                      step={100}
                      value={course?.levelEnd ?? ''}
                      disabled={!course}
                      onChange={(e) => {
                        if (!course) return
                        const end = Number(e.target.value)
                        const ground = course.ground.map((g, i) =>
                          i === course.ground.length - 1 ? { ...g, x1: end } : g,
                        )
                        changeCourse({ levelEnd: end, vaultX: end - 300, ground })
                        setCamera(Math.min(camera, Math.max(0, end - 1800)))
                      }}
                    />
                  </label>
                  <label>
                    {t('Odemčení', 'Unlock')}
                    <select
                      value={level.requirement.kind}
                      onChange={(e) => {
                        const kind = e.target.value as Level['requirement']['kind']
                        change({
                          requirement: {
                            ...level.requirement,
                            kind,
                            source: kind === 'EARN_SOURCE' ? 'EDUCATIONAL_CONTENT_COMPLETION' : null,
                          },
                        })
                      }}
                    >
                      <option value="NONE">{t('Bez podmínky', 'No requirement')}</option>
                      <option value="PREVIOUS_LEVEL">
                        {t('Dokončení předchozího kola', 'Complete previous chapter')}
                      </option>
                      <option value="EARN_SOURCE">{t('Dokončené vzdělávání', 'Completed education')}</option>
                    </select>
                  </label>
                  {level.requirement.kind === 'EARN_SOURCE' && (
                    <label>
                      {t('Počet dokončení', 'Completion count')}
                      <input
                        type="number"
                        min={1}
                        max={1000}
                        value={level.requirement.count}
                        onChange={(e) =>
                          change({ requirement: { ...level.requirement, count: Number(e.target.value) } })
                        }
                      />
                    </label>
                  )}
                  <label>
                    {t('Začátek (UTC)', 'Starts (UTC)')}
                    <input
                      type="datetime-local"
                      value={level.startsAt?.slice(0, 16) ?? ''}
                      onChange={(e) => change({ startsAt: e.target.value ? `${e.target.value}:00Z` : null })}
                    />
                  </label>
                  <label>
                    {t('Konec (UTC)', 'Ends (UTC)')}
                    <input
                      type="datetime-local"
                      value={level.endsAt?.slice(0, 16) ?? ''}
                      onChange={(e) => change({ endsAt: e.target.value ? `${e.target.value}:00Z` : null })}
                    />
                  </label>
                </fieldset>
                <div className={styles.panel}>
                  <h3>{t('Před publikováním', 'Before publishing')}</h3>
                  <p className={styles.subtle}>
                    {t(
                      'Upravené a nové tratě vyžadují mobilní aplikaci s podporou editoru. Starší aplikace zachová původní kola.',
                      'Edited and new courses require an app with editor support. Older apps retain the original chapters.',
                    )}
                  </p>
                  <p className={styles.subtle}>
                    {t(
                      'Změny nejprve uložte ve stavu Testování a projděte kolo v nové testovací verzi mobilky. Publikováno zpřístupní kolo hráčům při obnovení mapy.',
                      'Save changes as Testing first and play the chapter in the new testing app. Published makes the chapter available when players refresh the map.',
                    )}
                  </p>
                  <p className={styles.subtle}>
                    {t(
                      'Náhled ukazuje rozmístění prvků. Pohyb a fyziku ověřte ve hře.',
                      'Preview shows component placement. Verify motion and physics in the game.',
                    )}
                  </p>
                </div>
              </aside>
            </div>
          </>
        )}
        <div className={styles.toolbar} style={{ marginTop: 24 }}>
          <span className={styles.subtle}>
            {catalog
              ? `${t('Verze katalogu', 'Catalogue revision')} ${catalog.revision}`
              : busy
                ? t('Načítám kola…', 'Loading chapters…')
                : t('Katalog není dostupný', 'Catalogue unavailable')}
          </span>
          <button
            className={styles.small}
            disabled={busy}
            onClick={() => {
              if (
                !dirty ||
                window.confirm(
                  t('Zahodit neuložené úpravy a načíst aktuální katalog?', 'Discard unsaved edits and reload?'),
                )
              )
                void load()
            }}
          >
            <RefreshCw size={14} />
            {t('Načíst aktuální verzi', 'Reload current version')}
          </button>
        </div>
      </section>
    </AuthGuard>
  )
}
