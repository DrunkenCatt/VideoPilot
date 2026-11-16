import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import type { AnalysisJob, MediaAsset, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import MediaLibraryPage from './MediaLibraryPage.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-media',
  expiresAt: '2026-11-03T08:00:00Z',
}

const readyMedia: MediaAsset = {
  mediaId: '20000000-0000-0000-0000-000000000008' as MediaAsset['mediaId'],
  originalFilename: 'lecture.mp4',
  contentLength: 2_048,
  sha256: 'a'.repeat(64),
  status: 'READY',
  durationMs: 125_000,
  failure: null,
  createdAt: '2026-11-02T07:00:00Z',
  updatedAt: '2026-11-02T07:01:00Z',
}

const job: AnalysisJob = {
  jobId: '10000000-0000-0000-0000-000000000008' as AnalysisJob['jobId'],
  mediaId: readyMedia.mediaId,
  goal: '提取章节与关键概念',
  mode: 'STANDARD',
  status: 'CREATED',
  currentStage: null,
  attempt: 0,
  version: 0,
  failure: null,
  reliability: { recoveryState: 'NONE', operatorAction: 'NONE', attempt: 0, nextRetryAt: null, reasonCode: null },
  createdAt: '2026-11-02T08:00:00Z',
  updatedAt: '2026-11-02T08:00:00Z',
  completedAt: null,
  links: {
    self: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008',
    events: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008/events',
    cancellations: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008/cancellations',
  },
}

async function mountPage(submit = vi.fn().mockResolvedValue(job)) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/library', name: 'library', component: { template: '<div />' } },
      { path: '/analysis/:jobId', name: 'analysis-detail', component: { template: '<div />' } },
    ],
  })
  await router.push('/library')
  await router.isReady()
  const wrapper = mount(MediaLibraryPage, {
    props: { session, jobsApi: { submit } },
    global: {
      plugins: [router],
      stubs: {
        MediaUploadPanel: {
          emits: ['uploaded', 'unauthenticated'],
          template: '<button data-test="finish-upload" @click="$emit(\'uploaded\', readyMedia)">上传完成</button>',
          setup: () => ({ readyMedia }),
        },
        MediaLibrary: {
          name: 'MediaLibrary',
          props: ['uploaded', 'selectedId'],
          emits: ['select', 'unauthenticated'],
          template: '<button data-test="choose-ready" @click="$emit(\'select\', readyMedia)">选择媒体</button>',
          setup: () => ({ readyMedia }),
        },
      },
    },
  })
  return { wrapper, router, submit }
}

describe('MediaLibraryPage', () => {
  it('keeps uploaded media in the page and only enables analysis after READY selection', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.find('[data-test="upload-dialog"]').exists()).toBe(false)
    await wrapper.get('[data-test="open-upload"]').trigger('click')
    expect(wrapper.get('[data-test="upload-dialog"]')).toBeTruthy()
    await wrapper.get('[data-test="finish-upload"]').trigger('click')
    expect(wrapper.getComponent({ name: 'MediaLibrary' }).props('uploaded')).toEqual(readyMedia)

    await wrapper.get('[data-test="choose-ready"]').trigger('click')
    expect(wrapper.get('[data-test="analysis-dialog"]')).toBeTruthy()
    expect(wrapper.get('[data-test="selected-media"]').text()).toContain('lecture.mp4')
    expect(wrapper.get('[data-test="create-job"]').attributes('disabled')).toBeUndefined()
  })

  it('creates one analysis job with a stable idempotency key and opens its detail route', async () => {
    const submit = vi.fn().mockResolvedValue(job)
    const { wrapper, router } = await mountPage(submit)
    await wrapper.get('[data-test="choose-ready"]').trigger('click')
    await wrapper.get('#analysis-goal').setValue(job.goal)
    await wrapper.get('[data-test="submit-job"]').trigger('submit')
    await wrapper.get('[data-test="submit-job"]').trigger('submit')
    await flushPromises()

    expect(submit).toHaveBeenCalledOnce()
    expect(submit).toHaveBeenCalledWith(
      { mediaId: readyMedia.mediaId, goal: job.goal, mode: 'STANDARD' },
      expect.any(String),
      session.csrfToken,
    )
    expect(router.currentRoute.value.fullPath).toBe(`/analysis/${job.jobId}`)
  })

  it('reuses the same key for an unchanged retry and reports an expired session', async () => {
    const submit = vi.fn()
      .mockRejectedValueOnce(new ApiClientError(503, { retryable: true }))
      .mockRejectedValueOnce(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' }))
    const { wrapper } = await mountPage(submit)
    await wrapper.get('[data-test="choose-ready"]').trigger('click')
    await wrapper.get('#analysis-goal').setValue(job.goal)

    await wrapper.get('[data-test="submit-job"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('稍后重试')

    await wrapper.get('[data-test="submit-job"]').trigger('submit')
    await flushPromises()
    expect(submit.mock.calls[0]?.[1]).toBe(submit.mock.calls[1]?.[1])
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
