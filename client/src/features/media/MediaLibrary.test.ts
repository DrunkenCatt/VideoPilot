import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { MediaAsset, MediaId, PageResponse, Session } from '../../api/contracts'
import MediaLibrary from './MediaLibrary.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-media',
  expiresAt: '2026-11-03T08:00:00Z',
}

const uploaded: MediaAsset = {
  mediaId: '20000000-0000-0000-0000-000000000021' as MediaId,
  originalFilename: 'lecture.mp4',
  contentLength: 2_048,
  sha256: 'a'.repeat(64),
  status: 'UPLOADED',
  durationMs: null,
  failure: null,
  createdAt: '2026-11-03T08:00:00Z',
  updatedAt: '2026-11-03T08:00:00Z',
}
const ready: MediaAsset = { ...uploaded, status: 'READY', durationMs: 125_000 }
const failed: MediaAsset = {
  ...uploaded,
  mediaId: '20000000-0000-0000-0000-000000000022' as MediaId,
  originalFilename: 'broken.mp4',
  contentLength: 30,
  status: 'FAILED',
  failure: { code: 'MEDIA_INVALID', retryable: false, summary: '文件不是可解析的视频。' },
}

function page(data: MediaAsset[], current = 1, totalPages = 1): PageResponse<MediaAsset> {
  return {
    data,
    pagination: { page: current, pageSize: 8, totalItems: data.length, totalPages },
  }
}

describe('MediaLibrary', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders trusted status and only allows READY media to be selected', async () => {
    const api = { list: vi.fn().mockResolvedValue(page([ready, failed])), get: vi.fn() }
    const wrapper = mount(MediaLibrary, { props: { session, api } })
    await flushPromises()

    expect(wrapper.text()).toContain('02:05')
    expect(wrapper.text()).toContain('文件不是可解析的视频。')
    expect(wrapper.text()).toContain('30 B')
    expect(wrapper.text()).not.toContain('ffprobe')
    expect(wrapper.get('[data-test="media-feature-grid"]')).toBeTruthy()
    expect(wrapper.get('[data-test="media-table"]')).toBeTruthy()
    expect(wrapper.findAll('[data-test="select-media"]')).toHaveLength(1)
    await wrapper.get('[data-test="select-media"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([ready])
  })

  it('filters the visible card and table views with the product toolbar', async () => {
    const api = { list: vi.fn().mockResolvedValue(page([ready, failed])), get: vi.fn() }
    const wrapper = mount(MediaLibrary, { props: { session, api } })
    await flushPromises()

    await wrapper.get('[data-test="media-search"]').setValue('broken')
    expect(wrapper.text()).toContain('broken.mp4')
    expect(wrapper.text()).not.toContain('lecture.mp4')

    await wrapper.get('[data-test="media-status-filter"]').setValue('READY')
    expect(wrapper.get('[data-test="media-filter-empty"]').text()).toContain('没有匹配')
  })

  it('polls visible non-terminal media until READY and then stops', async () => {
    const api = {
      list: vi.fn().mockResolvedValue(page([uploaded])),
      get: vi.fn().mockResolvedValue(ready),
    }
    const wrapper = mount(MediaLibrary, { props: { session, api, pollIntervalMs: 100 } })
    await flushPromises()
    expect(wrapper.text()).toContain('等待校验')

    await vi.advanceTimersByTimeAsync(100)
    await flushPromises()
    expect(wrapper.text()).toContain('可分析')
    expect(api.get).toHaveBeenCalledOnce()

    await vi.advanceTimersByTimeAsync(500)
    expect(api.get).toHaveBeenCalledOnce()
  })

  it('merges a completed upload immediately and supports paging and recovery', async () => {
    const secondPage = page([failed], 2, 2)
    const api = {
      list: vi.fn()
        .mockResolvedValueOnce(page([], 1, 2))
        .mockResolvedValueOnce(secondPage)
        .mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValueOnce(secondPage),
      get: vi.fn(),
    }
    const wrapper = mount(MediaLibrary, { props: { session, api, uploaded } })
    await flushPromises()
    expect(wrapper.text()).toContain('lecture.mp4')

    await wrapper.get('[data-test="next-media-page"]').trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(2, 8)
    expect(wrapper.text()).toContain('broken.mp4')

    await wrapper.get('[data-test="refresh-media"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法读取媒体资源')
    await wrapper.get('[data-test="refresh-media"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('does not let an older list response overwrite a newly completed upload', async () => {
    let resolveList!: (value: PageResponse<MediaAsset>) => void
    const api = {
      list: vi.fn().mockReturnValue(new Promise<PageResponse<MediaAsset>>((resolve) => {
        resolveList = resolve
      })),
      get: vi.fn(),
    }
    const wrapper = mount(MediaLibrary, { props: { session, api } })

    await wrapper.setProps({ uploaded })
    resolveList(page([failed]))
    await flushPromises()

    expect(wrapper.text()).toContain('lecture.mp4')
    expect(wrapper.text()).not.toContain('broken.mp4')
  })
})
