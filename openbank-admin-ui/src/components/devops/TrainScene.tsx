// SPDX-License-Identifier: Apache-2.0
'use client'

import { useId } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { TrainLane } from './sdlc-content'
import styles from './SdlcGuide.module.css'

function Passenger({ x, lioness, mood }: { x: number; lioness: boolean; mood: number }) {
  return <svg className={styles.passenger} data-mood={mood} x={x} y="523" width="102" height="102" viewBox="0 0 512 512" overflow="hidden" aria-hidden="true">
    <image href="/brand/sdlc/train-passengers.png" x={-mood * 512} y={lioness ? -512 : 0} width="1536" height="1024" />
  </svg>
}

/** Paper props stay inside the cabin; passengers only appear once they board. */
function JourneyPaper({ x, checking, failed, label }: { x: number; checking: boolean; failed: boolean; label: string }) {
  return <g transform={`translate(${x} 536)`} aria-hidden="true">
    <rect x="3" y="4" width="133" height="58" rx="3" fill="#000" opacity=".3" />
    <rect width="133" height="58" rx="3" fill="#e6d7b9" stroke="#b79862" />
    <path d="M99 3v52" stroke="#9e835b" strokeDasharray="2 3" />
    <text x="10" y="17" fill="#463824" fontSize="10" fontWeight="600" letterSpacing="1">{label}</text>
    {checking ? <>
      <text x="10" y="32" fill="#635239" fontSize="8" letterSpacing="1">CI / REVIEW</text>
      <path d="M10 42h47M10 47h33" stroke="#a18a64" />
      <circle cx="77" cy="36" r="10" fill="none" stroke={failed ? '#9d3c36' : '#39654c'} strokeWidth="1.5" />
      <path d={failed ? 'M73 32l8 8m0-8l-8 8' : 'M72 36l3 3 7-7'} fill="none" stroke={failed ? '#9d3c36' : '#39654c'} strokeWidth="1.5" />
      <text x="108" y="36" fill="#635239" fontSize="9">PR</text>
    </> : <>
      <path d="M10 27h50M10 33h38M10 39h44M10 45h26" stroke="#a18a64" />
      <path d="M71 42l14-19 3 3-14 19-5 3z" fill="#6c5431" />
      <text x="108" y="36" fill="#635239" fontSize="9">01</text>
    </>}
  </g>
}

/** Cabin contents are recessed behind the real coach frame, sill and glazing. */
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
        `Modelový příklad. ${cars === 3 ? 'Tři návrhy změn' : 'Jeden návrh změny'}. ${failed ? 'Neúspěšná kontrola zastavila cestu.' : step === 4 ? 'Lvíčci oslavují úspěšné sloučení.' : onboard ? 'Lvíčci nastoupili do vagónů.' : step === 1 ? 'Kontrola jízdenek představuje kontroly PR před nástupem.' : 'Návrh změny čeká na ověření před nástupem.'}`,
        `Illustrative example. ${cars === 3 ? 'Three proposed changes' : 'One proposed change'}. ${failed ? 'A failed check has stopped the journey.' : step === 4 ? 'The lions celebrate a successful merge.' : onboard ? 'The lions have boarded their coaches.' : step === 1 ? 'Ticket inspection represents PR checks before boarding.' : 'A proposed change awaits verification before boarding.'}`,
      )}</desc>
      <defs>
        <linearGradient id={`${id}-shade`} x2="0" y2="1"><stop stopColor="#06101f" stopOpacity=".4" /><stop offset=".45" stopColor="#06101f" stopOpacity="0" /><stop offset="1" stopColor="#06101f" stopOpacity=".16" /></linearGradient>
        <linearGradient id={`${id}-glass`} x2="1" y2="1"><stop stopColor="#b8d4ec" stopOpacity=".1" /><stop offset=".4" stopColor="#b8d4ec" stopOpacity="0" /><stop offset="1" stopColor="#06101f" stopOpacity=".18" /></linearGradient>
        <filter id={`${id}-glow`} x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="5" /></filter>
        {Array.from({ length: cars }, (_, i) => {
          const x = start + i * 300
          // Measured from heritage-coach.webp (1448 × 1086), inset from the brass opening.
          return <g key={i}>
            <clipPath id={`${id}-window-${i}`}><rect x={x + 59} y="522" width="183" height="78" rx="10" /></clipPath>
            <mask id={`${id}-frame-${i}`} maskUnits="userSpaceOnUse" x={x} y="458" width="300" height="225">
              <rect x={x} y="458" width="300" height="225" fill="white" />
              <rect x={x + 59} y="522" width="183" height="78" rx="10" fill="black" />
            </mask>
          </g>
        })}
      </defs>
      <image href="/brand/sdlc/prague-station-v2.webp" x="0" y="-150" width="1536" height="1024" />
      <rect width="1536" height="768" fill={`url(#${id}-shade)`} />
      <g aria-hidden="true" className={styles.sceneMasthead}>
        <image href="/brand/sdlc/openbank-mark.svg" x="62" y="34" width="58" height="38" />
        <text x="138" y="59" fill="#e7f6ff" fontSize="20" letterSpacing="5">OpenBank / CI EXPRESS</text>
        <text x="64" y="105" fill="#c3d8eb" fontSize="17">{t('Praha · nástupiště změn', 'Prague · change platform')}</text>
      </g>
      <g className={styles.trainPosition} style={{ transform: `translateX(${onboard ? step * 10 : 0}px)` }}>
        <g className={styles.trainBounce}>
          {Array.from({ length: cars }, (_, i) => {
            const x = start + i * 300
            return <g key={i}>
              <image href="/brand/sdlc/heritage-coach.webp" x={x} y="458" width="300" height="225" aria-hidden="true" />
              <g clipPath={`url(#${id}-window-${i})`} data-cabin={i} aria-hidden="true">
                <rect x={x + 81} y="568" width="138" height="37" rx="9" fill="#352a23" stroke="#6d5032" />
                <path d={`M${x + 88} 576h124M${x + 150} 571v29`} stroke="#997149" opacity=".35" />
                {onboard
                  ? <Passenger x={x + 99} lioness={i % 2 === 1} mood={mood} />
                  : <JourneyPaper x={x + 84} checking={step === 1 || failed} failed={failed} label={step === 1 || failed ? `PR ${String.fromCharCode(65 + i)}` : t('NÁVRH ZMĚNY', 'CHANGE PROPOSAL')} />}
                <rect x={x + 59} y="522" width="183" height="78" fill={`url(#${id}-glass)`} />
                <path d={`M${x + 66} 527h168`} stroke="#ffd59a" opacity=".16" />
              </g>
              <image href="/brand/sdlc/heritage-coach.webp" x={x} y="458" width="300" height="225" mask={`url(#${id}-frame-${i})`} aria-hidden="true" />
              <image href="/brand/sdlc/openbank-mark.svg" x={x + 136} y="610" width="28" height="18" aria-hidden="true" />
              <text x={x + 150} y="443" textAnchor="middle" fill="#e8f8ff" fontSize="20" fontWeight="600" letterSpacing="3">PR {String.fromCharCode(65 + i)}</text>
            </g>
          })}
          <svg x={engineX} y="495" width="600" height="200" viewBox="0 0 1536 512" overflow="hidden" aria-hidden="true"><image href="/brand/sdlc/train-atlas-v3.webp" width="1536" height="1024" /></svg>
          <image href="/brand/sdlc/openbank-mark.svg" x={engineX + 91} y="603" width="32" height="21" aria-hidden="true" />
          <ellipse cx={engineX + 557} cy="605" rx="12" ry="5" fill="#ffd99c" opacity=".35" filter={`url(#${id}-glow)`} aria-hidden="true" />
        </g>
      </g>
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
