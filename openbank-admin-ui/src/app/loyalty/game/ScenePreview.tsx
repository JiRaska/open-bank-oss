// SPDX-License-Identifier: Apache-2.0
'use client'
/* eslint-disable @next/next/no-img-element -- Original transparent game layers use native image sizing. */
import { art, components, type Course, worlds } from '@/lib/game/editor'
import styles from './page.module.css'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Props = {
  course?: Course | null
  theme: string
  camera?: number
  compact?: boolean
  label: string
  onPlace?: (x: number, y: number) => void
  onSelect?: (key: string, index: number) => void
}
export function ScenePreview({ course, theme, camera = 0, compact = false, label, onPlace, onSelect }: Props) {
  const { language, t } = useLanguage()
  const world = worlds.find((w) => w.id === theme)
  if (!world)
    return (
      <div className={`${styles.scene} ${compact ? styles.compact : ''}`} role="group" aria-label={label}>
        <span className={styles.sceneCaption}>
          {t('Náhled prostředí zatím není k dispozici', 'World preview is not available yet')}
        </span>
      </div>
    )
  const span = compact ? 1600 : 1800
  const visible = (x: number) => x >= camera - 160 && x <= camera + span + 160
  const stoneSections = course?.ground ?? [{ x0: camera, x1: camera + span }]
  const statues = stoneSections.flatMap((g) => {
    const positions: number[] = []
    for (let x = Math.max(250, Number(g.x0) + 230); x < Number(g.x1) - 120 && x < 11650; x += 550) positions.push(x)
    return positions
  })

  return (
    <div className={`${styles.scene} ${compact ? styles.compact : ''}`} role="group" aria-label={label}>
      <img className={styles.sky} src={art(world.sky)} alt="" />
      {theme === 'charles_bridge' ? (
        <>
          {statues.filter(visible).map((x, i) => (
            <span
              key={x}
              className={styles.statue}
              style={{
                left: `${((x - camera) / span) * 100}%`,
                backgroundImage: `url(${art('bridge_statues')})`,
                backgroundPosition: i % 2 ? 'right top' : 'left top',
              }}
            />
          ))}
          {stoneSections.map((g, i) => (
            <div
              key={`parapet-${i}`}
              className={styles.parapet}
              style={{
                left: `${((Number(g.x0) - camera) / span) * 100}%`,
                width: `${((Number(g.x1) - Number(g.x0)) / span) * 100}%`,
                backgroundImage: `url(${art('bridge_parapet')})`,
              }}
            />
          ))}
        </>
      ) : (
        <>
          <img className={styles.far} src={art(world.far)} alt="" />
          <img className={styles.middle} src={art(world.mid)} alt="" />
        </>
      )}
      {course ? (
        course.ground.map((g, i) => (
          <div
            key={i}
            className={styles.ground}
            style={{
              left: `${((Number(g.x0) - camera) / span) * 100}%`,
              width: `${((Number(g.x1) - Number(g.x0)) / span) * 100}%`,
              backgroundImage:
                theme === 'rooftops'
                  ? 'repeating-linear-gradient(90deg,transparent 0 13px,#963d2b55 13px 15px),linear-gradient(#c75c3e 0 14px,#c8b29b 14px 100%)'
                  : `url(${art(world.floor)})`,
            }}
          />
        ))
      ) : (
        <div
          className={styles.ground}
          style={{
            left: 0,
            width: '100%',
            backgroundImage:
              theme === 'rooftops'
                ? 'repeating-linear-gradient(90deg,transparent 0 13px,#963d2b55 13px 15px),linear-gradient(#c75c3e 0 14px,#c8b29b 14px 100%)'
                : `url(${art(world.floor)})`,
          }}
        />
      )}
      {course &&
        components.flatMap((component) =>
          ((course[component.key] ?? []) as Record<string, number | string | boolean>[]).map((item, index) => {
            const x = Number(item.x ?? item.cx),
              y = Number(item.y ?? item.cy ?? 0)
            if (!visible(x)) return null
            const SpriteTag = compact ? 'span' : 'button'
            const tiledRoof = item.kind === 'CRUMBLE'
            return (
              <SpriteTag
                key={`${component.key}-${index}`}
                type="button"
                tabIndex={compact ? undefined : 0}
                aria-label={`${language === 'cs' ? component.cs : component.en} ${index + 1}`}
                className={`${styles.sprite} ${component.key === 'coins' ? styles.coin : ''}`}
                style={{
                  left: `${((x - camera) / span) * 100}%`,
                  bottom: `${22 + (y / 700) * 72}%`,
                  width: tiledRoof ? `${(Number(item.w) / span) * 100}%` : undefined,
                }}
                onClick={(e) => {
                  e.stopPropagation()
                  onSelect?.(component.key, index)
                }}
              >
                {tiledRoof ? (
                  <span className={styles.crumbleTile} />
                ) : (
                  <img
                    src={art(
                      item.boss
                        ? theme === 'rooftops'
                          ? 'l02_pickpocket'
                          : 'boss_f01'
                        : item.kind === 'BALCONY'
                          ? 'l02_balcony'
                          : item.kind === 'DORMER'
                            ? 'l02_dormer'
                            : item.kind === 'LAUNDRY'
                              ? 'l02_laundry'
                              : component.asset,
                    )}
                    alt=""
                  />
                )}
              </SpriteTag>
            )
          }),
        )}
      {course &&
        course.platforms
          .filter((p) => visible(Number(p.x)))
          .map((p, i) => (
            <div
              key={`platform-${i}`}
              className={styles.platform}
              style={{
                left: `${((Number(p.x) - camera) / span) * 100}%`,
                bottom: `${22 + (Number(p.y) / 700) * 72}%`,
                width: `${(Number(p.w) / span) * 100}%`,
              }}
            />
          ))}
      {course?.slopes?.map((slope, index) => (
        <div
          key={`slope-${index}`}
          className={styles.slope}
          style={{
            left: `${((Number(slope.x0) - camera) / span) * 100}%`,
            bottom: `${22 + (Number(slope.y0) / 700) * 72}%`,
            width: `${((Number(slope.x1) - Number(slope.x0)) / span) * 100}%`,
            transform: `rotate(${(-Math.atan2(Number(slope.y1) - Number(slope.y0), Number(slope.x1) - Number(slope.x0)) * 180) / Math.PI}deg)`,
          }}
        />
      ))}
      {course?.piggies.filter(visible).map((x, i) => (
        <img
          key={`deposit-${i}`}
          className={styles.deposit}
          style={{ left: `${((x - camera) / span) * 100}%` }}
          src={art('atm_f01')}
          alt=""
        />
      ))}
      {course && visible(course.vaultX) && (
        <img
          className={styles.finish}
          style={{ left: `${((course.vaultX - camera) / span) * 100}%` }}
          src={art('portal_open')}
          alt=""
        />
      )}
      {course?.paperworkBossX != null && visible(course.paperworkBossX) && (
        <img
          className={styles.bridgeBoss}
          style={{ left: `${((course.paperworkBossX - camera) / span) * 100}%` }}
          src={art('bridge_paperwork')}
          alt=""
        />
      )}
      {!compact && <img className={styles.hero} src={art('lion_f01')} alt="" />}
      {onPlace && (
        <button
          className={styles.placement}
          type="button"
          aria-label={label}
          onClick={(e) => {
            if (e.detail === 0) {
              onPlace(camera + span / 2, 100)
              return
            }
            const box = e.currentTarget.getBoundingClientRect()
            onPlace(
              Math.round(camera + ((e.clientX - box.left) / box.width) * span),
              Math.max(0, Math.round((((box.bottom - e.clientY) / box.height - 0.22) / 0.72) * 700)),
            )
          }}
        />
      )}
      <span className={styles.sceneCaption}>{language === 'cs' ? world.cs : world.en}</span>
    </div>
  )
}
