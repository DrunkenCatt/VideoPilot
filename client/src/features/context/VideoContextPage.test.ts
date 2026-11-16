import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { AnalysisJob } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import VideoContextPage from './VideoContextPage.vue'

const job = {
  jobId: '10000000-0000-0000-0000-000000000008',
  mediaId: '20000000-0000-0000-0000-000000000008',
  goal: '理解 Redis 数据结构',
  status: 'READY',
  currentStage: 'INDEXING',
} as AnalysisJob

function mountPage(get = vi.fn().mockResolvedValue(job), jobId?: string) {
  return mount(VideoContextPage, {
    props: { jobId, jobsApi: { get } },
    global: {
      stubs: {
        VideoContextTimeline: {
          name: 'VideoContextTimeline',
          props: ['job'],
          template: '<section data-test="timeline">{{ job.jobId }} · {{ job.status }}</section>',
        },
      },
    },
  })
}

describe('VideoContextPage', () => {
  it('does not request context without a valid route job id', async () => {
    const get = vi.fn()
    const wrapper = mountPage(get)
    await flushPromises()

    expect(wrapper.get('[data-test="context-empty"]').text()).toContain('选择一个视频')
    expect(get).not.toHaveBeenCalled()

    await wrapper.setProps({ jobId: 'not-a-job-id' })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('链接中的任务标识无效')
    expect(get).not.toHaveBeenCalled()
  })

  it('loads the owner-scoped job and delegates READY rendering to the timeline', async () => {
    const get = vi.fn().mockResolvedValue(job)
    const wrapper = mountPage(get, job.jobId)
    await flushPromises()

    expect(get).toHaveBeenCalledWith(job.links?.self ?? `/api/v1/analysis-jobs/${job.jobId}`)
    expect(wrapper.get('[data-test="timeline"]').text()).toContain(`${job.jobId} · READY`)
  })

  it('ignores a late response after the route switches to another job', async () => {
    let resolveFirst!: (value: AnalysisJob) => void
    const first = new Promise<AnalysisJob>((resolve) => { resolveFirst = resolve })
    const second = { ...job, jobId: '10000000-0000-0000-0000-000000000009' as AnalysisJob['jobId'], status: 'CONTEXT_BUILDING' as const }
    const get = vi.fn().mockReturnValueOnce(first).mockResolvedValueOnce(second)
    const wrapper = mountPage(get, job.jobId)

    await wrapper.setProps({ jobId: second.jobId })
    await flushPromises()
    resolveFirst(job)
    await flushPromises()

    expect(wrapper.get('[data-test="timeline"]').text()).toContain(second.jobId)
    expect(wrapper.text()).not.toContain(job.jobId)
  })

  it('clears stale content and expires the session on a 401', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce(job)
      .mockRejectedValueOnce(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' }))
    const wrapper = mountPage(get, job.jobId)
    await flushPromises()
    expect(wrapper.find('[data-test="timeline"]').exists()).toBe(true)

    await wrapper.setProps({ jobId: '10000000-0000-0000-0000-000000000009' })
    await flushPromises()
    expect(wrapper.find('[data-test="timeline"]').exists()).toBe(false)
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
