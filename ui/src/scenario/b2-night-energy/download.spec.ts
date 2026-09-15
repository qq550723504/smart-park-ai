import { describe, expect, it, vi } from 'vitest'
import { downloadReportSnapshot } from './download'

function fakeEnvironment() {
  const anchor = { href: '', download: '', rel: '', click: vi.fn(), remove: vi.fn() }
  const body = { appendChild: vi.fn() }
  const document = {
    createElement: vi.fn(() => anchor),
    body,
  } as unknown as Document
  const createObjectURL = vi.fn((_blob: Blob) => 'blob:frozen-snapshot')
  const revokeObjectURL = vi.fn()
  return { anchor, body, document, createObjectURL, revokeObjectURL }
}

describe('B2 report download', () => {
  it('writes the frozen markdown verbatim under the report id and revokes the URL', () => {
    const env = fakeEnvironment()
    const ok = downloadReportSnapshot(
      { reportId: 'SCN-RPT-B2-001-01', markdown: '# 事件简报\n冻结内容' },
      { document: env.document, createObjectURL: env.createObjectURL, revokeObjectURL: env.revokeObjectURL },
    )

    expect(ok).toBe(true)
    expect(env.createObjectURL).toHaveBeenCalledTimes(1)
    const blob = env.createObjectURL.mock.calls[0][0] as Blob
    expect(blob.type).toContain('text/markdown')
    expect(env.anchor.download).toBe('SCN-RPT-B2-001-01.md')
    expect(env.body.appendChild).toHaveBeenCalledWith(env.anchor)
    expect(env.anchor.click).toHaveBeenCalledTimes(1)
    expect(env.anchor.remove).toHaveBeenCalledTimes(1)
    expect(env.revokeObjectURL).toHaveBeenCalledWith('blob:frozen-snapshot')
  })

  it('is a silent no-op when the environment has no object-URL support', () => {
    const env = fakeEnvironment()
    const ok = downloadReportSnapshot(
      { reportId: 'SCN-RPT-B2-001-01', markdown: '# 事件简报' },
      { document: env.document, createObjectURL: undefined },
    )
    // jsdom exposes no `URL.createObjectURL`, so nothing is written.
    expect(ok).toBe(false)
    expect(env.createObjectURL).not.toHaveBeenCalled()
    expect(env.anchor.click).not.toHaveBeenCalled()
  })
})
