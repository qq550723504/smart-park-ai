const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '::1'])

export const VOICE_INPUT_RESTRICTION_MESSAGE = '语音输入需要 HTTPS；本机演示请使用 localhost。'

export function isVoiceInputAllowed(
  locationLike: Pick<Location, 'hostname'> = globalThis.location,
  secureContext = globalThis.isSecureContext,
): boolean {
  return secureContext === true || LOOPBACK_HOSTS.has(locationLike.hostname.toLowerCase())
}
