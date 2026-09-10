import type { CustomerAnalysisContext } from '../types/customer'

export function customerRecommendations(context: CustomerAnalysisContext): string[] {
  const rows = ['按异常窗口核对楼宇运行计划与实际启停记录，保留人工确认。']
  const devices = context.overviewDomainStatus.devices
  const alerts = context.overviewDomainStatus.alerts
  if (devices === 'UNAVAILABLE') rows.push('先恢复或补充设备状态数据，再判断设备与能耗变化的关系。')
  else if ((context.summary?.offlineDeviceCount ?? 0) > 0) rows.push('先恢复或核验离线设备的数据采集，再判断能耗变化。')
  if (alerts === 'UNAVAILABLE') rows.push('先补充告警域数据，当前不确认窗口内是否没有关联告警。')
  else if ((context.summary?.alertCount ?? 0) > 0) rows.push('逐条核对关联告警及现场情况，不把关联性直接写成故障结论。')
  rows.push('在后续小时持续观察实际用电与基线；当前页面不会执行能源控制。')
  return rows
}
