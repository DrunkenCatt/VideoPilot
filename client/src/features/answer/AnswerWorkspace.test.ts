import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type {
  AgentRun,
  GoalAnalysisResult,
  AnalysisJob,
  AnswerPageResponse,
  AnswerResponse,
  CitedEvidence,
  Session,
} from '../../api/contracts'
import type { AgentsApi } from '../../api/agents'
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
  missingRequirements: [],
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
  missingRequirements: [],
  degradedChannels: ['SEMANTIC', 'VECTOR_STORE', 'OCR'],
}
const partial: AnswerResponse = {
  ...answered,
  answerId: '60000000-0000-0000-0000-000000000011' as AnswerResponse['answerId'],
  status: 'PARTIALLY_ANSWERED',
  question: 'String 有哪些用法？',
  missingRequirements: ['视频未说明计数器用法'],
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

function run(overrides: Partial<AgentRun> = {}): AgentRun {
  return {
    runId: '80000000-0000-0000-0000-000000000008' as AgentRun['runId'],
    jobId: job.jobId,
    goal: answered.question,
    status: 'REQUESTED',
    versions: {
      policyVersion: 'agent-policy-v1', promptVersion: 'prompt-v1', modelVersion: 'model-v1',
      embeddingVersion: 'embedding-v1', retrievalFormulaVersion: 'formula-v1',
      evidenceGateVersion: 'gate-v1', limitsVersion: 'limits-v1',
    },
    budget: {
      maxSteps: 6, maxAnswerAttempts: 2, maxInputTokens: 12_000, maxOutputTokens: 6_000,
      maxKnownCost: 1, deadlineAt: '2026-11-11T09:05:00Z',
    },
    usage: { steps: 0, answerAttempts: 0, inputTokens: 0, outputTokens: 0, knownCost: 0, unknownCostCalls: 0 },
    terminationReason: null,
    createdAt: '2026-11-11T09:00:00Z',
    completedAt: null,
    steps: [],
    toolCalls: [],
    ...overrides,
  }
}

function agentsApi(overrides: Partial<AgentsApi> = {}): AgentsApi {
  return {
    create: vi.fn().mockResolvedValue(run()),
    get: vi.fn().mockResolvedValue(run({ status: 'COMPLETED', terminationReason: 'COMPLETED' })),
    getGoalAnalysisResult: vi.fn().mockResolvedValue({ state: 'DISABLED', run: null, answer: null }),
    ...overrides,
  }
}

afterEach(() => {
  vi.useRealTimers()
  globalThis.history.replaceState(null, '', '/')
})

describe('AnswerWorkspace', () => {
  it('keeps the experimental Agent entry absent by default', async () => {
    const wrapper = mount(AnswerWorkspace, { props: { job, csrfToken: session.csrfToken, api: answersApi() } })
    await flushPromises()

    expect(wrapper.find('[data-test="agent-toggle"]').exists()).toBe(false)
  })

  it('shows the evidence-bound goal result before manual questions without duplicating it in history', async () => {
    const result = {
      state: 'ANSWERED',
      run: run({ status: 'COMPLETED', goal: '总结课程核心命令' }),
      answer: {
        ...answered,
        answerId: '60000000-0000-0000-0000-000000000010' as AnswerResponse['answerId'],
        question: '总结课程核心命令',
      },
    } satisfies GoalAnalysisResult
    const agentApi = agentsApi({ getGoalAnalysisResult: vi.fn().mockResolvedValue(result) })
    const api = answersApi({ list: vi.fn().mockResolvedValue(page([result.answer, answered])) })
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job: { ...job, goal: '总结课程核心命令' }, csrfToken: session.csrfToken,
        api, agentApi, agentAvailable: true, goalAnalysisAvailable: true,
      },
    })
    await flushPromises()

    expect(agentApi.getGoalAnalysisResult).toHaveBeenCalledWith(job.jobId)
    expect(wrapper.get('[data-test="goal-analysis-result"]').text()).toContain('目标分析结果')
    expect(wrapper.get('[data-test="goal-analysis-result"]').text()).toContain('String 可以保存')
    expect(wrapper.findAll('[data-test="answer-claim"]')).toHaveLength(1)

    await wrapper.get('[data-test="goal-cited-evidence"]').trigger('click')
    expect(wrapper.emitted('cite')?.[0]).toEqual([citation])
  })

  it('separates verified video claims from gaps for manual and goal partial answers', async () => {
    const goalPartial = { ...partial, answerId: '60000000-0000-0000-0000-000000000012' as AnswerResponse['answerId'] }
    const result = {
      state: 'PARTIALLY_ANSWERED',
      run: run({ status: 'COMPLETED', goal: goalPartial.question, terminationReason: 'PARTIALLY_ANSWERED' }),
      answer: goalPartial,
    } satisfies GoalAnalysisResult
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job: { ...job, goal: goalPartial.question }, csrfToken: session.csrfToken,
        api: answersApi({ list: vi.fn().mockResolvedValue(page([partial])) }),
        agentApi: agentsApi({ getGoalAnalysisResult: vi.fn().mockResolvedValue(result) }),
        agentAvailable: true, goalAnalysisAvailable: true,
      },
    })
    await flushPromises()

    const goalResult = wrapper.get('[data-test="goal-analysis-result"]')
    expect(goalResult.text()).toContain('视频可以确认')
    expect(goalResult.text()).toContain('视频证据不足以确认')
    expect(goalResult.text()).toContain('视频未说明计数器用法')
    expect(wrapper.get('[data-test="answer-partial"]').text()).toContain('视频证据不足以确认')
    expect(wrapper.get('[data-test="answer-partial"]').text()).toContain('视频未说明计数器用法')
    expect(wrapper.findAll('[data-test="answer-claim"]')).toHaveLength(1)
  })

  it('polls a pending goal result until it reaches a terminal state', async () => {
    vi.useFakeTimers()
    const getGoalAnalysisResult = vi.fn()
      .mockResolvedValueOnce({ state: 'PENDING', run: null, answer: null })
      .mockResolvedValueOnce({
        state: 'REFUSED', run: run({ status: 'REFUSED', terminationReason: 'NO_SUFFICIENT_EVIDENCE' }), answer: null,
      })
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job: { ...job, goal: '总结不存在的主题' }, csrfToken: session.csrfToken,
        api: answersApi(), agentApi: agentsApi({ getGoalAnalysisResult }),
        agentAvailable: true, goalAnalysisAvailable: true, agentPollMs: 1,
      },
    })
    await flushPromises()
    expect(wrapper.get('[data-test="goal-analysis-result"]').text()).toContain('等待后台创建')

    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(getGoalAnalysisResult).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test="goal-analysis-result"]').text()).toContain('视频证据不足')
  })

  it('polls an enabled Agent run and reuses the existing verified answer list', async () => {
    vi.useFakeTimers()
    const agentApi = agentsApi()
    const api = answersApi({
      list: vi.fn()
        .mockResolvedValueOnce(page())
        .mockResolvedValueOnce(page([answered])),
    })
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job, csrfToken: session.csrfToken, api, agentApi,
        agentAvailable: true, agentPollMs: 1,
      },
    })
    await flushPromises()
    await wrapper.get('[data-test="agent-toggle"]').setValue(true)
    await wrapper.get('[data-test="answer-question"]').setValue(answered.question)
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()

    expect(agentApi.create).toHaveBeenCalledWith(job.jobId, answered.question, expect.any(String), session.csrfToken)
    expect(agentApi.get).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="agent-termination"]').text()).toContain('确定性证据门')
    expect(wrapper.get('[data-test="answer-claim"]').text()).toContain('String 可以保存')
  })

  it('renders a redacted planner and critic repair trace with the model call count', async () => {
    const repaired = run({
      status: 'COMPLETED',
      terminationReason: 'COMPLETED',
      usage: { steps: 6, answerAttempts: 2, inputTokens: 120, outputTokens: 40, knownCost: 0.01, unknownCostCalls: 0 },
      steps: [
        { stepNumber: 1, action: 'PLAN', observationSummary: 'PLAN:RETRIEVE;facts:2;expected:5L2_55So5Zy65pmv', resultReference: null, completedAt: '2026-11-11T09:00:01Z' },
        { stepNumber: 2, action: 'RETRIEVE', observationSummary: 'EVIDENCE:single', resultReference: null, completedAt: '2026-11-11T09:00:02Z' },
        { stepNumber: 3, action: 'GENERATE_AND_VERIFY', observationSummary: 'ANSWER:REFUSED', resultReference: null, completedAt: '2026-11-11T09:00:03Z' },
        { stepNumber: 4, action: 'CRITIQUE', observationSummary: 'CRITIQUE:RETRIEVE;missing:5L2_55So5Zy65pmv;unsupported:', resultReference: null, completedAt: '2026-11-11T09:00:04Z' },
        { stepNumber: 5, action: 'RETRIEVE', observationSummary: 'EVIDENCE:multiple', resultReference: null, completedAt: '2026-11-11T09:00:05Z' },
        { stepNumber: 6, action: 'GENERATE_AND_VERIFY', observationSummary: 'ANSWER:ANSWERED', resultReference: null, completedAt: '2026-11-11T09:00:06Z' },
      ],
    })
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job, csrfToken: session.csrfToken, api: answersApi(),
        agentApi: agentsApi({ create: vi.fn().mockResolvedValue(repaired) }), agentAvailable: true,
      },
    })
    await flushPromises()
    await wrapper.get('[data-test="agent-toggle"]').setValue(true)
    await wrapper.get('[data-test="answer-question"]').setValue(answered.question)
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-test="agent-budget"]').text()).toContain('模型调用4 次')
    const trace = wrapper.get('[data-test="agent-trace"]').text()
    expect(trace).toContain('计划检索 · 预期事实 2 项')
    expect(trace).toContain('定向补检 · 缺失：使用场景')
    expect(trace).toContain('补充检索新增证据：多条')
    expect(trace).not.toContain('5L2_55So5Zy65pmv')
    expect(wrapper.text()).not.toContain('prompt')
  })

  it('shows unknown cost and clarification without exposing parameters or raw responses', async () => {
    const clarified = run({
      status: 'REFUSED',
      terminationReason: 'NEEDS_CLARIFICATION',
      usage: { steps: 1, answerAttempts: 0, inputTokens: 12, outputTokens: 0, knownCost: 0, unknownCostCalls: 1 },
      steps: [{
        stepNumber: 1, action: 'RETRIEVE', observationSummary: 'EVIDENCE:none',
        resultReference: null, completedAt: '2026-11-11T09:00:01Z',
      }],
      toolCalls: [{
        toolCallId: '90000000-0000-0000-0000-000000000008', stepNumber: 1,
        toolName: 'RETRIEVE', status: 'UNKNOWN', parameterSummary: 'SECRET_PROMPT',
        observationSummary: 'UNKNOWN_OUTCOME', resultReference: null, latencyMs: 10,
        startedAt: '2026-11-11T09:00:00Z', completedAt: '2026-11-11T09:00:01Z',
      }],
    })
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job, csrfToken: session.csrfToken, api: answersApi(),
        agentApi: agentsApi({ create: vi.fn().mockResolvedValue(clarified) }), agentAvailable: true,
      },
    })
    await flushPromises()
    await wrapper.get('[data-test="agent-toggle"]').setValue(true)
    await wrapper.get('[data-test="answer-question"]').setValue('为什么？')
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-test="agent-termination"]').text()).toContain('补充更具体')
    expect(wrapper.get('[data-test="agent-budget"]').text()).toContain('未知费用调用 1 次')
    expect(wrapper.get('[data-test="agent-trace"]').text()).toContain('不会自动重放')
    expect(wrapper.text()).not.toContain('SECRET_PROMPT')
  })

  it('reloads the persisted Agent trace referenced by the page URL', async () => {
    const completed = run({
      status: 'REFUSED',
      terminationReason: 'NO_SUFFICIENT_EVIDENCE',
      usage: { steps: 1, answerAttempts: 0, inputTokens: 9, outputTokens: 0, knownCost: 0, unknownCostCalls: 1 },
    })
    globalThis.history.replaceState(null, '', `/?agentRun=${completed.runId}`)
    const agentApi = agentsApi({ get: vi.fn().mockResolvedValue(completed) })

    const wrapper = mount(AnswerWorkspace, {
      props: { job, csrfToken: session.csrfToken, api: answersApi(), agentApi, agentAvailable: true },
    })
    await flushPromises()

    expect(agentApi.get).toHaveBeenCalledWith(completed.runId)
    expect(wrapper.get('[data-test="agent-run"]').text()).toContain('视频证据不足')
    expect(wrapper.get('[data-test="agent-budget"]').text()).toContain('未知费用调用 1 次')
  })

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

  it('does not let a late Agent poll overwrite the selected job or replay URL', async () => {
    vi.useFakeTimers()
    let resolve!: (value: AgentRun) => void
    const pending = new Promise<AgentRun>((done) => { resolve = done })
    const agentApi = agentsApi({ get: vi.fn().mockReturnValue(pending) })
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job, csrfToken: session.csrfToken, api: answersApi(), agentApi,
        agentAvailable: true, agentPollMs: 1,
      },
    })
    await flushPromises()
    await wrapper.get('[data-test="agent-toggle"]').setValue(true)
    await wrapper.get('[data-test="answer-question"]').setValue(answered.question)
    await wrapper.get('[data-test="ask-form"]').trigger('submit')
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()

    const nextJob = {
      ...job,
      jobId: '10000000-0000-0000-0000-000000000009' as AnalysisJob['jobId'],
    }
    await wrapper.setProps({ job: nextJob })
    await flushPromises()
    resolve(run({ status: 'FAILED', terminationReason: 'UNKNOWN_COST' }))
    await flushPromises()

    expect(wrapper.find('[data-test="agent-run"]').exists()).toBe(false)
    expect(globalThis.location.search).not.toContain('agentRun=')
  })

  it('does not read or submit answers before the job is ready', async () => {
    const api = answersApi()
    const building = { ...job, status: 'INDEXING' as const }
    const wrapper = mount(AnswerWorkspace, { props: { job: building, csrfToken: session.csrfToken, api } })
    await flushPromises()

    expect(api.list).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('视频索引尚未就绪')
  })

  it.each([
    [new TypeError('connection failed'), '无法连接本地问答服务'],
    [new ApiClientError(500, { code: 'INTERNAL_ERROR', retryable: true }), '问答服务暂时不可用'],
    [new ApiClientError(400, { code: 'INVALID_REQUEST', retryable: false }), '问题未提交'],
  ])('presents remaining transport failures without calling them refusals', async (failure, expected) => {
    const wrapper = mount(AnswerWorkspace, {
      props: {
        job,
        csrfToken: session.csrfToken,
        api: answersApi({ list: vi.fn().mockRejectedValue(failure) }),
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="answer-error"]').text()).toContain(expected)
    expect(wrapper.find('[data-test="answer-refused"]').exists()).toBe(false)
  })
})
