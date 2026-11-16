import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type {
  AnalysisJob,
  CitedEvidence,
  SegmentPageResponse,
  TemporaryObjectAccess,
  VideoContext,
  VideoSegment,
} from '../../api/contracts'
import type { ContextApi } from '../../api/context'
import { ApiClientError } from '../../api/session'
import VideoContextTimeline from './VideoContextTimeline.vue'

const job = {
  jobId: '10000000-0000-0000-0000-000000000008',
  mediaId: '20000000-0000-0000-0000-000000000008',
  status: 'READY',
  currentStage: 'CONTEXT_BUILDING',
} as AnalysisJob

const context: VideoContext = {
  contextId: '30000000-0000-0000-0000-000000000008' as VideoContext['contextId'],
  jobId: job.jobId,
  mediaId: job.mediaId,
  status: 'READY',
  extractionVersion: 'm2-extraction-v1',
  alignmentVersion: 'm2-align-60s-v1',
  durationMs: 120_000,
  asrState: 'AVAILABLE',
  ocrState: 'DEGRADED',
  segmentCount: 2,
  updatedAt: '2026-11-05T08:00:00Z',
  links: { segments: '/context/segments', playback: '/media/playback' },
}

const segments: VideoSegment[] = [
  {
    segmentId: '40000000-0000-0000-0000-000000000001' as VideoSegment['segmentId'],
    ordinal: 0,
    startMs: 0,
    endMs: 60_000,
    asrText: '欢迎来到第一节。',
    ocr: [{ text: '第一章', confidence: 0.94 }],
    keyframes: [{
      keyframeId: '50000000-0000-0000-0000-000000000001' as VideoSegment['keyframes'][number]['keyframeId'],
      timestampMs: 15_000,
      access: '/context/keyframes/frame-1/access',
    }],
  },
  {
    segmentId: '40000000-0000-0000-0000-000000000002' as VideoSegment['segmentId'],
    ordinal: 1,
    startMs: 60_000,
    endMs: 120_000,
    asrText: '继续讲解第二节。',
    ocr: [],
    keyframes: [],
  },
]

const playback: TemporaryObjectAccess = {
  url: 'http://storage.invalid/video-signed',
  expiresAt: '2026-11-05T08:10:00Z',
}

function contextApi(overrides: Partial<ContextApi> = {}): ContextApi {
  return {
    get: vi.fn().mockResolvedValue(context),
    segments: vi.fn().mockResolvedValue({ data: segments, nextCursor: null, hasMore: false }),
    access: vi.fn().mockResolvedValue(playback),
    ...overrides,
  }
}

describe('VideoContextTimeline', () => {
  it('renders persisted ASR, OCR and keyframes with a clear degraded channel state', async () => {
    const api = contextApi()
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    expect(wrapper.get('[data-test="context-player"]').attributes('src')).toBe(playback.url)
    expect(wrapper.get('[data-test="context-product-grid"]')).toBeTruthy()
    expect(wrapper.get('[data-test="context-summary-panel"]').text()).toContain('VideoSegment')
    expect(wrapper.get('[data-test="current-segment-panel"]').text()).toContain('欢迎来到第一节')
    expect(wrapper.get('[data-test="channel-warning"]').text()).toContain('OCR 已降级')
    expect(wrapper.get('[data-test="asr-layer"]').text()).toContain('欢迎来到第一节')
    expect(wrapper.get('[data-test="ocr-layer"]').text()).toContain('第一章')
    expect(wrapper.get('[data-test="keyframe-layer"]').text()).toContain('00:15')
  })

  it('keeps an ASR-degraded visual-only segment explicit instead of inventing transcript evidence', async () => {
    const visualOnly = {
      ...segments[0]!,
      asrText: null,
      ocr: [{ text: '缓存命中率', confidence: 0.88 }],
      keyframes: [],
    }
    const degradedContext = {
      ...context,
      asrState: 'DEGRADED' as const,
      ocrState: 'AVAILABLE' as const,
      segmentCount: 1,
    }
    const api = contextApi({
      get: vi.fn().mockResolvedValue(degradedContext),
      segments: vi.fn().mockResolvedValue({ data: [visualOnly], nextCursor: null, hasMore: false }),
    })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    expect(wrapper.get('[data-test="segment-0"]').text()).toContain('仅包含视觉证据的片段')
    expect(wrapper.get('[data-test="segment-0"]').text()).toContain('ASR 缺失')
    expect(wrapper.get('[data-test="context-summary-panel"]').text()).toContain('ASR 字符0')
    expect(wrapper.get('[data-test="asr-layer"]').text()).toContain('没有可用语音文本')
    expect(wrapper.get('[data-test="ocr-layer"]').text()).toContain('缓存命中率 · 88%')
    expect(wrapper.get('[data-test="keyframe-layer"]').text()).toContain('没有关键帧')
    expect(wrapper.find('[data-test="channel-warning"]').exists()).toBe(false)
  })

  it('seeks from keyboard-operable entries and only changes the active segment on time updates', async () => {
    const api = contextApi()
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()
    const video = wrapper.get<HTMLVideoElement>('[data-test="context-player"]')

    await wrapper.get('[data-test="segment-1"]').trigger('click')
    expect(video.element.currentTime).toBe(60)

    video.element.currentTime = 15
    await video.trigger('timeupdate')
    expect(wrapper.get('[data-test="segment-0"]').attributes('aria-current')).toBe('true')
    expect(api.segments).toHaveBeenCalledTimes(1)
  })

  it('loads a keyframe lazily and renews temporary playback access after an expiry error', async () => {
    const renewed = { ...playback, url: 'http://storage.invalid/video-renewed' }
    const frame = { ...playback, url: 'http://storage.invalid/frame-signed' }
    const access = vi.fn()
      .mockResolvedValueOnce(playback)
      .mockResolvedValueOnce(frame)
      .mockResolvedValueOnce(renewed)
    const api = contextApi({ access })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    await wrapper.get('[data-test="keyframe-0-0"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="keyframe-preview"]').attributes('src')).toBe(frame.url)

    await wrapper.get('[data-test="context-player"]').trigger('error')
    await flushPromises()
    expect(wrapper.get('[data-test="context-player"]').attributes('src')).toBe(renewed.url)
    expect(access).toHaveBeenNthCalledWith(2, segments[0]!.keyframes[0]!.access)
    expect(access).toHaveBeenNthCalledWith(3, context.links.playback)
  })

  it('shows server processing state without requesting unfinished context', async () => {
    const api = contextApi()
    const building = { ...job, status: 'CONTEXT_BUILDING' as const }
    const wrapper = mount(VideoContextTimeline, { props: { job: building, api } })
    await flushPromises()

    expect(wrapper.get('[data-test="context-processing"]').text()).toContain('正在构建上下文')
    expect(api.get).not.toHaveBeenCalled()
  })

  it('emits authentication expiry and keeps hidden resources indistinguishable', async () => {
    const expiredApi = contextApi({
      get: vi.fn().mockRejectedValue(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' })),
    })
    const expired = mount(VideoContextTimeline, { props: { job, api: expiredApi } })
    await flushPromises()
    expect(expired.emitted('unauthenticated')).toHaveLength(1)

    const hiddenApi = contextApi({
      get: vi.fn().mockRejectedValue(new ApiClientError(404, { code: 'RESOURCE_NOT_FOUND' })),
    })
    const hidden = mount(VideoContextTimeline, { props: { job, api: hiddenApi } })
    await flushPromises()
    expect(hidden.get('[data-test="context-error"]').text()).toContain('不可用或无权访问')
  })

  it('loads later pages only when requested and appends them once', async () => {
    const first: SegmentPageResponse = { data: [segments[0]!], nextCursor: 1, hasMore: true }
    const second: SegmentPageResponse = { data: [segments[1]!], nextCursor: null, hasMore: false }
    const pages = vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second)
    const api = contextApi({ segments: pages })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    expect(wrapper.find('[data-test="segment-1"]').exists()).toBe(false)
    await wrapper.get('[data-test="load-more-segments"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="segment-1"]').text()).toContain('01:00')
    expect(pages).toHaveBeenNthCalledWith(2, context.links.segments, { cursor: 1, pageSize: 100 })
  })

  it('surfaces playback renewal failures and stops an automatic error loop', async () => {
    const access = vi.fn()
      .mockRejectedValueOnce(new ApiClientError(503, { code: 'DEPENDENCY_UNAVAILABLE', retryable: true }))
      .mockRejectedValueOnce(new ApiClientError(503, { code: 'DEPENDENCY_UNAVAILABLE', retryable: true }))
    const api = contextApi({ access })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    expect(wrapper.text()).toContain('上下文服务暂时不可用')
    const video = wrapper.get('[data-test="context-player"]')
    await video.trigger('error')
    await flushPromises()
    await video.trigger('error')

    expect(wrapper.text()).toContain('续签后仍不可用')
    expect(access).toHaveBeenCalledTimes(2)
  })

  it('keeps loaded segments when a later page fails and reports an expired session', async () => {
    const pages = vi.fn()
      .mockResolvedValueOnce({ data: [segments[0]], nextCursor: 1, hasMore: true })
      .mockRejectedValueOnce(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' }))
    const api = contextApi({ segments: pages })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    await wrapper.get('[data-test="load-more-segments"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="segment-0"]').text()).toContain('00:00')
    expect(wrapper.find('[data-test="segment-1"]').exists()).toBe(false)
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })

  it('renews an expired keyframe once and reports a second image failure', async () => {
    const frame = { ...playback, url: 'http://storage.invalid/frame-signed' }
    const renewed = { ...frame, url: 'http://storage.invalid/frame-renewed' }
    const access = vi.fn()
      .mockResolvedValueOnce(playback)
      .mockResolvedValueOnce(frame)
      .mockResolvedValueOnce(renewed)
    const api = contextApi({ access })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    await wrapper.get('[data-test="keyframe-0-0"]').trigger('click')
    await flushPromises()
    const preview = wrapper.get('[data-test="keyframe-preview"]')
    await preview.trigger('error')
    await flushPromises()
    expect(preview.attributes('src')).toBe(renewed.url)

    await preview.trigger('error')
    expect(wrapper.text()).toContain('关键帧链接续签后仍不可用')
    expect(access).toHaveBeenCalledTimes(3)
  })

  it('does not apply a late playback link after the selected job changes', async () => {
    let resolveFirst!: (value: TemporaryObjectAccess) => void
    const firstAccess = new Promise<TemporaryObjectAccess>((resolve) => { resolveFirst = resolve })
    const secondJob = {
      ...job,
      jobId: '10000000-0000-0000-0000-000000000009' as AnalysisJob['jobId'],
      mediaId: '20000000-0000-0000-0000-000000000009' as AnalysisJob['mediaId'],
    }
    const secondContext = {
      ...context,
      contextId: '30000000-0000-0000-0000-000000000009' as VideoContext['contextId'],
      jobId: secondJob.jobId,
      mediaId: secondJob.mediaId,
      links: { segments: '/context-2/segments', playback: '/media-2/playback' },
    }
    const secondAccess = { ...playback, url: 'http://storage.invalid/video-second' }
    const api = contextApi({
      get: vi.fn().mockResolvedValueOnce(context).mockResolvedValueOnce(secondContext),
      segments: vi.fn().mockResolvedValue({ data: segments, nextCursor: null, hasMore: false }),
      access: vi.fn().mockReturnValueOnce(firstAccess).mockResolvedValueOnce(secondAccess),
    })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    await wrapper.setProps({ job: secondJob })
    await flushPromises()
    expect(wrapper.get('[data-test="context-player"]').attributes('src')).toBe(secondAccess.url)

    resolveFirst({ ...playback, url: 'http://storage.invalid/video-stale' })
    await flushPromises()
    expect(wrapper.get('[data-test="context-player"]').attributes('src')).toBe(secondAccess.url)
  })

  it('loads a missing cited segment in a bounded window and seeks the existing player', async () => {
    const first: SegmentPageResponse = { data: [segments[0]!], nextCursor: 1, hasMore: true }
    const citedPage: SegmentPageResponse = { data: [segments[1]!], nextCursor: null, hasMore: false }
    const pages = vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(citedPage)
    const api = contextApi({ segments: pages })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()
    const citation = {
      citationId: '70000000-0000-0000-0000-000000000008',
      segmentId: segments[1]!.segmentId,
      startMs: 72_000,
      endMs: 89_000,
      channel: 'ASR',
      quote: '继续讲解第二节。',
    } as CitedEvidence

    await wrapper.setProps({ seekTarget: citation })
    await flushPromises()

    expect(pages).toHaveBeenNthCalledWith(2, context.links.segments, {
      pageSize: 100,
      fromMs: 72_000,
      toMs: 89_000,
    })
    expect(wrapper.get<HTMLVideoElement>('[data-test="context-player"]').element.currentTime).toBe(72)
    expect(wrapper.get('[data-test="segment-1"]').attributes('aria-current')).toBe('true')
  })

  it('seeks an already loaded citation without fetching another segment window', async () => {
    const pages = vi.fn().mockResolvedValue({ data: segments, nextCursor: null, hasMore: false })
    const api = contextApi({ segments: pages })
    const wrapper = mount(VideoContextTimeline, { props: { job, api } })
    await flushPromises()

    await wrapper.setProps({
      seekTarget: {
        citationId: '70000000-0000-0000-0000-000000000009',
        segmentId: segments[0]!.segmentId,
        startMs: 15_000,
        endMs: 30_000,
        channel: 'OCR',
        quote: '第一章',
      } as CitedEvidence,
    })
    await flushPromises()

    expect(pages).toHaveBeenCalledTimes(1)
    expect(wrapper.get<HTMLVideoElement>('[data-test="context-player"]').element.currentTime).toBe(15)
  })
})
