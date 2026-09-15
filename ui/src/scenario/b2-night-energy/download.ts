import type { ScenarioReport } from '../../types/scenarioEnergy'

export interface DownloadEnvironment {
  document?: Document
  createObjectURL?: (blob: Blob) => string
  revokeObjectURL?: (url: string) => void
}

/**
 * Triggers a browser download for an already-frozen report snapshot.
 *
 * The report's `markdown` is written verbatim: downloading must never
 * regenerate a snapshot (contract action `DOWNLOAD_REPORT`). Returns false in
 * non-browser environments so callers can stay silent instead of throwing.
 */
export function downloadReportSnapshot(
  report: Pick<ScenarioReport, 'reportId' | 'markdown'>,
  environment: DownloadEnvironment = {},
): boolean {
  const doc = 'document' in environment
    ? environment.document
    : (typeof document !== 'undefined' ? document : undefined)
  const createObjectURL = 'createObjectURL' in environment
    ? environment.createObjectURL
    : (typeof URL !== 'undefined' ? URL.createObjectURL?.bind(URL) : undefined)
  const revokeObjectURL = 'revokeObjectURL' in environment
    ? environment.revokeObjectURL
    : (typeof URL !== 'undefined' ? URL.revokeObjectURL?.bind(URL) : undefined)
  if (!doc || typeof createObjectURL !== 'function') return false

  const blob = new Blob([report.markdown], { type: 'text/markdown;charset=utf-8' })
  const objectUrl = createObjectURL(blob)
  if (!objectUrl) return false
  const anchor = doc.createElement('a')
  anchor.href = objectUrl
  anchor.download = `${report.reportId}.md`
  anchor.rel = 'noopener'
  doc.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  revokeObjectURL?.(objectUrl)
  return true
}
