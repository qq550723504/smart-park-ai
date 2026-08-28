/**
 * Realtime voice protocol mirror of the backend contract:
 * controls START_INPUT|COMMIT_INPUT|INTERRUPT_OUTPUT|CLOSE_SESSION;
 * server frames SESSION_STATE|ASR_PARTIAL|ASR_FINAL|TOOL_EVENT|
 * ANSWER_DELTA|AUDIO_CHUNK|ERROR. Every JSON frame carries sessionId,
 * messageId and sequence.
 */

export type VoiceClientControlType =
  | 'START_INPUT'
  | 'COMMIT_INPUT'
  | 'INTERRUPT_OUTPUT'
  | 'CLOSE_SESSION'

export type VoiceServerFrameType =
  | 'SESSION_STATE'
  | 'ASR_PARTIAL'
  | 'ASR_FINAL'
  | 'TOOL_EVENT'
  | 'ANSWER_DELTA'
  | 'AUDIO_CHUNK'
  | 'ERROR'

export type VoiceSessionState =
  | 'IDLE'
  | 'LISTENING'
  | 'ASR_FINALIZED'
  | 'REASONING'
  | 'TOOL_CALLING'
  | 'ANSWER_STREAMING'
  | 'SPEAKING'
  /** 可重试的失败状态；CLOSED 不经此通道下发。 */
  | 'ERROR'

export interface VoiceEnvelope {
  sessionId: string
  messageId: string
  sequence: number
}

export interface SessionStateFrame extends VoiceEnvelope {
  type: 'SESSION_STATE'
  state: VoiceSessionState
  turnId: string | null
}

export interface AsrPartialFrame extends VoiceEnvelope {
  type: 'ASR_PARTIAL'
  text: string
}

export interface AsrFinalFrame extends VoiceEnvelope {
  type: 'ASR_FINAL'
  text: string
}

export interface ToolEventFrame extends VoiceEnvelope {
  type: 'TOOL_EVENT'
  toolName: string
  phase: 'STARTED' | 'COMPLETED'
  argumentSummary: string
}

export interface AnswerDeltaFrame extends VoiceEnvelope {
  type: 'ANSWER_DELTA'
  delta: string
}

/** Metadata only — raw PCM travels as the next binary WS message. */
export interface AudioChunkFrame extends VoiceEnvelope {
  type: 'AUDIO_CHUNK'
  chunkSequence: number
  sizeBytes: number
}

export interface ErrorFrame extends VoiceEnvelope {
  type: 'ERROR'
  code:
    | 'INVALID_FRAME'
    | 'UNSUPPORTED_STATE'
    | 'AUDIO_REJECTED'
    | 'PROVIDER_FAILURE'
    | 'TIMEOUT'
    | 'ANSWER_VALIDATION_FAILED'
    | 'INTERNAL_ERROR'
  userMessage: string
}

export type VoiceServerFrame =
  | SessionStateFrame
  | AsrPartialFrame
  | AsrFinalFrame
  | ToolEventFrame
  | AnswerDeltaFrame
  | AudioChunkFrame
  | ErrorFrame

export interface ClientControlFrame extends VoiceEnvelope {
  type: VoiceClientControlType
}

const FRAME_TYPES: readonly string[] = [
  'SESSION_STATE',
  'ASR_PARTIAL',
  'ASR_FINAL',
  'TOOL_EVENT',
  'ANSWER_DELTA',
  'AUDIO_CHUNK',
  'ERROR',
]

/**
 * Parses and shape-checks one server JSON frame; throws on anything that does
 * not match the protocol so callers can skip it instead of trusting garbage.
 */
export function parseServerFrame(raw: string): VoiceServerFrame {
  const json = JSON.parse(raw) as Record<string, unknown>
  const type = json['type']
  if (typeof type !== 'string' || !FRAME_TYPES.includes(type)) {
    throw new Error(`unknown voice frame type: ${String(type)}`)
  }
  const sessionId = requireString(json, 'sessionId')
  const messageId = requireString(json, 'messageId')
  const sequence = json['sequence']
  if (typeof sequence !== 'number' || !Number.isFinite(sequence) || sequence < 0) {
    throw new Error('voice frame sequence must be a non-negative number')
  }
  const envelope = { sessionId, messageId, sequence } as const
  switch (type) {
    case 'SESSION_STATE': {
      const state = requireString(json, 'state') as VoiceSessionState
      return { ...envelope, type, state, turnId: strOrNull(json, 'turnId') }
    }
    case 'ASR_PARTIAL':
    case 'ASR_FINAL':
      return { ...envelope, type, text: requireString(json, 'text') }
    case 'TOOL_EVENT': {
      const phase = requireString(json, 'phase')
      if (phase !== 'STARTED' && phase !== 'COMPLETED') {
        throw new Error('tool event phase must be STARTED or COMPLETED')
      }
      return {
        ...envelope,
        type,
        toolName: requireString(json, 'toolName'),
        phase,
        argumentSummary: typeof json['argumentSummary'] === 'string' ? json['argumentSummary'] : '',
      }
    }
    case 'ANSWER_DELTA':
      return { ...envelope, type, delta: String(json['delta'] ?? '') }
    case 'AUDIO_CHUNK': {
      const chunkSequence = json['chunkSequence']
      if (typeof chunkSequence !== 'number' || chunkSequence < 1) {
        throw new Error('audio chunk sequence starts at 1')
      }
      return {
        ...envelope,
        type,
        chunkSequence,
        sizeBytes: Number(json['sizeBytes'] ?? 0),
      }
    }
    default:
      return {
        ...envelope,
        type: 'ERROR',
        code: requireString(json, 'code') as ErrorFrame['code'],
        userMessage: requireString(json, 'userMessage'),
      }
  }
}

export function buildControlFrame(
  sessionId: string,
  messageId: string,
  sequence: number,
  type: VoiceClientControlType,
): ClientControlFrame {
  if (!sessionId.trim()) throw new Error('sessionId must not be blank')
  if (!messageId.trim()) throw new Error('messageId must not be blank')
  if (sequence < 0) throw new Error('sequence must not be negative')
  return { type, sessionId, messageId, sequence }
}

function requireString(json: Record<string, unknown>, field: string): string {
  const value = json[field]
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`voice frame field ${field} must be a non-empty string`)
  }
  return value
}

function strOrNull(json: Record<string, unknown>, field: string): string | null {
  const value = json[field]
  return typeof value === 'string' && value.length > 0 ? value : null
}
