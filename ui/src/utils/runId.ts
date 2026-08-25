/**
 * Deterministic workflowId → unified execution runId mapping, mirroring
 * LegacyWorkflowEventAdapter.runIdFor on the backend (UUID v3 / MD5 of a
 * namespaced string, RFC 4122 formatting).
 */

const NAMESPACE = 'smart-park-alert-workflow:'

function md5(bytes: Uint8Array): Uint8Array {
  const s = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
  ]
  const K = new Int32Array(64)
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296)

  const originalLength = bytes.length
  const message = new Uint8Array((((originalLength + 8) >> 6) + 1) << 6)
  message.set(bytes)
  message[originalLength] = 0x80
  const bitLength = originalLength * 8
  new DataView(message.buffer).setUint32(message.length - 8, bitLength >>> 0, true)
  new DataView(message.buffer).setUint32(message.length - 4, Math.floor(bitLength / 4294967296), true)

  let a0 = 0x67452301
  let b0 = 0xefcdab89
  let c0 = 0x98badcfe
  let d0 = 0x10325476

  const view = new DataView(message.buffer)
  for (let chunk = 0; chunk < message.length; chunk += 64) {
    const M = new Int32Array(16)
    for (let i = 0; i < 16; i++) M[i] = view.getInt32(chunk + i * 4, true)

    let A = a0
    let B = b0
    let C = c0
    let D = d0
    for (let i = 0; i < 64; i++) {
      let F: number
      let g: number
      if (i < 16) {
        F = (B & C) | (~B & D)
        g = i
      } else if (i < 32) {
        F = (D & B) | (~D & C)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        F = B ^ C ^ D
        g = (3 * i + 5) % 16
      } else {
        F = C ^ (B | ~D)
        g = (7 * i) % 16
      }
      F = (F + A + K[i] + M[g]) | 0
      A = D
      D = C
      C = B
      B = (B + ((F << s[i]) | (F >>> (32 - s[i])))) | 0
    }
    a0 = (a0 + A) | 0
    b0 = (b0 + B) | 0
    c0 = (c0 + C) | 0
    d0 = (d0 + D) | 0
  }
  const digest = new Uint8Array(16)
  const digestView = new DataView(digest.buffer)
  digestView.setInt32(0, a0, true)
  digestView.setInt32(4, b0, true)
  digestView.setInt32(8, c0, true)
  digestView.setInt32(12, d0, true)
  return digest
}

function toUuidV3(digest: Uint8Array): string {
  const hex = Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')
  const versioned = hex.slice(0, 12) + '3' + hex.slice(13, 16) + ((parseInt(hex[16], 16) & 0x3) | 0x8).toString(16) + hex.slice(17)
  return [
    versioned.slice(0, 8),
    versioned.slice(8, 12),
    versioned.slice(12, 16),
    versioned.slice(16, 20),
    versioned.slice(20),
  ].join('-')
}

export function alertWorkflowRunId(workflowId: string): string {
  const bytes = new TextEncoder().encode(NAMESPACE + workflowId)
  return toUuidV3(md5(bytes))
}
