// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

type LoadMoreControlProps = {
  loaded: number
  total: number
  progressLabel: string
  buttonLabel: string
  buttonAriaLabel: string
  controls: string
  onLoadMore: () => void
  /** Disable only when the surrounding result surface already announces the same count. */
  announceProgress?: boolean
  /**
   * Whether more rows exist. Omit for a client-side list, where `loaded < total` answers it.
   * A CURSOR-paginated caller must pass it: it has fetched one page, so `total` is the size of
   * what is on screen and `loaded < total` is false while the server still holds more.
   */
  hasMore?: boolean
  /** A fetch is in flight. Disables the button and marks it busy, so it cannot be double-fired. */
  busy?: boolean
  busyLabel?: string
}

/** Bounded-list footer that keeps result extent visible and announces incremental progress. */
export function LoadMoreControl({
  loaded,
  total,
  progressLabel,
  buttonLabel,
  buttonAriaLabel,
  controls,
  onLoadMore,
  announceProgress = true,
  hasMore,
  busy = false,
  busyLabel,
}: LoadMoreControlProps) {
  const moreAvailable = hasMore ?? loaded < total

  return (
    <div
      style={{
        padding: '12px 16px',
        borderTop: '1px solid var(--border)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 12,
        flexWrap: 'wrap',
      }}
    >
      <span
        role={announceProgress ? 'status' : undefined}
        aria-live={announceProgress ? 'polite' : undefined}
        style={{ fontSize: 12, color: 'var(--text-tertiary)' }}
      >
        {progressLabel}
      </span>
      {moreAvailable && (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          aria-label={buttonAriaLabel}
          aria-controls={controls}
          aria-busy={busy}
          disabled={busy}
          onClick={onLoadMore}
        >
          {busy && busyLabel ? busyLabel : buttonLabel}
        </button>
      )}
    </div>
  )
}
