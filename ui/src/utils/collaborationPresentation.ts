import type { ExpertDomain, ExpertFinding, Synthesis } from '../types/collaboration'

export interface FindingDisplay {
  summary: string
  details: Array<{ label: string; value: string }>
  evidence: string[]
  nextChecks: string[]
}

export interface SynthesisDisplay {
  conclusion: string
  uncertainties: string[]
  evidence: string[]
}

const domainLabels: Record<ExpertDomain, string> = {
  ENERGY: '能耗专家',
  DEVICE: '设备专家',
  SECURITY: '安防专家',
}

const toolLabels: Record<string, string> = {
  lookupEnergyConsumption: '能耗查询',
  lookupDeviceStatus: '设备状态查询',
  lookupSecurityEvent: '安防事件查询',
  lookupAlert: '告警查询',
  lookupAlertHistory: '告警历史查询',
  lookupWorkOrders: '工单查询',
  searchParkKnowledge: '园区知识查询',
}

const fieldLabels: Record<string, string> = {
  query: '检索词',
  id: '知识文档',
  documentId: '知识文档',
  title: '知识标题',
  domain: '知识领域',
  tags: '标签',
  score: '匹配度',
  updatedAt: '更新时间',
  classification: '告警类型',
  riskHint: '风险提示',
  summary: '告警摘要',
  meterId: '电表',
  deviceId: '设备',
  eventId: '事件',
  eventType: '事件类型',
  evidenceSummary: '事件摘要',
  alertId: '告警',
  workOrderId: '工单',
  workOrderSummary: '工单摘要',
  buildingId: '楼宇',
  parkId: '园区',
  status: '状态',
  category: '类别',
  riskLevel: '风险等级',
  currentKwh: '当前能耗',
  baselineKwh: '基线能耗',
  peakDemandKw: '峰值功率',
  varianceKwh: '能耗差值',
  varianceRatio: '偏差率',
  measuredAt: '采集时间',
  occurredAt: '发生时间',
}

const statusLabels: Record<string, string> = {
  ACTIVE: '运行中',
  ONLINE: '在线',
  OFFLINE: '离线',
  OPEN: '未处理',
  RESOLVED: '已解决',
  PENDING_EXECUTION: '待现场执行',
  IN_PROGRESS: '执行中',
  CANCELLED: '已取消',
  HIGH: '高风险',
  LOW: '低风险',
  MEDIUM: '中风险',
}

const knowledgeDomainLabels: Record<string, string> = {
  ALERT_OPERATIONS: '告警运维',
  CUSTOMER_SERVICE: '客户服务',
}

const alertClassificationLabels: Record<string, string> = {
  TEMPERATURE: '温度',
  POWER: '功率',
  ENERGY: '能耗',
  ACCESS: '访问',
  PUMP: '水泵',
  UNKNOWN: '未知',
}

const securityEventTypeLabels: Record<string, string> = {
  UNAUTHORIZED_ACCESS_ATTEMPT: '未授权访问尝试',
}

function extractObjects(text: string): Record<string, unknown>[] {
  const objects: Record<string, unknown>[] = []
  let cursor = 0
  while (cursor < text.length) {
    const start = text.indexOf('{', cursor)
    if (start < 0) break
    let depth = 0
    let inString = false
    let escaped = false
    let end = -1
    for (let index = start; index < text.length; index += 1) {
      const character = text[index]
      if (inString) {
        if (escaped) escaped = false
        else if (character === '\\') escaped = true
        else if (character === '"') inString = false
        continue
      }
      if (character === '"') inString = true
      else if (character === '{') depth += 1
      else if (character === '}') {
        depth -= 1
        if (depth === 0) {
          end = index
          try {
            const parsed: unknown = JSON.parse(text.slice(start, index + 1))
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
              objects.push(parsed as Record<string, unknown>)
            }
          } catch {
            // Ignore malformed fragments and continue looking for a safe fallback.
          }
          break
        }
      }
    }
    if (end < 0) break
    cursor = end + 1
  }
  return objects
}

function formatNumber(value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value ?? '')
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

function formatDate(value: unknown): string {
  if (typeof value !== 'string') return String(value ?? '')
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatValue(key: string, value: unknown): string {
  if (key === 'measuredAt' || key === 'occurredAt' || key === 'updatedAt') return formatDate(value)
  if (key === 'currentKwh' || key === 'baselineKwh' || key === 'varianceKwh') return `${formatNumber(value)} kWh`
  if (key === 'peakDemandKw') return `${formatNumber(value)} kW`
  if (key === 'varianceRatio' && typeof value === 'number') return `${formatNumber(value * 100)}%`
  if (key === 'score' && typeof value === 'number') return `${formatNumber(value * 100)}%`
  if (key === 'domain') return knowledgeDomainLabels[String(value).toUpperCase()] ?? String(value)
  if (key === 'tags' && Array.isArray(value)) return value.filter((item) => typeof item === 'string').join('、')
  if (key === 'classification') return alertClassificationLabels[String(value).toUpperCase()] ?? String(value)
  if (key === 'eventType') return securityEventTypeLabels[String(value).toUpperCase()] ?? String(value)
  if (key === 'status' || key === 'riskLevel' || key === 'riskHint') return statusLabels[String(value).toUpperCase()] ?? String(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object' && value !== null) return ''
  return String(value ?? '')
}

function collectKnownFields(value: unknown, fields: Array<[string, unknown]> = [], context: 'root' | 'knowledge' | 'workorder' = 'root'): Array<[string, unknown]> {
  if (Array.isArray(value)) {
    value.forEach((item) => collectKnownFields(item, fields, context))
    return fields
  }
  if (!value || typeof value !== 'object') return fields

  Object.entries(value).forEach(([key, nestedValue]) => {
    const nextContext = key === 'documents' ? 'knowledge' : key === 'workOrders' ? 'workorder' : context
    const isIdentifier = key === 'id' || key === 'documentId'
    const isScopedIdentifier = isIdentifier && context !== 'root'
    const projectedKey = isIdentifier && context === 'workorder'
      ? 'workOrderId'
      : key === 'summary' && context === 'workorder'
        ? 'workOrderSummary'
        : key
    if (fieldLabels[key] && (typeof nestedValue !== 'object' || nestedValue === null || key === 'tags') && (!isIdentifier || isScopedIdentifier)) {
      fields.push([projectedKey, nestedValue])
    } else {
      collectKnownFields(nestedValue, fields, nextContext)
    }
  })
  return fields
}

function collectUniqueKnownFields(value: unknown): Array<[string, unknown]> {
  const seen = new Set<string>()
  return collectKnownFields(value).filter(([key, fieldValue]) => {
    const marker = [key, typeof fieldValue, String(fieldValue)].join(':')
    if (seen.has(marker)) return false
    seen.add(marker)
    return true
  })
}

function summaryFor(domain: ExpertDomain, finding: ExpertFinding, details: Array<{ label: string; value: string }>, hasStructuredObject: boolean): string {
  if (finding.status === 'INSUFFICIENT_EVIDENCE') return `${domainLabels[domain]}当前证据不足，暂无法确认该领域结论。`
  if (finding.status === 'FAILED') return `${domainLabels[domain]}本轮分析未完成，请稍后重试。`
  if (details.length > 0) return `${domainLabels[domain]}已完成核查，关键数据如下。`
  if (hasStructuredObject) return `${domainLabels[domain]}已返回核查结果。`
  const conclusion = finding.conclusion.trim()
  return conclusion ? `${domainLabels[domain]}已返回核查结果：${conclusion}` : `${domainLabels[domain]}已返回核查结果。`
}

export function expertDetailKey(label: string, value: string, index: number): string {
  return [label, value, index].join('-')
}

export function formatEvidenceRef(ref: string): string {
  const match = /^tool:([^#]+)#/.exec(ref)
  return match ? `${toolLabels[match[1]] ?? '工具查询'}证据` : '已验证证据'
}

export function formatNextCheck(check: string): string {
  const normalized = check.toLowerCase()
  if (normalized.includes('redacted summary')) return '复核脱敏摘要'
  if ((normalized.includes('repeat') || normalized.includes('retry')) && normalized.includes('tool lookup')) return '重新查询分派实体的领域数据'
  if (/[\u4e00-\u9fff]/.test(check)) return check
  return '按领域重新核验相关证据'
}

export function formatFinding(finding: ExpertFinding): FindingDisplay {
  const objects = extractObjects(finding.conclusion)
  const details = objects.flatMap((object) => collectUniqueKnownFields(object))
    .filter(([, value]) => value !== null && value !== '')
    .map(([key, value]) => ({ label: fieldLabels[key], value: formatValue(key, value) }))
    .filter((detail) => detail.value !== '')

  return {
    summary: summaryFor(finding.domain, finding, details, objects.length > 0),
    details,
    evidence: finding.evidenceRefs.map(formatEvidenceRef),
    nextChecks: finding.nextChecks.map(formatNextCheck),
  }
}

function formatUncertaintyText(text: string): string {
  const normalized = text.toLowerCase()
  if (normalized.includes('confidence 0.0') && normalized.includes('temporal, spatial, or causal')) {
    const domains = (text.match(/\b(ENERGY|DEVICE|SECURITY)\b/gi) ?? []).map((item) => item.toUpperCase() as ExpertDomain)
    const affectedMatch = /\b(ENERGY|DEVICE|SECURITY)\b\s+finding\b/i.exec(text)
    const affectedDomain = (affectedMatch?.[1]?.toUpperCase() as ExpertDomain | undefined) ?? domains[0]
    const peerLabels = Array.from(new Set(domains.filter((item) => item !== affectedDomain)))
      .map((item) => domainLabels[item].replace('专家', ''))
      .join('、') || '其他领域'
    const confidenceMatch = /\bconfidence\s+([0-9]+(?:\.[0-9]+)?)/i.exec(text)
    const confidence = confidenceMatch ? formatNumber(Number(confidenceMatch[1]) * 100) : '0'
    const affectedLabel = affectedDomain ? domainLabels[affectedDomain] : '相关专家'
    return affectedLabel + '置信度为 ' + confidence + '%，当前证据无法与' + peerLabels + '结论建立时间、空间或因果关联；现有结论均为独立的演示数据，暂无跨域关联证据。'
  }
  const domain = text.includes('SECURITY') ? '安防专家' : text.includes('DEVICE') ? '设备专家' : text.includes('ENERGY') ? '能耗专家' : '部分专家'
  if (/(?:failed|failure|error|timeout|timed out|执行失败|查询失败|调用失败|工具失败)/i.test(text)) {
    return domain + '执行失败，本轮核查未完成，请重试相关工具。'
  }
  if (/[\u4e00-\u9fff]/.test(text) && !/[a-z]{3,}/i.test(text)) return text
  return `${domain}证据不足，当前无法确认跨域关联。`
}

export function formatSynthesis(synthesis: Synthesis): SynthesisDisplay {
  const localizedConclusion = synthesis.status === 'FAILED'
    ? '专家分析未完成'
    : '当前证据不足，暂无法确认请求结论'
  const rawConclusion = synthesis.conclusion.trim()
  return {
    conclusion: synthesis.status === 'SUPPORTED' && rawConclusion ? rawConclusion : localizedConclusion,
    uncertainties: synthesis.uncertainties.map(formatUncertaintyText),
    evidence: synthesis.evidenceRefs.map(formatEvidenceRef),
  }
}
