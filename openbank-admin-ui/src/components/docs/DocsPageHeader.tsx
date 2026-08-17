import type { ReactNode } from 'react'
import { PageHeader } from '@/components/ui/PageHeader'

type DocsPageHeaderProps = {
  title: ReactNode
  subtitle?: ReactNode
  icon?: ReactNode
  /** Breadcrumb items, without the surrounding `.breadcrumb` landmark styling. */
  crumbs: ReactNode
  actions?: ReactNode
}

/**
 * Documentation-specific wrapper around the shared header. Keeping the breadcrumb wrapper here
 * prevents the 20+ server/client docs routes from drifting back to hand-rolled hierarchy markup.
 */
export function DocsPageHeader({ title, subtitle, icon, crumbs, actions }: DocsPageHeaderProps) {
  return (
    <PageHeader
      breadcrumb={<div className="breadcrumb">{crumbs}</div>}
      title={title}
      subtitle={subtitle}
      icon={icon}
      actions={actions}
    />
  )
}
