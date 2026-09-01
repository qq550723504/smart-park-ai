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
  detailPath: 'workflow' | 'customer'
}

export interface CollaborationWorkItemFilters {
  source?: CollaborationWorkItemSource
  status?: CollaborationWorkItemStatus
  limit?: number
}
