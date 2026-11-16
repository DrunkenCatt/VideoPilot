import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { AnalysisJob, CitedEvidence, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import AnswerPage from './AnswerPage.vue'

const job = {
  jobId: '10000000-0000-0000-0000-000000000008',
  mediaId: '20000000-0000-0000-0000-000000000008',
  goal: '理解 Redis 数据结构',
  status: 'READY',
} as AnalysisJob
const session = { csrfToken: 'csrf-answer-page' } as Session
const citation = {
  citationId: '70000000-0000-0000-0000-000000000008',
  segmentId: '40000000-0000-0000-0000-000000000008',
  startMs: 72_000,
  endMs: 89_000,
  channel: 'ASR',
  quote: 'Redis 使用内存保存可重建数据。',
} as CitedEvidence

async function mountPage(get = vi.fn().mockResolvedValue(job), jobId?: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/answers/:jobId?', name: 'answers', component: { template: '<div />' } },
      { path: '/context/:jobId?', name: 'context', component: { template: '<div />' } },
    ],
  })
  await router.push(jobId ? `/answers/${jobId}` : '/answers')
  await router.isReady()
  const wrapper = mount(AnswerPage, {
    props: { jobId, session, jobsApi: { get } },
    global: {
      plugins: [router],
      stubs: {
        AnswerWorkspace: {
          props: ['job', 'csrfToken', 'initialQuestion'],
          emits: ['cite', 'unauthenticated'],
          template: '<section data-test="answer-workspace-stub">{{ job.jobId }} · {{ job.status }} · {{ csrfToken }} · {{ initialQuestion }}<button data-test="cite" @click="$emit(\'cite\', citation)">引用</button></section>',
          data: () => ({ citation }),
        },
      },
    },
  })
  return { wrapper, router }
}

describe('AnswerPage', () => {
  it('does not request answers without a valid route job id', async () => {
    const get = vi.fn()
    const { wrapper } = await mountPage(get)
    await flushPromises()

    expect(wrapper.get('[data-test="answers-empty"]').text()).toContain('选择一个视频')
    expect(get).not.toHaveBeenCalled()

    await wrapper.setProps({ jobId: 'not-a-job-id' })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('链接中的任务标识无效')
    expect(get).not.toHaveBeenCalled()
  })

  it('loads only the owner-scoped current job and delegates its answer history', async () => {
    const get = vi.fn().mockResolvedValue(job)
    const { wrapper } = await mountPage(get, job.jobId)
    await flushPromises()

    expect(get).toHaveBeenCalledWith(`/api/v1/analysis-jobs/${job.jobId}`)
    expect(wrapper.get('[data-test="answer-workspace-stub"]').text()).toContain(`${job.jobId} · READY · ${session.csrfToken}`)
  })

  it('passes a workspace question from the URL without submitting it automatically', async () => {
    const { wrapper, router } = await mountPage(vi.fn().mockResolvedValue(job), job.jobId)
    await flushPromises()
    await router.push(`/answers/${job.jobId}?question=${encodeURIComponent('MySQL 为什么是真源？')}`)
    await flushPromises()

    expect(wrapper.get('[data-test="answer-workspace-stub"]').text()).toContain('MySQL 为什么是真源？')
  })

  it('ignores a late job response after the route switches', async () => {
    let resolveFirst!: (value: AnalysisJob) => void
    const first = new Promise<AnalysisJob>((resolve) => { resolveFirst = resolve })
    const second = { ...job, jobId: '10000000-0000-0000-0000-000000000009' as AnalysisJob['jobId'] }
    const get = vi.fn().mockReturnValueOnce(first).mockResolvedValueOnce(second)
    const { wrapper } = await mountPage(get, job.jobId)

    await wrapper.setProps({ jobId: second.jobId })
    await flushPromises()
    resolveFirst(job)
    await flushPromises()

    expect(wrapper.get('[data-test="answer-workspace-stub"]').text()).toContain(second.jobId)
    expect(wrapper.text()).not.toContain(job.jobId)
  })

  it('routes citations to the same job context with a persistent seek target', async () => {
    const { wrapper, router } = await mountPage(vi.fn().mockResolvedValue(job), job.jobId)
    await flushPromises()

    await wrapper.get('[data-test="cite"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('context')
    expect(router.currentRoute.value.params.jobId).toBe(job.jobId)
    expect(router.currentRoute.value.query).toEqual({
      segmentId: citation.segmentId,
      fromMs: String(citation.startMs),
      toMs: String(citation.endMs),
    })
  })

  it('clears stale answers and expires the session on a 401', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce(job)
      .mockRejectedValueOnce(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' }))
    const { wrapper } = await mountPage(get, job.jobId)
    await flushPromises()
    expect(wrapper.find('[data-test="answer-workspace-stub"]').exists()).toBe(true)

    await wrapper.setProps({ jobId: '10000000-0000-0000-0000-000000000009' })
    await flushPromises()
    expect(wrapper.find('[data-test="answer-workspace-stub"]').exists()).toBe(false)
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
