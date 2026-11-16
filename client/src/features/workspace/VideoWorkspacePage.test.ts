import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { AnalysisJob, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import VideoWorkspacePage from './VideoWorkspacePage.vue'

const session = { csrfToken: 'csrf-workspace' } as Session
const readyJob = {
  jobId: '10000000-0000-0000-0000-000000000008',
  mediaId: '20000000-0000-0000-0000-000000000008',
  goal: '梳理视频中的可靠性架构',
  status: 'READY',
  currentStage: 'INDEXING',
  failure: null,
} as AnalysisJob

async function mountPage(jobId?: string, get = vi.fn().mockResolvedValue(readyJob)) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/workspace/:jobId?', name: 'workspace', component: { template: '<div />' } },
      { path: '/answers/:jobId?', name: 'answers', component: { template: '<div />' } },
      { path: '/library', name: 'library', component: { template: '<div />' } },
      { path: '/analysis', name: 'analysis', component: { template: '<div />' } },
      { path: '/analysis/:jobId', name: 'analysis-detail', component: { template: '<div />' } },
    ],
  })
  await router.push(jobId ? `/workspace/${jobId}` : '/workspace')
  await router.isReady()
  const wrapper = mount(VideoWorkspacePage, {
    props: { jobId, session, jobsApi: { get } },
    global: {
      plugins: [router],
      stubs: {
        ProductPageHeader: true,
        WorkspaceContextPanel: {
          props: ['job'],
          template: '<section data-test="workspace-context-panel"><div data-test="workspace-player-panel">{{ job.jobId }}</div><div data-test="workspace-evidence-panel">{{ job.status }}</div></section>',
        },
      },
    },
  })
  return { wrapper, router }
}

describe('VideoWorkspacePage', () => {
  it('shows a real empty state without requesting a guessed job', async () => {
    const get = vi.fn()
    const { wrapper } = await mountPage(undefined, get)
    await flushPromises()

    expect(wrapper.get('[data-test="workspace-empty"]').text()).toContain('选择一个视频')
    expect(wrapper.find('a[href="/library"]').exists()).toBe(true)
    expect(get).not.toHaveBeenCalled()
  })

  it('assembles the READY player timeline and routes a keyboard-submitted question', async () => {
    const get = vi.fn().mockResolvedValue(readyJob)
    const { wrapper, router } = await mountPage(readyJob.jobId, get)
    await flushPromises()

    expect(get).toHaveBeenCalledWith(`/api/v1/analysis-jobs/${readyJob.jobId}`)
    expect(wrapper.get('[data-test="workspace-product-grid"]')).toBeTruthy()
    expect(wrapper.get('[data-test="workspace-player-panel"]').text()).toContain(readyJob.jobId)
    expect(wrapper.get('[data-test="workspace-evidence-panel"]').text()).toContain('READY')
    expect(wrapper.get('[data-test="workspace-question-card"]')).toBeTruthy()
    expect(wrapper.get('[data-test="workspace-progress-card"]')).toBeTruthy()
    expect(wrapper.get('[data-test="workspace-goal"]').text()).toContain(readyJob.goal)

    await wrapper.get('[data-test="workspace-question"]').setValue('MySQL 为什么是真源？')
    await wrapper.get('[data-test="workspace-question-form"]').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('answers')
    expect(router.currentRoute.value.params.jobId).toBe(readyJob.jobId)
    expect(router.currentRoute.value.query.question).toBe('MySQL 为什么是真源？')
  })

  it.each([
    ['CONTEXT_BUILDING', null, '正在构建视频上下文'],
    ['FAILED', { code: 'ASR_EMPTY', retryable: false, summary: '未识别到可用语音。' }, '未识别到可用语音'],
  ] as const)('keeps %s state honest and disables questions', async (status, failure, expected) => {
    const current = { ...readyJob, status, currentStage: 'CONTEXT_BUILDING', failure } as AnalysisJob
    const { wrapper } = await mountPage(current.jobId, vi.fn().mockResolvedValue(current))
    await flushPromises()

    expect(wrapper.get('[data-test="workspace-status"]').text()).toContain(expected)
    expect(wrapper.get<HTMLButtonElement>('[data-test="workspace-ask"]').element.disabled).toBe(true)
    expect(wrapper.get('[data-test="workspace-evidence-panel"]').text()).toContain(status)
  })

  it('ignores a late response and clears stale workspace data on authentication expiry', async () => {
    let resolveFirst!: (value: AnalysisJob) => void
    const first = new Promise<AnalysisJob>((resolve) => { resolveFirst = resolve })
    const get = vi.fn()
      .mockReturnValueOnce(first)
      .mockRejectedValueOnce(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' }))
    const { wrapper } = await mountPage(readyJob.jobId, get)

    await wrapper.setProps({ jobId: '10000000-0000-0000-0000-000000000009' })
    await flushPromises()
    resolveFirst(readyJob)
    await flushPromises()

    expect(wrapper.find('[data-test="workspace-context-panel"]').exists()).toBe(false)
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
