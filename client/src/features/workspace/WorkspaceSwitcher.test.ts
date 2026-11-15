import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import type { AnalysisJob, MediaAsset, PageResponse } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import { appRoutes } from '../../router'
import WorkspaceSwitcher from './WorkspaceSwitcher.vue'

const media: MediaAsset = {
  mediaId: '20000000-0000-0000-0000-000000000008' as MediaAsset['mediaId'],
  originalFilename: 'distributed-systems.mp4',
  contentLength: 2_048,
  sha256: 'a'.repeat(64),
  status: 'READY',
  durationMs: 120_000,
  failure: null,
  createdAt: '2026-11-10T08:00:00Z',
  updatedAt: '2026-11-10T08:01:00Z',
}

function job(id: string, status: AnalysisJob['status'], updatedAt: string, goal: string): AnalysisJob {
  return {
    jobId: id as AnalysisJob['jobId'],
    mediaId: media.mediaId,
    goal,
    mode: 'STANDARD',
    status,
    currentStage: null,
    attempt: 0,
    version: 1,
    failure: status === 'FAILED' ? { code: 'MODEL_FAILED', retryable: true, summary: '模型调用失败' } : null,
    reliability: { recoveryState: 'NONE', operatorAction: 'NONE', attempt: 0, nextRetryAt: null, reasonCode: null },
    createdAt: updatedAt,
    updatedAt,
    completedAt: status === 'READY' ? updatedAt : null,
    links: {
      self: `/api/v1/analysis-jobs/${id}`,
      events: `/api/v1/analysis-jobs/${id}/events`,
      cancellations: `/api/v1/analysis-jobs/${id}/cancellations`,
    },
  }
}

const olderReady = job('10000000-0000-0000-0000-000000000008', 'READY', '2026-11-11T08:00:00Z', '概念提取')
const newerReady = job('10000000-0000-0000-0000-000000000009', 'READY', '2026-11-12T08:00:00Z', '考点复核')
const running = job('10000000-0000-0000-0000-000000000010', 'INDEXING', '2026-11-13T08:00:00Z', '新版本索引')

function page<T>(data: T[]): PageResponse<T> {
  return { data, pagination: { page: 1, pageSize: 20, totalItems: data.length, totalPages: data.length ? 1 : 0 } }
}

async function mountSwitcher(path: string, jobs = [running, newerReady, olderReady]) {
  const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
  await router.push(path)
  await router.isReady()
  const jobsApi = { list: vi.fn().mockResolvedValue(page(jobs)), get: vi.fn() }
  const mediaApi = { list: vi.fn().mockResolvedValue(page([media])), get: vi.fn() }
  const wrapper = mount(WorkspaceSwitcher, {
    props: { jobsApi, mediaApi },
    global: { plugins: [router], stubs: { teleport: true } },
  })
  await flushPromises()
  return { wrapper, router, jobsApi, mediaApi }
}

describe('WorkspaceSwitcher', () => {
  it('defaults a job-scoped page to the newest READY job, never the newer running job', async () => {
    const { wrapper, router } = await mountSwitcher('/workspace')

    expect(router.currentRoute.value.params.jobId).toBe(newerReady.jobId)
    expect(wrapper.get('[data-test="workspace-switcher"]').text()).toContain('distributed-systems.mp4')
    expect(wrapper.get('[data-test="workspace-switcher"]').text()).toContain('已就绪')
  })

  it('groups multiple jobs under one media and keeps their status and goal distinguishable', async () => {
    const { wrapper } = await mountSwitcher(`/workspace/${olderReady.jobId}`)
    await wrapper.get('[data-test="workspace-switcher"]').trigger('click')

    expect(wrapper.findAll('[data-test="media-group"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="workspace-job"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('新版本索引')
    expect(wrapper.text()).toContain('建立索引')
    expect(wrapper.text()).toContain('考点复核')
  })

  it('updates the current route when another job is selected', async () => {
    const { wrapper, router } = await mountSwitcher(`/answers/${olderReady.jobId}`)
    await wrapper.get('[data-test="workspace-switcher"]').trigger('click')
    await wrapper.findAll('[data-test="workspace-job"]')[1]!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('answers')
    expect(router.currentRoute.value.params.jobId).toBe(newerReady.jobId)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('does not select a job automatically on a non-workspace page', async () => {
    const { router } = await mountSwitcher('/library')

    expect(router.currentRoute.value.fullPath).toBe('/library')
  })

  it('offers media without a job as a create-task path instead of a question-ready workspace', async () => {
    const { wrapper } = await mountSwitcher('/library', [])
    await wrapper.get('[data-test="workspace-switcher"]').trigger('click')

    expect(wrapper.get('a[href="/library"]').text()).toBe('创建解析任务')
    expect(wrapper.findAll('[data-test="workspace-job"]')).toHaveLength(0)
  })

  it('clears the current display when an explicit job is deleted or inaccessible', async () => {
    const missingId = '10000000-0000-0000-0000-000000000099'
    const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
    await router.push(`/workspace/${missingId}`)
    await router.isReady()
    const jobsApi = {
      list: vi.fn().mockResolvedValue(page([])),
      get: vi.fn().mockRejectedValue(new ApiClientError(404, {
        code: 'RESOURCE_NOT_FOUND', message: 'not found', retryable: false,
      })),
    }
    const wrapper = mount(WorkspaceSwitcher, {
      props: {
        jobsApi,
        mediaApi: { list: vi.fn().mockResolvedValue(page([])), get: vi.fn() },
      },
      global: { plugins: [router], stubs: { teleport: true } },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="workspace-switcher"]').text()).toContain('切换视频')
    expect(wrapper.get('[role="status"]').text()).toContain('已删除，或当前账号无权访问')
  })

  it('ignores a late response for the previously routed job', async () => {
    const first = olderReady
    const secondMedia = { ...media, mediaId: '20000000-0000-0000-0000-000000000099' as MediaAsset['mediaId'], originalFilename: 'second.mp4' }
    const second = { ...newerReady, mediaId: secondMedia.mediaId }
    let resolveFirst!: (value: AnalysisJob) => void
    const firstResponse = new Promise<AnalysisJob>((resolve) => { resolveFirst = resolve })
    const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
    await router.push(`/workspace/${first.jobId}`)
    await router.isReady()
    const jobsApi = {
      list: vi.fn().mockResolvedValue(page([])),
      get: vi.fn((selfLink: string) => selfLink.endsWith(first.jobId) ? firstResponse : Promise.resolve(second)),
    }
    const mediaApi = {
      list: vi.fn().mockResolvedValue(page([])),
      get: vi.fn().mockImplementation((mediaId: MediaAsset['mediaId']) => Promise.resolve(mediaId === second.mediaId ? secondMedia : media)),
    }
    const wrapper = mount(WorkspaceSwitcher, { props: { jobsApi, mediaApi }, global: { plugins: [router], stubs: { teleport: true } } })
    await flushPromises()

    await router.push(`/workspace/${second.jobId}`)
    await flushPromises()
    resolveFirst(first)
    await flushPromises()

    expect(wrapper.get('[data-test="workspace-switcher"]').text()).toContain('second.mp4')
    expect(wrapper.get('[data-test="workspace-switcher"]').text()).not.toContain('distributed-systems.mp4')
  })

  it('rejects a malformed route id without querying a guessed resource', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRoutes })
    await router.push('/workspace/not-a-job-id')
    await router.isReady()
    const jobsApi = { list: vi.fn().mockResolvedValue(page([])), get: vi.fn() }
    const wrapper = mount(WorkspaceSwitcher, {
      props: { jobsApi, mediaApi: { list: vi.fn().mockResolvedValue(page([])), get: vi.fn() } },
      global: { plugins: [router], stubs: { teleport: true } },
    })
    await flushPromises()

    expect(jobsApi.get).not.toHaveBeenCalled()
    expect(wrapper.get('[role="status"]').text()).toContain('任务标识格式无效')
  })
})
