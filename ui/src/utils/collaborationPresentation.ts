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
  meterId: '电表',
  deviceId: '设备',
  eventId: '事件',
  alertId: '告警',
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
  HIGH: '高风险',
  LOW: '低风险',
  MEDIUM: '中风险',
}

function extractObjects(text: string): Record<string, unknown>[] {
  const objects: Record<string, unknown>[] = []
  let start = text.indexOf('{')
  while (start >= 0) {
    let depth = 0
    let inString = false
    let escaped = false
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
    start = text.indexOf('{', start + 1)
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
  if (key === 'measuredAt' || key === 'occurredAt') return formatDate(value)
  if (key === 'currentKwh' || key === 'baselineKwh' || key === 'varianceKwh') return `${formatNumber(value)} kWh`
  if (key === 'peakDemandKw') return `${formatNumber(value)} kW`
  if (key === 'varianceRatio' && typeof value === 'number') return `${formatNumber(value * 100)}%`
  if (key === 'status' || key === 'riskLevel') return statusLabels[String(value).toUpperCase()] ?? String(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object' && value !== null) return ''
  return String(value ?? '')
}

function summaryFor(domain: ExpertDomain, finding: ExpertFinding, details: Array<{ label: string; value: string }>, hasStructuredObject: boolean): string {
  if (finding.status === 'INSUFFICIENT_EVIDENCE') return `${domainLabels[domain]}当前证据不足，暂无法确认该领域结论。`
  if (finding.status === 'FAILED') return `${domainLabels[domain]}本轮分析未完成，请稍后重试。`
  if (details.length > 0) return `${domainLabels[domain]}已完成核查，关键数据如下。`
  if (hasStructuredObject) return `${domainLabels[domain]}已返回核查结果。`
  const conclusion = finding.conclusion.trim()
  return conclusion ? `${domainLabels[domain]}已返回核查结果：${conclusion}` : `${domainLabels[domain]}已返回核查结果。`
}

export function formatEvidenceRef(ref: string): string {
  const match = /^tool:([^#]+)#/.exec(ref)
  return match ? `${toolLabels[match[1]] ?? '工具查询'}证据` : '已验证证据'
}

export function formatNextCheck(check: string): string {
  const normalized = check.toLowerCase()
  if (normalized.includes('redacted summary')) return '复核脱敏摘要'
  if (normalized.includes('repeat') && normalized.includes('tool lookup')) return '重新查询分派实体的领域数据'
  if (/[\u4e00-\u9fff]/.test(check)) return check
  return '按领域重新核验相关证据'
}

export function formatFinding(finding: ExpertFinding): FindingDisplay {
  const objects = extractObjects(finding.conclusion)
  const source = objects[0]
  const details = source
    ? Object.entries(source)
        .filter(([key, value]) => fieldLabels[key] && value !== null && value !== '')
        .map(([key, value]) => ({ label: fieldLabels[key], value: formatValue(key, value) }))
        .filter((detail) => detail.value !== '')
    : []

  return {
    summary: summaryFor(finding.domain, finding, details, Boolean(source)),
    details,
    evidence: finding.evidenceRefs.map(formatEvidenceRef),
    nextChecks: finding.nextChecks.map(formatNextCheck),
  }
}

function formatUncertaintyText(text: string): string {
  const normalized = text.toLowerCase()
  if (normalized.includes('confidence 0.0') && normalized.includes('temporal, spatial, or causal')) {
    return '安防专家置信度为 0%，当前证据无法与能耗、设备结论建立时间、空间或因果关联；现有结论均为独立的演示数据，暂无跨域关联证据。'
  }
  if (/[\u4e00-\u9fff]/.test(text) && !/[a-z]{3,}/i.test(text)) return text
  const domain = text.includes('SECURITY') ? '安防专家' : text.includes('DEVICE') ? '设备专家' : text.includes('ENERGY') ? '能耗专家' : '部分专家'
  return `${domain}证据不足，当前无法确认跨域关联。`
}

export function formatSynthesis(synthesis: Synthesis): SynthesisDisplay {
  const localizedConclusion = synthesis.status === 'SUPPORTED'
    ? '已确认存在关联'
    : synthesis.status === 'FAILED'
      ? '专家分析未完成'
      : '当前证据不足，暂无法确认关联'
  const rawConclusion = synthesis.conclusion.trim()
  return {
    conclusion: rawConclusion && rawConclusion !== localizedConclusion
      ? `${localizedConclusion}：${rawConclusion}`
      : localizedConclusion,
    uncertainties: synthesis.uncertainties.map(formatUncertaintyText),
    evidence: synthesis.evidenceRefs.map(formatEvidenceRef),
  }
}
