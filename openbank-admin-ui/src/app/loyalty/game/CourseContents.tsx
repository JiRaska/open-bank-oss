// SPDX-License-Identifier: Apache-2.0
'use client'
import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Course, Item } from '@/lib/game/editor'
import styles from './page.module.css'
const groups = [
  ['coins', 'Mince', 'Coins'],
  ['vampires', 'Pozemní nepřátelé a bossové', 'Ground enemies and bosses'],
  ['bats', 'Létající nepřátelé', 'Flying enemies'],
  ['piggies', 'Vkladomaty', 'Deposit kiosks'],
  ['shields', 'Ochranné štíty', 'Shields'],
  ['platforms', 'Plošiny', 'Platforms'],
  ['ground', 'Pevná země', 'Solid ground'],
  ['slopes', 'Šikmé střechy', 'Roof slopes'],
  ['thieves', 'Zloději', 'Thieves'],
  ['dropZones', 'Padající překážky', 'Falling obstacles'],
  ['rooftopObjects', 'Střešní objekty', 'Rooftop objects'],
  ['pigeons', 'Holubi', 'Pigeons'],
  ['bridgePlatforms', 'Mostní plošiny', 'Bridge platforms'],
  ['bridgeTraps', 'Pasti u soch', 'Statue traps'],
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
  const caption = (g: (typeof groups)[number]) => (language === 'cs' ? g[1] : g[2])
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
            key={g[0]}
            aria-pressed={group === g[0]}
            onClick={() => {
              setGroup(g[0])
              setLimit(25)
            }}
          >
            {caption(g)} · {((course[g[0]] ?? []) as unknown[]).length}
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
