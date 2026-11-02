import {
  API,
  type AnalysisJob,
  type CreateAnalysisJobRequest,
  type JobEvent,
  type PageResponse,
} from './contracts'
import { ApiClientError } from './session'

type Request = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>
type Sleep = (milliseconds: number) => Promise<void>

interface FollowEventsOptions {
  initialLastEventId?: number
  signal?: AbortSignal
  onEvent: (event: JobEvent) => void
}

interface ParsedEvent {
  id?: number
  name: string
  data: unknown
}

const defaultSleep: Sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

async function errorFrom(response: Response): Promise<ApiClientError> {
  try {
    return new ApiClientError(response.status, await response.json())
  } catch {
    return new ApiClientError(response.status)
  }
}

function parseBlock(block: string): ParsedEvent | null {
  let id: number | undefined
  let name = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('id:')) id = Number(line.slice(3).trim())
    if (line.startsWith('event:')) name = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (data.length === 0) return null
  return { id, name, data: JSON.parse(data.join('\n')) as unknown }
}

function isJobEvent(value: unknown): value is JobEvent {
  if (typeof value !== 'object' || value === null) return false
  const event = value as Partial<JobEvent>
  return typeof event.jobId === 'string'
    && Number.isSafeInteger(event.sequence)
    && Number(event.sequence) > 0
    && typeof event.eventType === 'string'
    && typeof event.status === 'string'
    && (event.stage === null || typeof event.stage === 'string')
    && typeof event.message === 'string'
    && typeof event.occurredAt === 'string'
    && typeof event.terminal === 'boolean'
}

async function readEventStream(
  response: Response,
  onEvent: (event: JobEvent) => void,
  initialCursor: number,
): Promise<{ cursor: number; terminal: boolean }> {
  if (!response.body) throw new ApiClientError(response.status)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let cursor = initialCursor
  let terminal = false

  const consume = (block: string) => {
    const parsed = parseBlock(block)
    if (!parsed) return
    if (parsed.name === 'error') {
      throw new ApiClientError(response.status, parsed.data as Record<string, unknown>)
    }
    if (parsed.name !== 'job-event' || !isJobEvent(parsed.data)) {
      throw new ApiClientError(response.status, { message: '任务事件格式无效。' })
    }
    if (parsed.id !== parsed.data.sequence || parsed.data.sequence <= cursor) return
    cursor = parsed.data.sequence
    terminal = parsed.data.terminal
    onEvent(parsed.data)
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      consume(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
  if (buffer.trim()) consume(buffer.trim())
  return { cursor, terminal }
}

export function createJobsApi(request: Request = fetch, sleep: Sleep = defaultSleep) {
  return {
    async list(): Promise<PageResponse<AnalysisJob>> {
      const response = await request(`${API.analysisJobs}?page=1&pageSize=20`, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as PageResponse<AnalysisJob>
    },

    async get(selfLink: string): Promise<AnalysisJob> {
      const response = await request(selfLink, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as AnalysisJob
    },

    async submit(
      input: CreateAnalysisJobRequest,
      idempotencyKey: string,
      csrfToken: string,
    ): Promise<AnalysisJob> {
      const response = await request(API.analysisJobs, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          [API.idempotencyKeyHeader]: idempotencyKey,
          'X-CSRF-Token': csrfToken,
        },
        body: JSON.stringify(input),
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as AnalysisJob
    },

    async cancel(cancellationsLink: string, csrfToken: string): Promise<AnalysisJob> {
      const response = await request(cancellationsLink, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { Accept: 'application/json', 'X-CSRF-Token': csrfToken },
      })
      if (!response.ok) throw await errorFrom(response)
      return (await response.json()) as AnalysisJob
    },

    async followEvents(eventsLink: string, options: FollowEventsOptions): Promise<number> {
      let cursor = options.initialLastEventId ?? 0
      const delays = [400, 800, 1_600]
      for (let connection = 0; connection <= delays.length; connection += 1) {
        try {
          const headers: Record<string, string> = { Accept: 'text/event-stream' }
          if (cursor > 0) headers[API.lastEventIdHeader] = String(cursor)
          const response = await request(eventsLink, {
            method: 'GET',
            credentials: 'same-origin',
            headers,
            signal: options.signal,
          })
          if (!response.ok && !response.headers.get('Content-Type')?.includes('text/event-stream')) {
            throw await errorFrom(response)
          }
          const result = await readEventStream(response, options.onEvent, cursor)
          cursor = Math.max(cursor, result.cursor)
          if (result.terminal) return cursor
        } catch (error) {
          if (options.signal?.aborted) return cursor
          if (error instanceof ApiClientError && error.status < 500 && !error.retryable) throw error
          if (connection === delays.length) throw error
        }
        if (connection < delays.length) await sleep(delays[connection]!)
      }
      return cursor
    },
  }
}
