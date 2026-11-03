import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AnalysisJob, JobEvent, MediaAsset, PageResponse, Session } from '../../api/contracts'
import JobWorkbench from './JobWorkbench.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-jobs',
  expiresAt: '2026-11-03T08:00:00Z',
}

const job: AnalysisJob = {
  jobId: '10000000-0000-0000-0000-000000000008' as AnalysisJob['jobId'],
  mediaId: '20000000-0000-0000-0000-000000000008' as AnalysisJob['mediaId'],
  goal: '提取章节与关键概念',
  mode: 'STANDARD',
  status: 'CREATED',
  currentStage: null,
  attempt: 0,
  version: 0,
  failure: null,
  createdAt: '2026-11-02T08:00:00Z',
  updatedAt: '2026-11-02T08:00:00Z',
  completedAt: null,
  links: {
    self: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008',
    events: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008/events',
    cancellations: '/api/v1/analysis-jobs/10000000-0000-0000-0000-000000000008/cancellations',
  },
}

const readyMedia: MediaAsset = {
  mediaId: job.mediaId,
  originalFilename: 'lecture.mp4',
  contentLength: 2_048,
  sha256: 'a'.repeat(64),
  status: 'READY',
  durationMs: 125_000,
  failure: null,
  createdAt: '2026-11-02T07:00:00Z',
  updatedAt: '2026-11-02T07:01:00Z',
}

const emptyPage: PageResponse<AnalysisJob> = {
  data: [],
  pagination: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 },
}

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function terminalStream(event: JobEvent): Response {
  return new Response(`id: ${event.sequence}\nevent: job-event\ndata: ${JSON.stringify(event)}\n\n`, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function mountWorkbench() {
  return mount(JobWorkbench, {
    props: { session },
    global: {
      stubs: {
        MediaUploadPanel: { template: '<div data-test="upload-stub" />' },
        MediaLibrary: {
          emits: ['select'],
          setup: () => ({ readyMedia }),
          template: '<button data-test="choose-ready" @click="$emit(\'select\', readyMedia)">选择媒体</button>',
        },
      },
    },
  })
}

describe('JobWorkbench', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/')
  })

  it('shows a meaningful empty state backed by the server list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, emptyPage)))

    const wrapper = mountWorkbench()
    await flushPromises()

    expect(wrapper.get('h1').text()).toContain('假任务工作台')
    expect(wrapper.get('[data-test="jobs-empty"]').text()).toContain('还没有分析任务')
    expect(wrapper.get('[data-test="selected-media"]').text()).toContain('请先在媒体资源中选择')
  })

  it('submits one intent and navigates with the self link returned by the server', async () => {
    const readyEvent: JobEvent = {
      jobId: job.jobId,
      sequence: 6,
      eventType: 'JOB_READY',
      status: 'READY',
      stage: 'INDEXING',
      message: 'Analysis job ready.',
      occurredAt: '2026-11-02T08:00:06Z',
      terminal: true,
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, emptyPage))
      .mockResolvedValueOnce(jsonResponse(202, job))
      .mockResolvedValueOnce(terminalStream(readyEvent))
      .mockResolvedValueOnce(jsonResponse(200, { ...job, status: 'READY', currentStage: 'INDEXING', version: 6 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountWorkbench()
    await flushPromises()

    await wrapper.get('[data-test="choose-ready"]').trigger('click')
    await wrapper.get('#analysis-goal').setValue(job.goal)
    await wrapper.get('[data-test="submit-job"]').trigger('submit')
    await flushPromises()

    expect(window.location.hash).toBe(`#${job.links.self}`)
    expect(wrapper.get('[data-test="job-status"]').text()).toContain('已就绪')
    expect(wrapper.get('[data-test="job-events"]').text()).toContain('Analysis job ready.')
    const submitHeaders = fetchMock.mock.calls[1]?.[1]?.headers as Record<string, string>
    expect(submitHeaders['Idempotency-Key']).toBeTruthy()
    expect(submitHeaders['X-CSRF-Token']).toBe(session.csrfToken)
  })

  it('cancels through the server link and announces the resulting state', async () => {
    const page = { ...emptyPage, data: [job], pagination: { ...emptyPage.pagination, totalItems: 1, totalPages: 1 } }
    const canceled = { ...job, status: 'CANCELED' as const, version: 1 }
    const canceledEvent: JobEvent = {
      jobId: job.jobId,
      sequence: 2,
      eventType: 'JOB_CANCELED',
      status: 'CANCELED',
      stage: null,
      message: 'Analysis job canceled.',
      occurredAt: '2026-11-02T08:00:02Z',
      terminal: true,
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, page))
      .mockResolvedValueOnce(new Response('', { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
      .mockResolvedValueOnce(jsonResponse(202, canceled))
      .mockResolvedValueOnce(terminalStream(canceledEvent))
      .mockResolvedValueOnce(jsonResponse(200, canceled))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountWorkbench()
    await flushPromises()

    await wrapper.get('[data-test="open-job"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="cancel-job"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[2]?.[0]).toBe(job.links.cancellations)
    expect(wrapper.get('[data-test="job-status"]').text()).toContain('已取消')
    expect(wrapper.get('[aria-live="polite"]').text()).toContain('已取消')
  })

  it('renders the stable failure summary without exposing internal details', async () => {
    const failed = {
      ...job,
      status: 'FAILED' as const,
      failure: { code: 'MEDIA_CORRUPT', retryable: false, summary: '测试媒体无法解析。' },
    }
    const page = { ...emptyPage, data: [failed], pagination: { ...emptyPage.pagination, totalItems: 1, totalPages: 1 } }
    const failedEvent: JobEvent = {
      jobId: job.jobId,
      sequence: 3,
      eventType: 'JOB_FAILED',
      status: 'FAILED',
      stage: 'PROBING',
      message: '测试媒体无法解析。',
      occurredAt: '2026-11-02T08:00:03Z',
      terminal: true,
    }
    vi.stubGlobal('fetch', vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, page))
      .mockResolvedValueOnce(terminalStream(failedEvent))
      .mockResolvedValueOnce(jsonResponse(200, failed)))

    const wrapper = mountWorkbench()
    await flushPromises()
    await wrapper.get('[data-test="open-job"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('测试媒体无法解析')
    expect(wrapper.get('[role="alert"]').text()).not.toContain('SQLException')
    expect(wrapper.find('[data-test="cancel-job"]').exists()).toBe(false)
  })

  it('restores a selected terminal job from the server link in the URL hash', async () => {
    const ready = { ...job, status: 'READY' as const, version: 6 }
    const page = { ...emptyPage, data: [ready], pagination: { ...emptyPage.pagination, totalItems: 1, totalPages: 1 } }
    const readyEvent: JobEvent = {
      jobId: job.jobId,
      sequence: 6,
      eventType: 'JOB_READY',
      status: 'READY',
      stage: 'INDEXING',
      message: 'Analysis job ready.',
      occurredAt: '2026-11-02T08:00:06Z',
      terminal: true,
    }
    globalThis.window.history.replaceState(null, '', `#${job.links.self}`)
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, page))
      .mockResolvedValueOnce(terminalStream(readyEvent))
      .mockResolvedValueOnce(jsonResponse(200, ready))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mountWorkbench()
    await flushPromises()

    expect(wrapper.get('[data-test="job-status"]').text()).toContain('已就绪')
    expect(wrapper.get('[data-test="job-events"]').text()).toContain('Analysis job ready.')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
