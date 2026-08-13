// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * The campaign builder as a canvas: entry → step → step, clicked rather than typed.
 *
 * **On ADR-0221 D5.** That decision rejects "a drag-and-drop journey canvas", and the reasoning is
 * sound: a free-form 40-node graph is where campaign tools go to die. This is deliberately not that.
 * The flow is LINEAR and BOUNDED — the domain caps a journey at five steps (`Campaign.MAX_STEPS`) —
 * so there is no arbitrary edge to draw and nothing to arrange. You add a step, you edit it, you
 * remove it. It is the wizard's step list rendered as the thing it describes.
 *
 * The visual language is the one every workflow tool converges on, for reasons that are not
 * decoration: a node reads as a CARD (so it is obviously a thing you can act on), its type lives in
 * a coloured glyph block (so channel is legible before any text is read), the wait between steps
 * sits ON the connector rather than inside a node (because it describes the transition, not the
 * step), and the canvas carries a dot grid (which makes the alignment deliberate rather than
 * accidental). None of it is a graph library: a strict CSP and a 5-node ceiling make one pure cost.
 *
 * Deliberately still absent, because D5 and ADR-0176 D4 forbid them and the service enforces both:
 * no free-text body anywhere (only declared template variables), and no way to author a segment —
 * that is a pull request against the catalogue.
 */

export type EditorChannel = 'EMAIL' | 'PUSH' | 'BANNER'

export type EditorMobileDestination = 'HOME' | 'SAVINGS' | 'CARDS' | 'PAYMENTS' | 'PRODUCT_HUB'

export type EditorInAppSurface = 'HOME_BANNER' | 'HOME_CAROUSEL' | 'PRODUCT_FEED' | 'REWARDS_HUB'

export type EditorCondition = 'IF_PREVIOUS_CONFIRMED' | 'IF_PREVIOUS_NOT_CONFIRMED'

export interface EditorStep {
  template: string
  channel: EditorChannel
  variables: { [key: string]: string }
  delaySeconds: number
  /** Absent means the step always runs. Evaluated against the previous send's delivery status. */
  condition?: EditorCondition
  /** Alternative B-arm values in a campaign-wide content experiment. */
  variantBVariables?: { [key: string]: string }
  /** Try the catalogue's safe app-push counterpart only when email consent is absent. */
  fallbackToPush?: boolean
  /** Closed app context reached after a push tap; never an arbitrary URL. */
  mobileDestination?: EditorMobileDestination
  /** Closed in-app inventory for a BANNER step; absent remains the backwards-compatible home banner. */
  inAppSurface?: EditorInAppSurface
}

export const MAX_STEPS = 5

const NODE_W = 212
const NODE_H = 84
const GAP_X = 92
const ROW_Y = 96
const PAD = 28
const ICON = 40

/**
 * Channel identity, in one place.
 *
 * Colour comes from the semantic tokens (ADR-0208 D2) rather than a literal, so a theme change
 * carries the canvas with it. The glyph is drawn as a path rather than pulled from an icon set
 * because the whole canvas is one inline SVG — mixing in DOM icons would break at export.
 */
const CHANNEL: Record<EditorChannel, { tint: string; glyph: string }> = {
  // envelope
  EMAIL: {
    tint: 'var(--accent)',
    glyph: 'M3 5h18v14H3V5zm0 0l9 7 9-7',
  },
  // phone with a signal arc
  PUSH: {
    tint: 'var(--success)',
    glyph: 'M7 3h10v18H7V3zm4 15h2',
  },
  // A home-surface card: a banner is rendered in the signed-in app, not dispatched as a message.
  BANNER: {
    tint: 'var(--warning)',
    glyph: 'M3 5h18v14H3V5zm3 4h12M6 13h7',
  },
}

export function JourneyEditor({
  steps,
  audience,
  audienceSize,
  selected,
  onSelect,
  onAdd,
  onRemove,
  templateLabels,
  stopAfter,
  attachedBelow = false,
}: {
  steps: EditorStep[]
  /** Segment name, or empty while the marketer has not chosen one. */
  audience: string
  audienceSize: number | null
  selected: number | null
  onSelect: (index: number | null) => void
  onAdd: () => void
  onRemove: (index: number) => void
  templateLabels: Record<string, string>
  /** Campaign-level cap: the journey ends once a party has had this many sends. Null = no cap. */
  stopAfter: number | null
  /** True when the step editor renders directly beneath, so the two read as one surface. */
  attachedBelow?: boolean
}) {
  const { t, language } = useLanguage()
  const n = (v: number) => v.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')

  const delayLabel = (s: number): string => {
    if (s <= 0) return t('hned', 'right away')
    const d = Math.floor(s / 86400)
    if (d >= 1) return t(`za ${d} d`, `after ${d} d`)
    const h = Math.floor(s / 3600)
    if (h >= 1) return t(`za ${h} h`, `after ${h} h`)
    return t(`za ${Math.floor(s / 60)} min`, `after ${Math.floor(s / 60)} min`)
  }

  const canAdd = steps.length < MAX_STEPS
  // Entry + steps + the add affordance, which occupies a slot so the canvas does not jump when a
  // step is added.
  const cols = 1 + steps.length + (canAdd ? 1 : 0)
  const width = PAD * 2 + cols * NODE_W + (cols - 1) * GAP_X
  // Two rows live under the cards: the per-hop condition chips, then the journey-wide cap note.
  const height = ROW_Y + NODE_H / 2 + 92

  const colX = (i: number) => PAD + i * (NODE_W + GAP_X)

  /**
   * A connector, and the wait it represents.
   *
   * Drawn as a flat run with rounded ends rather than a bezier: the nodes are on one row, so a curve
   * would be ornament suggesting a freedom of layout this canvas does not have. The label sits in a
   * chip that masks the line, which is what keeps it readable when the theme is dark.
   */
  const conditionLabel = (c?: EditorCondition): string => {
    if (c === 'IF_PREVIOUS_CONFIRMED') return t('jen po doručení', 'if delivered')
    if (c === 'IF_PREVIOUS_NOT_CONFIRMED') return t('jen bez doručení', 'if not delivered')
    return ''
  }

  /**
   * A connector carries BOTH gates on the transition it represents: how long the journey waits, and
   * whether it proceeds at all. Putting the condition inside the node would read as a property of
   * the message rather than of the hop, and a marketer scanning the row would have to open each step
   * to find out why someone might not get it.
   */
  const edge = (fromIdx: number, toIdx: number, label: string, condition?: EditorCondition) => {
    const x0 = colX(fromIdx) + NODE_W
    const x1 = colX(toIdx)
    const mid = (x0 + x1) / 2
    const chipW = Math.max(46, label.length * 6.4 + 16)
    const cLabel = conditionLabel(condition)
    const cW = Math.max(60, cLabel.length * 6.2 + 22)
    return (
      <g key={`e${fromIdx}`}>
        <path
          d={`M ${x0} ${ROW_Y} L ${x1 - 4} ${ROW_Y}`}
          stroke="var(--border-strong)" strokeWidth="1.5" fill="none"
          strokeLinecap="round" markerEnd="url(#je-arrow)"
        />
        {label && (
          <>
            <rect
              x={mid - chipW / 2} y={ROW_Y - 11} width={chipW} height={22} rx="11"
              fill="var(--surface)" stroke="var(--border)" strokeWidth="1"
            />
            <text
              x={mid} y={ROW_Y + 4} fontSize="11" textAnchor="middle"
              fill="var(--text-secondary)"
            >
              {label}
            </text>
          </>
        )}
        {cLabel && (
          <g data-edge-condition={condition}>
            <rect
              x={mid - cW / 2} y={ROW_Y + NODE_H / 2 + 4} width={cW} height={21} rx="10.5"
              fill="var(--surface)" stroke="var(--warning)" strokeWidth="1"
            />
            {/* A filter glyph, so the chip reads as a gate rather than another label. */}
            <path
              d="M-4,-3.2 H4 L1,0.4 V3.6 L-1,2.6 V0.4 Z"
              transform={`translate(${mid - cW / 2 + 13}, ${ROW_Y + NODE_H / 2 + 14.5})`}
              fill="var(--warning)" opacity="0.9"
            />
            <text
              x={mid + 8} y={ROW_Y + NODE_H / 2 + 18.5} fontSize="10.5" textAnchor="middle"
              fill="var(--text-secondary)"
            >
              {cLabel}
            </text>
          </g>
        )}
      </g>
    )
  }

  return (
    <div
      className={
        attachedBelow
          ? 'overflow-x-auto rounded-t-xl border-x border-t'
          : 'overflow-x-auto rounded-xl border'
      }
      style={{ background: 'var(--surface-2)' }}
    >
      <svg
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', minWidth: Math.min(width, 820), height: 'auto', display: 'block' }}
        role="img"
        aria-label={t('Plátno pro sestavení kampaně', 'Campaign builder canvas')}
      >
        <defs>
          <marker id="je-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
            <path d="M0,0.5 L8,4.5 L0,8.5 Z" fill="var(--border-strong)" />
          </marker>
          <filter id="je-shadow" x="-30%" y="-30%" width="160%" height="180%">
            <feDropShadow dx="0" dy="2" stdDeviation="3" floodOpacity="0.10" />
          </filter>
          {/* The dot grid. Sized so it reads as texture at any canvas width rather than as content. */}
          <pattern id="je-grid" width="18" height="18" patternUnits="userSpaceOnUse">
            <circle cx="1.5" cy="1.5" r="1" fill="var(--border)" opacity="0.55" />
          </pattern>
        </defs>

        <rect x="0" y="0" width={width} height={height} fill="url(#je-grid)" />

        {/* Entry — the audience. Not editable here: a segment is code (ADR-0201 D1), so this node
            reports the choice made above rather than offering to author one. */}
        <g filter="url(#je-shadow)">
          <rect
            x={colX(0)} y={ROW_Y - NODE_H / 2} width={NODE_W} height={NODE_H} rx="14"
            fill="var(--surface)" stroke="var(--border-strong)" strokeWidth="1.2"
          />
          <rect
            x={colX(0) + 14} y={ROW_Y - ICON / 2} width={ICON} height={ICON} rx="11"
            fill="var(--info)" opacity="0.14"
          />
          {/* people glyph */}
          <path
            d="M9 11a3 3 0 100-6 3 3 0 000 6zm7 8v-1a5 5 0 00-10 0v1"
            transform={`translate(${colX(0) + 14 + ICON / 2 - 12}, ${ROW_Y - 12})`}
            fill="none" stroke="var(--info)" strokeWidth="1.7" strokeLinecap="round"
          />
          <text x={colX(0) + 14 + ICON + 12} y={ROW_Y - 12} fontSize="10" fill="var(--text-secondary)"
            letterSpacing="0.06em">
            {t('PUBLIKUM', 'AUDIENCE')}
          </text>
          <text x={colX(0) + 14 + ICON + 12} y={ROW_Y + 6} fontSize="13.5" fontWeight="600"
            fill="var(--text-primary)">
            {audience || t('zatím nevybráno', 'not chosen yet')}
          </text>
          <text x={colX(0) + 14 + ICON + 12} y={ROW_Y + 23} fontSize="11" fill="var(--text-secondary)">
            {audienceSize === null ? '—' : `${n(audienceSize)} ${t('lidí', 'people')}`}
          </text>
        </g>

        {steps.map((step, i) => {
          const x = colX(i + 1)
          const isSelected = selected === i
          const ch = CHANNEL[step.channel] ?? CHANNEL.EMAIL
          return (
            <g key={i}>
              {edge(i, i + 1, delayLabel(step.delaySeconds), step.condition)}
              <g
                filter="url(#je-shadow)"
                style={{ cursor: 'pointer' }}
                onClick={() => onSelect(isSelected ? null : i)}
                role="button"
                aria-label={t(`Upravit krok ${i + 1}`, `Edit step ${i + 1}`)}
                data-step={i}
                data-channel={step.channel}
                data-selected={isSelected ? 'true' : 'false'}
              >
                <rect
                  x={x} y={ROW_Y - NODE_H / 2} width={NODE_W} height={NODE_H} rx="14"
                  fill="var(--surface)"
                  stroke={isSelected ? 'var(--accent)' : 'var(--border-strong)'}
                  strokeWidth={isSelected ? 2 : 1.2}
                />
                {/* The channel block. Colour before text: on a five-node journey the first question
                    is "what goes out where", and reading five labels to answer it is the difference
                    between a diagram and a list. */}
                <rect
                  x={x + 14} y={ROW_Y - ICON / 2} width={ICON} height={ICON} rx="11"
                  fill={ch.tint} opacity="0.14"
                />
                <path
                  d={ch.glyph}
                  transform={`translate(${x + 14 + ICON / 2 - 12}, ${ROW_Y - 12})`}
                  fill="none" stroke={ch.tint} strokeWidth="1.7"
                  strokeLinecap="round" strokeLinejoin="round"
                />
                <text x={x + 14 + ICON + 12} y={ROW_Y - 12} fontSize="10" fill="var(--text-secondary)"
                  letterSpacing="0.06em">
                  {t('KROK', 'STEP')} {i + 1} · {step.channel === 'PUSH'
                    ? t('PUSH', 'PUSH')
                    : step.channel === 'BANNER'
                      ? t('PLOCHA V APLIKACI', 'IN-APP SURFACE')
                      : t('E-MAIL', 'EMAIL')}
                </text>
                <text x={x + 14 + ICON + 12} y={ROW_Y + 8} fontSize="13.5" fontWeight="600"
                  fill="var(--text-primary)">
                  {templateLabels[step.template] ?? step.template}
                </text>
                {step.fallbackToPush && (
                  <text x={x + 14 + ICON + 12} y={ROW_Y + 26} fontSize="10.5" fill="var(--text-secondary)">
                    {t('bez e-mail souhlasu → push', 'no email consent → push')}
                  </text>
                )}
              </g>

              {/* Remove sits on the node rather than in a toolbar: the thing it acts on is the thing
                  you are looking at, and a five-step journey never needs a bulk operation. */}
              <g
                style={{ cursor: 'pointer' }}
                onClick={() => onRemove(i)}
                role="button"
                aria-label={t(`Odebrat krok ${i + 1}`, `Remove step ${i + 1}`)}
                data-remove-step={i}
              >
                <circle cx={x + NODE_W - 15} cy={ROW_Y - NODE_H / 2 + 15} r="9.5"
                  fill="var(--surface-3)" stroke="var(--border)" strokeWidth="1" />
                <path
                  d="M-3.2,-3.2 L3.2,3.2 M3.2,-3.2 L-3.2,3.2"
                  transform={`translate(${x + NODE_W - 15}, ${ROW_Y - NODE_H / 2 + 15})`}
                  stroke="var(--text-secondary)" strokeWidth="1.5" strokeLinecap="round"
                />
              </g>
            </g>
          )
        })}

        {canAdd && (
          <g>
            {edge(steps.length, steps.length + 1, '')}
            <g
              style={{ cursor: 'pointer' }}
              onClick={onAdd}
              role="button"
              aria-label={t('Přidat krok', 'Add a step')}
              data-add-step="true"
            >
              <rect
                x={colX(steps.length + 1)} y={ROW_Y - NODE_H / 2}
                width={NODE_W} height={NODE_H} rx="14"
                fill="var(--surface)" fillOpacity="0.5"
                stroke="var(--border-strong)" strokeWidth="1.4" strokeDasharray="6 5"
              />
              <path
                d="M-7,0 H7 M0,-7 V7"
                transform={`translate(${colX(steps.length + 1) + NODE_W / 2}, ${ROW_Y - 8})`}
                stroke="var(--text-secondary)" strokeWidth="1.6" strokeLinecap="round"
              />
              <text
                x={colX(steps.length + 1) + NODE_W / 2} y={ROW_Y + 22}
                fontSize="12.5" textAnchor="middle" fill="var(--text-secondary)"
              >
                {t('přidat krok', 'add a step')}
              </text>
            </g>
          </g>
        )}

        {stopAfter !== null && (
          // The cap belongs on the canvas, not only in a form field: it is the reason a journey a
          // marketer drew five steps for may deliver two, and a number that changes the outcome
          // should not live where you have to scroll back to see it.
          <g data-stop-after={stopAfter}>
            <rect
              x={PAD} y={ROW_Y + NODE_H / 2 + 34} width={214} height={24} rx="12"
              fill="var(--surface)" stroke="var(--border-strong)" strokeWidth="1"
            />
            <path
              d="M-4,-4 H4 V4 H-4 Z"
              transform={`translate(${PAD + 16}, ${ROW_Y + NODE_H / 2 + 46})`}
              fill="var(--text-secondary)" opacity="0.75"
            />
            <text x={PAD + 28} y={ROW_Y + NODE_H / 2 + 50} fontSize="11" fill="var(--text-secondary)">
              {t(`Konec po ${stopAfter} zprávách na člověka`, `Stops after ${stopAfter} messages per person`)}
            </text>
          </g>
        )}

        {!canAdd && (
          // Stated, not enforced silently: the cap is a domain rule (Campaign.MAX_STEPS), and a
          // marketer who cannot find the add button deserves to know why rather than assume a bug.
          <text
            x={width - PAD} y={ROW_Y + NODE_H / 2 + 78}
            fontSize="11" textAnchor="end" fill="var(--text-secondary)"
          >
            {t(
              `Maximum je ${MAX_STEPS} kroků — delší cesty se v praxi neudrží.`,
              `${MAX_STEPS} steps is the maximum — longer journeys do not survive contact with reality.`,
            )}
          </text>
        )}
      </svg>
    </div>
  )
}
