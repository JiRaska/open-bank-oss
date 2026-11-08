// SPDX-License-Identifier: Apache-2.0
'use client'
import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Course, Item } from '@/lib/game/editor'
import styles from './page.module.css'
const groups = [
  { id: 'coins', label: { cs: 'Mince', en: 'Coins' } },
  { id: 'vampires', label: { cs: 'Pozemní nepřátelé a bossové', en: 'Ground enemies and bosses' } },
  { id: 'bats', label: { cs: 'Létající nepřátelé', en: 'Flying enemies' } },
  { id: 'piggies', label: { cs: 'Vkladomaty', en: 'Deposit kiosks' } },
  { id: 'shields', label: { cs: 'Ochranné štíty', en: 'Shields' } },
  { id: 'platforms', label: { cs: 'Plošiny', en: 'Platforms' } },
  { id: 'ground', label: { cs: 'Pevná země', en: 'Solid ground' } },
  { id: 'slopes', label: { cs: 'Šikmé střechy', en: 'Roof slopes' } },
  { id: 'thieves', label: { cs: 'Zloději', en: 'Thieves' } },
  { id: 'dropZones', label: { cs: 'Padající překážky', en: 'Falling obstacles' } },
  { id: 'rooftopObjects', label: { cs: 'Střešní objekty', en: 'Rooftop objects' } },
  { id: 'pigeons', label: { cs: 'Holubi', en: 'Pigeons' } },
  { id: 'bridgePlatforms', label: { cs: 'Mostní plošiny', en: 'Bridge platforms' } },
  { id: 'bridgeTraps', label: { cs: 'Pasti u soch', en: 'Statue traps' } },
] as const
export function CourseContents({
  course,
  onSelect,
}: {
  course: Course
  onSelect: (key: string, index: number) => void
}) {
  const { t, language } = useLanguage()
  const [group, setGroup] = useState('coins')
  const [limit, setLimit] = useState(25)
  const entries = (course[group as keyof Course] ?? []) as (Item | number)[]
  const caption = (g: (typeof groups)[number]) => (language === 'cs' ? g.label.cs : g.label.en)
  return (
    <section className={styles.panel} aria-labelledby="course-contents-title">
      <h2 id="course-contents-title">{t('Obsah tohoto kola', 'Contents of this chapter')}</h2>
      <p className={styles.subtle}>
        {t(
          'Vyberte konkrétní prvek. Náhled se přesune na jeho místo a otevře se jeho nastavení.',
          'Choose an existing component to move the preview to its position and open its settings.',
        )}
      </p>
      <div className={styles.actions}>
        {groups.map((g) => (
          <button
            className={styles.small}
            key={g.id}
            aria-pressed={group === g.id}
            onClick={() => {
              setGroup(g.id)
              setLimit(25)
            }}
          >
            {caption(g)} · {((course[g.id] ?? []) as unknown[]).length}
          </button>
        ))}
      </div>
      <div className={styles.contentItems}>
        {entries.slice(0, limit).map((entry, index) => {
          const x = typeof entry === 'number' ? entry : Number(entry.x ?? entry.cx ?? entry.x0 ?? 0)
          return (
            <button className={styles.small} key={index} onClick={() => onSelect(group, index)}>
              {t('Prvek', 'Component')} {index + 1} · {t('vzdálenost', 'distance')} {Math.round(x)}
            </button>
          )
        })}
      </div>
      {entries.length === 0 && (
        <p className={styles.subtle}>
          {t('V této skupině zatím nejsou žádné prvky.', 'This group has no components yet.')}
        </p>
      )}
      {entries.length > limit && (
        <button className={styles.small} onClick={() => setLimit((n) => n + 25)}>
          {t('Zobrazit další', 'Show more')}
        </button>
      )}
    </section>
  )
}
