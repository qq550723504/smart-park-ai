let fallbackSequence = 0

type RequestCrypto = {
  getRandomValues?: (bytes: Uint8Array) => Uint8Array
  randomUUID?: () => string
}

function uuidFromRandomValues(cryptoApi: RequestCrypto): string | null {
  if (typeof cryptoApi.getRandomValues !== 'function') return null

  const bytes = new Uint8Array(16)
  cryptoApi.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-')
}

/**
 * Creates a request key without requiring a secure browser context.
 * randomUUID is unavailable in some HTTP/LAN browsers, while the backend
 * only requires a non-blank key to protect retries from duplicate effects.
 */
export function createRequestId(): string {
  const cryptoApi = globalThis.crypto as RequestCrypto | undefined
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID()

  const uuid = cryptoApi ? uuidFromRandomValues(cryptoApi) : null
  if (uuid) return uuid

  fallbackSequence += 1
  return `request-${Date.now().toString(36)}-${fallbackSequence.toString(36)}-${Math.random().toString(36).slice(2)}`
}
