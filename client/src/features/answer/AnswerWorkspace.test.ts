import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type {
  AnalysisJob,
  AnswerPageResponse,
  AnswerResponse,
  CitedEvidence,
  Session,
} from '../../api/contracts'
import type { AnswersApi } from '../../api/answers'
import { ApiClientError } from '../../api/session'
import AnswerWorkspace from './AnswerWorkspace.vue'

const session = { csrfToken: 'csrf-answer' } as Session
const job = {
  jobId: '10000000-0000-0000-0000-000000000008',
  status: 'READY',
} as AnalysisJob
const citation: CitedEvidence = {
  citationId: '70000000-0000-0000-0000-000000000008' as CitedEvidence['citationId'],
  segmentId: '40000000-0000-0000-0000-000000000008' as CitedEvidence['segmentId'],
  startMs: 72_000,
  endMs: 89_000,
  channel: 'ASR',
  quote: 'Redis 的 String 可以保存字符串、整数或者浮点数。',
}
const answered: AnswerResponse = {
  answerId: '60000000-0000-0000-0000-000000000008' as AnswerResponse['answerId'],
  status: 'ANSWERED',
  question: 'Redis 的 String 能保存什么？',
  claims: [{
    claimId: '65000000-0000-0000-0000-000000000008' as AnswerResponse['claims'][number]['claimId'],
    text: 'String 可以保存字符串、整数或浮点数。',
    citations: [citation],
  }],
  refusalReason: null,
  degradedChannels: ['VECTOR_STORE'],
  versions: {
    promptVersion: 'answer-evidence-v1',
    modelVersion: 'deepseek-ai/DeepSeek-V4-Flash',
    embeddingVersion: 'BAAI/bge-m3',
    retrievalFormulaVersion: 'hybrid-v1',
    chunkVersion: 'chunk-v1',
  },
  createdAt: '2026-11-06T08:00:00Z',
}
const refused: AnswerResponse = {
  ...answered,
  answerId: '60000000-0000-0000-0000-000000000009' as AnswerResponse['answerId'],
  status: 'REFUSED',
  question: '讲师最喜欢哪种咖啡？',
  claims: [],
  refusalReason: 'NO_SUFFICIENT_EVIDENCE',
  degradedChannels: ['SEMANTIC', 'VECTOR_STORE', 'OCR'],
}

function page(data: AnswerResponse[] = []): AnswerPageResponse {
  return { data, nextCursor: null, hasMore: false }
}

function answersApi(overrides: Partial<AnswersApi> = {}): AnswersApi {
  return {
    list: vi.fn().mockResolvedValue(page()),
    ask: vi.fn().mockResolvedValue(answered),
    ...overrides,
  }
}

afterEach(() => vi.useRealTimers())

describe('AnswerWorkspace', () => {
  it('restores published claims and emits a keyboard-operable citation seek', async () => {
    const api = answersApi({ list: vi.fn().mockResolvedValue(page([answered])) })
    const wrapper = mount(AnswerWorkspace, { props: { job, csrfToken: session.csrfToken, api } })
    await flushPromises()

    expect(wrapper.get('[data-test="answer-claim"]').text()).toContain('String 可以保存')
    const evidence = wrapper.get('[data-test="cited-evidence"]')
    expect(evidence.text()).toContain('ASR · 01:12')
    expect(evidence.text()).toContain(citation.quote)

    await evidence.trigger('click')
    expect(wrapper.emitted('cite')?.[0]).toEqual([citation])
  })

  it('announces pending state and prevents duplicate submissions', async () => {
    let resolve!: (value: AnswerResponse) => void
    const pending = new Promise<AnswerResponse>((done) => { resolve = done })
    const ask = vi.fn().mockReturnValue(pending)
    const wrapper = mount(AnswerWorkspace, { props: { job, csrfToken: session.csrfToken, api: answersApi({ ask }) } })
    await flushPromises()

    await wrapper.get('[data-test="answer-question"]').setValue(answered.question)
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await wrapper.get('[data-test="ask-form"]').trigger('submit')

    expect(wrapper.get('[data-test="answer-pending"]').text()).toContain('正在检索并核验证据')
    expect(wrapper.get('[data-test="ask-submit"]').attributes('disabled')).toBeDefined()
    expect(ask).toHaveBeenCalledTimes(1)

    resolve(answered)
    await flushPromises()
    expect(wrapper.get('[data-test="answer-claim"]').text()).toContain('String 可以保存')
  })

  it('distinguishes refusals from semantic, vector-store and OCR degradation', async () => {
    const wrapper = mount(AnswerWorkspace, {
      props: { job, csrfToken: session.csrfToken, api: answersApi({ list: vi.fn().mockResolvedValue(page([refused])) }) },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="answer-refused"]').text()).toContain('视频证据不足')
    const degradation = wrapper.get('[data-test="answer-degraded"]').text()
    expect(degradation).toContain('语义召回不可用')
    expect(degradation).toContain('向量库不可用')
    expect(degradation).toContain('OCR 证据不完整')
    expect(wrapper.find('[data-test="answer-claim"]').exists()).toBe(false)
  })

  it('reports a client timeout separately from a backend dependency failure', async () => {
    vi.useFakeTimers()
    const timedOut = vi.fn((_jobId, _input, _key, _csrf, signal?: AbortSignal) => new Promise<AnswerResponse>(
      (_resolve, reject) => signal?.addEventListener('abort', () => reject(new DOMException('timeout', 'AbortError'))),
    ))
    const wrapper = mount(AnswerWorkspace, {
      props: { job, csrfToken: session.csrfToken, api: answersApi({ ask: timedOut }), timeoutMs: 50 },
    })
    await flushPromises()
    await wrapper.get('[data-test="answer-question"]').setValue(answered.question)
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await vi.advanceTimersByTimeAsync(50)
    await flushPromises()

    expect(wrapper.get('[data-test="answer-error"]').text()).toContain('请求超时')

    vi.useRealTimers()
    await wrapper.setProps({
      api: answersApi({
        ask: vi.fn().mockRejectedValue(new ApiClientError(503, {
          code: 'DEPENDENCY_UNAVAILABLE', retryable: true, requestId: 'request-2',
        })),
      }),
    })
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-test="answer-error"]').text()).toContain('问答依赖暂时不可用')
  })

  it('emits authentication expiry instead of presenting it as an answer failure', async () => {
    const api = answersApi({
      list: vi.fn().mockRejectedValue(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' })),
    })
    const wrapper = mount(AnswerWorkspace, { props: { job, csrfToken: session.csrfToken, api } })
    await flushPromises()

    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
    expect(wrapper.get('[data-test="answer-error"]').text()).toContain('会话已过期')
  })

  it('does not let a late answer from the previous job pollute the selected job', async () => {
    let resolve!: (value: AnswerResponse) => void
    const pending = new Promise<AnswerResponse>((done) => { resolve = done })
    const api = answersApi({ ask: vi.fn().mockReturnValue(pending) })
    const wrapper = mount(AnswerWorkspace, { props: { job, csrfToken: session.csrfToken, api } })
    await flushPromises()
    await wrapper.get('[data-test="answer-question"]').setValue(answered.question)
    await wrapper.get('[data-test="ask-form"]').trigger('submit')

    const nextJob = {
      ...job,
      jobId: '10000000-0000-0000-0000-000000000009' as AnalysisJob['jobId'],
    }
    await wrapper.setProps({ job: nextJob })
    await flushPromises()
    resolve(answered)
    await flushPromises()

    expect(wrapper.find('[data-test="answer-claim"]').exists()).toBe(false)
    expect(api.list).toHaveBeenLastCalledWith(nextJob.jobId)
  })
})
