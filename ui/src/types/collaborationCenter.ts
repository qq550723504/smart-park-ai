export type CollaborationWorkItemSource = 'ALERT_WORKFLOW' | 'CUSTOMER_TICKET'
export type CollaborationWorkItemStatus =
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'COMPLETED'
  | 'REJECTED'
  | 'FAILED'
  | 'WORK_ORDER_FAILED'
  | 'WAITING_AGENT'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'WAITING_CUSTOMER'
  | 'RESOLVED'
  | 'CLOSED'
  | 'CANCELLED'
export type CollaborationWorkItemPriority = 'HIGH' | 'NORMAL'
export type CollaborationWorkItemSlaState = 'ON_TRACK' | 'DUE_SOON' | 'OVERDUE' | 'COMPLETED' | 'NOT_APPLICABLE'

export interface CollaborationWorkItem {
  id: string
  source: CollaborationWorkItemSource
  status: CollaborationWorkItemStatus
  priority: CollaborationWorkItemPriority
  title: string
  safeSummary: string
  parkId: string | null
  buildingId: string | null
  deviceId: string | null
  updatedAt: string
  openedAt: string | null
  slaDueAt: string | null
  slaState: CollaborationWorkItemSlaState
  detailPath: 'workflow' | 'customer'
}

export interface CollaborationSlaSnapshot {
  capturedAt: string
  total: number
  overdue: number
  dueSoon: number
  onTrack: number
  completed: number
  notApplicable: number
}

export interface CollaborationWorkItemFilters {
  source?: CollaborationWorkItemSource
  status?: CollaborationWorkItemStatus
  limit?: number
  sort?: 'sla' | 'updatedAt'
}
