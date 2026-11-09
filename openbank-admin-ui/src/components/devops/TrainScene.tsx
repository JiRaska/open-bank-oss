// SPDX-License-Identifier: Apache-2.0
'use client'

import { useId } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { TrainLane } from './sdlc-content'
import styles from './SdlcGuide.module.css'

function Passenger({ x, y, size, lioness, mood }: { x: number; y: number; size: number; lioness: boolean; mood: number }) {
  return <svg className={styles.passenger} data-mood={mood} x={x} y={y} width={size} height={size} viewBox="0 0 512 512" overflow="hidden" aria-hidden="true">
    <image href="/brand/sdlc/train-passengers.png" x={-mood * 512} y={lioness ? -512 : 0} width="1536" height="1024" />
  </svg>
}

/** Artwork supplies materials and lighting; the journey still owns composition and state. */
export function TrainScene({ lane, step, playing, failed }: { lane: TrainLane; step: number; playing: boolean; failed: boolean }) {
  const { t } = useLanguage()
  const id = useId().replaceAll(':', '')
  const cars = lane === 'batch' ? 3 : 1
  const start = cars === 3 ? 12 : 430
  const onboard = step >= 2
  const mood = failed || step === 3 ? 1 : step === 4 ? 2 : 0
  const moving = playing && onboard && step < 4 && !failed
  const engineX = start + cars * 300
  return <div className={styles.scene} data-moving={moving} data-playing={playing} data-failed={failed}>
    <svg viewBox="0 0 1536 768" className={styles.sceneSvg} role="img" aria-labelledby={`${id}-title`} aria-describedby={`${id}-desc`}>
      <title id={`${id}-title`}>{t('Lví vláček změn', 'Lion change train')}</title>
      <desc id={`${id}-desc`}>{t(
        `Modelový příklad. ${cars === 3 ? 'Tři návrhy změn' : 'Jeden návrh změny'}. ${failed ? 'Lvíčci čekají na vyřešení neúspěšné kontroly.' : step === 4 ? 'Lvíčci oslavují úspěšné sloučení.' : onboard ? 'Lvíčci nastoupili do vagónů.' : 'Lvíčci čekají na nástupišti.'}`,
        `Illustrative example. ${cars === 3 ? 'Three proposed changes' : 'One proposed change'}. ${failed ? 'The lions wait for a failed check to be resolved.' : step === 4 ? 'The lions celebrate a successful merge.' : onboard ? 'The lions have boarded their coaches.' : 'The lions wait on the platform.'}`,
      )}</desc>
      <defs>
        <linearGradient id={`${id}-shade`} x2="0" y2="1"><stop stopColor="#06101f" stopOpacity=".4" /><stop offset=".45" stopColor="#06101f" stopOpacity="0" /><stop offset="1" stopColor="#06101f" stopOpacity=".16" /></linearGradient>
        <filter id={`${id}-glow`} x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="5" /></filter>
        {Array.from({ length: cars }, (_, i) => <clipPath key={i} id={`${id}-window-${i}`}><rect x={start + i * 300 + 49} y="501" width="202" height="111" rx="9" /></clipPath>)}
      </defs>
      <image href="/brand/sdlc/prague-station-v2.webp" x="0" y="-150" width="1536" height="1024" />
      <rect width="1536" height="768" fill={`url(#${id}-shade)`} />
      <g aria-hidden="true" className={styles.sceneMasthead}>
        <image href="/brand/sdlc/openbank-mark.svg" x="62" y="34" width="77" height="36" />
        <text x="154" y="59" fill="#e7f6ff" fontSize="20" letterSpacing="5">{t('OpenBank / CI EXPRESS', 'OpenBank / CI EXPRESS')}</text>
        <text x="64" y="105" fill="#c3d8eb" fontSize="17">{t('Praha · nástupiště změn', 'Prague · change platform')}</text>
      </g>
      <g className={styles.trainPosition} style={{ transform: `translateX(${onboard ? step * 10 : 0}px)` }}>
        <g className={styles.trainBounce}>
          {Array.from({ length: cars }, (_, i) => {
            const x = start + i * 300
            return <g key={i}>
              <image href="/brand/sdlc/heritage-coach.webp" x={x} y="458" width="300" height="225" aria-hidden="true" />
              {onboard && <g clipPath={`url(#${id}-window-${i})`}>
                <Passenger x={x + 65} y={500} size={174} lioness={i % 2 === 1} mood={mood} />
              </g>}
              <image href="/brand/sdlc/openbank-mark.svg" x={x + 126} y="622" width="48" height="23" aria-hidden="true" />
              <text x={x + 150} y="443" textAnchor="middle" fill="#e8f8ff" fontSize="20" fontWeight="600" letterSpacing="3">PR {String.fromCharCode(65 + i)}</text>
            </g>
          })}
          <svg x={engineX} y="495" width="600" height="200" viewBox="0 0 1536 512" overflow="hidden" aria-hidden="true"><image href="/brand/sdlc/train-atlas-v3.webp" width="1536" height="1024" /></svg>
          <image href="/brand/sdlc/openbank-mark.svg" x={engineX + 74} y="604" width="54" height="25" aria-hidden="true" />
          <ellipse cx={engineX + 557} cy="605" rx="12" ry="5" fill="#ffd99c" opacity=".35" filter={`url(#${id}-glow)`} aria-hidden="true" />
        </g>
      </g>
      {!onboard && <g className={styles.waitingPassengers} aria-hidden="true">
        {Array.from({ length: step === 0 ? 1 : cars }, (_, i) => <image key={i} href={i % 2 === 1 ? '/lion-quest/game_lioness_f01.webp' : '/lion-quest/game_lion_f01.webp'} x={start + 65 + i * 300} y="466" width="150" height="230" />)}
      </g>}
      <g transform="translate(1440 420)" aria-hidden="true">
        <rect width="34" height="104" rx="15" fill="#091525" stroke="#496178" strokeWidth="1" />
        <path d="M17 104v143" stroke="#193047" strokeWidth="5" />
        <circle cx="17" cy="26" r="8" fill={failed ? '#ff7171' : '#233347'} />
        <circle cx="17" cy="52" r="8" fill={!failed && step < 4 ? '#ffcb78' : '#233347'} />
        <circle cx="17" cy="78" r="8" fill={step === 4 && !failed ? '#66e2b0' : '#233347'} />
      </g>
      <g aria-hidden="true"><text x="64" y="747" fill="#b8cfe2" fontSize="15" letterSpacing="3">{t('ILUSTRAČNÍ JÍZDA', 'ILLUSTRATIVE JOURNEY')}</text></g>
    </svg>
  </div>
}
