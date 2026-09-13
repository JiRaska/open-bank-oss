// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { LoadMoreControl } from '@/components/ui'

describe('LoadMoreControl', () => {
  it('announces extent and links an available incremental action to its results', () => {
    const onLoadMore = vi.fn()
    render(<LoadMoreControl loaded={25} total={60} progressLabel="Showing 25 of 60 accounts"
      buttonLabel="Load 25 more" buttonAriaLabel="Load more accounts" controls="accounts-results"
      onLoadMore={onLoadMore} />)

    expect(screen.getByRole('status')).toHaveTextContent('Showing 25 of 60 accounts')
    const button = screen.getByRole('button', { name: 'Load more accounts' })
    expect(button).toHaveAttribute('aria-controls', 'accounts-results')
    fireEvent.click(button)
    expect(onLoadMore).toHaveBeenCalledOnce()
  })

  it('keeps the final extent visible and removes the exhausted action', () => {
    render(<LoadMoreControl loaded={18} total={18} progressLabel="Showing 18 of 18 cards"
      buttonLabel="Load more" buttonAriaLabel="Load more cards" controls="cards-results"
      onLoadMore={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('Showing 18 of 18 cards')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('does not create a second live region when the result surface already owns announcements', () => {
    render(<LoadMoreControl loaded={2} total={2} progressLabel="Showing 2 of 2 cards"
      buttonLabel="Load more" buttonAriaLabel="Load more cards" controls="cards-results"
      onLoadMore={vi.fn()} announceProgress={false} />)

    expect(screen.getByText('Showing 2 of 2 cards')).not.toHaveAttribute('role')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
