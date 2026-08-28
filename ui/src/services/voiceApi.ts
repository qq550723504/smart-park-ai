interface CreateVoiceSessionResponse {
  sessionId: string
  runId: string
  wsPath: string
}

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`语音会话请求失败（${response.status}）`)
  }
  return response.json() as Promise<T>
}

export async function createVoiceSession(): Promise<CreateVoiceSessionResponse> {
  return parse(
    await fetch('/api/voice/sessions', { method: 'POST' }),
  )
}
