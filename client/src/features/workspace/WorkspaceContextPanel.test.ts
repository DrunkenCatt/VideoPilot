import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { AnalysisJob, SegmentPageResponse, TemporaryObjectAccess, VideoContext } from '../../api/contracts'
import type { ContextApi } from '../../api/context'
import WorkspaceContextPanel from './WorkspaceContextPanel.vue'

const readyJob = {
  jobId: '10000000-0000-0000-0000-000000000008',
  goal: '梳理视频中的可靠性架构',
  status: 'READY',
} as AnalysisJob

const context = {
  contextId: '30000000-0000-0000-0000-000000000008',
  jobId: readyJob.jobId,
  durationMs: 120_000,
  segmentCount: 2,
  asrState: 'AVAILABLE',
  ocrState: 'DEGRADED',
  links: { segments: '/segments', playback: '/playback' },
} as VideoContext

const page = {
  data: [
    { segmentId: '40000000-0000-0000-0000-000000000001', ordinal: 1, startMs: 18_000, endMs: 31_000, asrText: 'RDB 快照的工作方式', ocr: [], keyframes: [] },
    { segmentId: '40000000-0000-0000-0000-000000000002', ordinal: 2, startMs: 42_000, endMs: 59_000, asrText: 'AOF 写入日志', ocr: [], keyframes: [] },
  ],
  nextCursor: null,
  hasMore: false,
} as unknown as SegmentPageResponse

function api() {
  return {
    get: vi.fn().mockResolvedValue(context),
    segments: vi.fn().mockResolvedValue(page),
    access: vi.fn().mockResolvedValue({ url: '/media/video', expiresAt: '2026-11-16T10:00:00Z' } as TemporaryObjectAccess),
  }
}

describe('WorkspaceContextPanel', () => {
  it('renders a player and compact evidence list from the current context', async () => {
    const contextApi = api()
    const wrapper = mount(WorkspaceContextPanel, { props: { job: readyJob, api: contextApi as unknown as ContextApi } })
    await flushPromises()

    expect(contextApi.get).toHaveBeenCalledWith(readyJob.jobId)
    expect(wrapper.get('[data-test="workspace-player-panel"] video').attributes('src')).toBe('/media/video')
    expect(wrapper.findAll('[data-test="workspace-evidence-item"]')).toHaveLength(2)
    expect(wrapper.get('[data-test="workspace-context-summary"]').text()).toContain('2 个片段')
    expect(wrapper.text()).toContain('OCR 降级')
  })

  it('seeks the player when an evidence item is selected', async () => {
    const wrapper = mount(WorkspaceContextPanel, { props: { job: readyJob, api: api() as unknown as ContextApi } })
    await flushPromises()
    const video = wrapper.get<HTMLVideoElement>('video').element

    await wrapper.findAll('[data-test="workspace-evidence-item"]')[1]?.trigger('click')

    expect(video.currentTime).toBe(42)
  })

  it('shows the real job state without requesting context before READY', async () => {
    const contextApi = api()
    const job = { ...readyJob, status: 'CONTEXT_BUILDING' } as AnalysisJob
    const wrapper = mount(WorkspaceContextPanel, { props: { job, api: contextApi as unknown as ContextApi } })
    await flushPromises()

    expect(wrapper.get('[data-test="workspace-evidence-panel"]').text()).toContain('构建上下文')
    expect(contextApi.get).not.toHaveBeenCalled()
  })
})
