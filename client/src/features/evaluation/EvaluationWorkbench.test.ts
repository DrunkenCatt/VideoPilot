import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { EvaluationDataset, EvaluationRun, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import EvaluationWorkbench from './EvaluationWorkbench.vue'

const session = { csrfToken: 'csrf', username: 'teacher' } as Session
const devDataset = {
  datasetId: '00000000-0000-0000-0000-000000000201', dataVersion: 'm5-dev-v1',
  datasetFingerprint: 'a'.repeat(64), split: 'DEV', status: 'FROZEN', retrievalCaseCount: 2,
  answerCaseCount: 0, videoCount: 1, approvedCaseCount: 2, totalCaseCount: 2,
  frozenAt: '2026-11-08T00:00:00Z',
} as EvaluationDataset
const draftTest = { ...devDataset, datasetId: '00000000-0000-0000-0000-000000000202', split: 'TEST', status: 'DRAFT', frozenAt: null } as EvaluationDataset
const completed = {
  runId: '00000000-0000-0000-0000-000000000211', datasetId: devDataset.datasetId,
  status: 'COMPLETED', completedCaseCount: 2, totalCaseCount: 2, executionFingerprint: 'b'.repeat(64),
  repetitionIndex: 1, repetitionCount: 2, createdAt: '2026-11-08T00:00:00Z',
  completedAt: '2026-11-08T00:01:00Z', baselineType: 'BASELINE_V1',
  executionMode: 'BASELINE', agentPolicyVersion: null,
} as EvaluationRun
const secondCompleted = { ...completed, runId: '00000000-0000-0000-0000-000000000212', repetitionIndex: 2,
  baselineType: 'AGENT_CANDIDATE', executionMode: 'AGENT', agentPolicyVersion: 'agent-policy-v1' } as EvaluationRun
const report = {
  runId: completed.runId, datasetId: devDataset.datasetId, datasetFingerprint: 'a'.repeat(64),
  executionFingerprint: 'b'.repeat(64),
  executionMode: 'BASELINE', agentPolicyVersion: null,
  versions: { codeCommit: 'c', pipelineVersion: 'p', promptVersion: 'pr', modelVersion: 'm', embeddingVersion: 'e', chunkVersion: 'ch', retrievalFormulaVersion: 'r', evidenceGateVersion: 'g', limitsVersion: 'l', configurationVersion: 'cfg' },
  metrics: [{ name: 'recallAt3', numerator: 1, denominator: 2, value: 0.5, exclusions: {} }],
  usage: { totalCalls: 1, inputUnits: 2, outputUnits: 3, latencyMs: 50, estimatedCost: null, currency: null, unavailablePriceCalls: 1, callOutcomes: {} },
  operations: { p95LatencyMs: 50, averageCost: null, unavailablePriceCalls: 1, pathDistribution: {} },
  series: {}, generatedAt: '2026-11-08T00:01:00Z',
}
const trace = [{ caseId: 'case-1', caseType: 'RETRIEVAL', outcome: 'COMPLETED', rank: 1, claimCount: 0, citationCount: 0, supportedClaimCount: 0, localizedCitationCount: 0, latencyMs: 10, tokenCount: 0, estimatedCost: null, errorCode: null }]

function api(overrides: Record<string, unknown> = {}) {
  return {
    listDatasets: vi.fn().mockResolvedValue([devDataset, draftTest]),
    listRuns: vi.fn().mockResolvedValue([]), createRuns: vi.fn(), getRun: vi.fn(),
    results: vi.fn().mockResolvedValue([]), report: vi.fn(),
    comparison: vi.fn().mockResolvedValue({
      baseRunId: completed.runId, candidateRunId: secondCompleted.runId,
      hardGatePassRateDelta: 0, ruleOneMet: false, ruleTwoMet: false,
      complexSubsetRuleEligible: false, m5GatesMet: false, costKnown: false,
      promotionEligible: false, reason: 'M5_GATES_NOT_MET',
    }), ...overrides,
  }
}

describe('EvaluationWorkbench', () => {
  it('renders dataset review state and disables an unfrozen test run', async () => {
    const wrapper = mount(EvaluationWorkbench, { props: { session, api: api() } })
    await flushPromises()
    expect(wrapper.text()).toContain('m5-dev-v1')
    await wrapper.get('[data-test="dataset-1"]').trigger('click')
    expect(wrapper.get('[data-test="start-run"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('TEST 数据集必须冻结并全部批准')
  })

  it('defaults agent candidates to the frozen runtime policy version', async () => {
    const wrapper = mount(EvaluationWorkbench, { props: { session, api: api() } })
    await flushPromises()

    await wrapper.get('.candidate-controls select').setValue('AGENT')

    expect((wrapper.get('.candidate-controls input').element as HTMLInputElement).value)
      .toBe('agent-policy-v5-partial')
  })

  it('ignores a late run response after another dataset is selected', async () => {
    let resolveRuns!: (runs: EvaluationRun[]) => void
    const delayed = new Promise<EvaluationRun[]>((resolve) => { resolveRuns = resolve })
    const mockApi = api({ listRuns: vi.fn().mockReturnValueOnce(delayed).mockResolvedValueOnce([]) })
    const wrapper = mount(EvaluationWorkbench, { props: { session, api: mockApi } })
    await flushPromises()
    await wrapper.get('[data-test="dataset-1"]').trigger('click')
    resolveRuns([{ runId: 'late' } as EvaluationRun])
    await flushPromises()
    expect(wrapper.text()).not.toContain('late')
  })

  it('shows empty, partial and authentication-expired states without exposing raw content', async () => {
    const running = { runId: 'run-1', datasetId: devDataset.datasetId, status: 'RUNNING', completedCaseCount: 1, totalCaseCount: 2, executionFingerprint: 'b'.repeat(64), repetitionIndex: 1, repetitionCount: 2, createdAt: '2026-11-08T00:00:00Z', completedAt: null, baselineType: 'BASELINE_V1' } as EvaluationRun
    const emit = vi.fn()
    const wrapper = mount(EvaluationWorkbench, { props: { session, api: api({ listRuns: vi.fn().mockResolvedValue([running]) }) }, attrs: { onUnauthenticated: emit } })
    await flushPromises()
    expect(wrapper.text()).toContain('1 / 2')
    expect(wrapper.text()).toContain('需要同一冻结数据集上的已完成基线与 Agent 候选')
    expect(wrapper.text()).not.toMatch(/question|transcript|promptText|rawResponse/)
  })

  it('emits the shared session-expired boundary on a 401 response', async () => {
    const expired = new ApiClientError(401, {
      code: 'AUTHENTICATION_REQUIRED', message: 'expired', retryable: false, requestId: 'request-1',
    })
    const wrapper = mount(EvaluationWorkbench, {
      props: { session, api: api({ listDatasets: vi.fn().mockRejectedValue(expired) }) },
    })
    await flushPromises()
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('会话已过期')
  })

  it('runs, traces, compares, exports and refreshes through the redacted APIs', async () => {
    const mockApi = api({
      listRuns: vi.fn().mockResolvedValue([completed, secondCompleted]),
      createRuns: vi.fn().mockResolvedValue([completed, secondCompleted]),
      results: vi.fn().mockResolvedValue(trace), report: vi.fn().mockResolvedValue(report),
    })
    const createObjectURL = vi.spyOn(globalThis.URL, 'createObjectURL').mockReturnValue('blob:report')
    const revokeObjectURL = vi.spyOn(globalThis.URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const wrapper = mount(EvaluationWorkbench, { props: { session, api: mockApi } })
    await flushPromises()
    expect(wrapper.get('main').classes()).toContain('product-page')
    expect(wrapper.get('[data-test="evaluation-metric-band"]').text()).toContain('recallAt3')
    expect(wrapper.get('[data-test="evaluation-report-panel"]')).toBeTruthy()
    expect(wrapper.get('[data-test="evaluation-gate-panel"]').text()).toContain('发布硬门')
    for (const input of wrapper.findAll('.version-grid input')) await input.setValue('frozen-v1')
    await wrapper.get('[data-test="start-run"]').trigger('click')
    await flushPromises()
    expect(mockApi.createRuns).toHaveBeenCalledWith(devDataset.datasetId,
      expect.objectContaining({ repetitions: 2 }), expect.any(String), 'csrf')
    expect(wrapper.text()).toContain('case-1')
    expect(wrapper.text()).toContain('recallAt3')
    const selects = wrapper.findAll('.compare-card select')
    await selects[0]!.setValue(completed.runId)
    await selects[1]!.setValue(secondCompleted.runId)
    await wrapper.get('.compare-card button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('0.000')
    await wrapper.get('.report-card .text-button').trigger('click')
    expect(createObjectURL).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:report')
    await wrapper.get('[data-test="page-actions"] button').trigger('click')
    await flushPromises()
    expect(mockApi.listRuns).toHaveBeenCalledTimes(2)
  })

  it('renders the empty dataset and safe connection-error states', async () => {
    const empty = mount(EvaluationWorkbench, { props: { session, api: api({ listDatasets: vi.fn().mockResolvedValue([]) }) } })
    await flushPromises()
    expect(empty.text()).toContain('尚无数据集')
    const retrying = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce([devDataset])
    const failed = mount(EvaluationWorkbench, { props: { session, api: api({ listDatasets: retrying }) } })
    await flushPromises()
    expect(failed.get('[role="alert"]').text()).toContain('暂时无法读取')
    await failed.get('[data-test="page-actions"] button').trigger('click')
    await flushPromises()
    expect(failed.text()).toContain('m5-dev-v1')
    expect(retrying).toHaveBeenCalledTimes(2)
  })
})
