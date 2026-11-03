import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { MediaAsset, Session } from '../../api/contracts'
import { ApiClientError } from '../../api/session'
import MediaUploadPanel from './MediaUploadPanel.vue'

const uploadMediaFile = vi.fn()

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-media',
  expiresAt: '2026-11-03T08:00:00Z',
}

const uploaded: MediaAsset = {
  mediaId: '20000000-0000-0000-0000-000000000009' as MediaAsset['mediaId'],
  originalFilename: 'lesson.mp4',
  contentLength: 9,
  sha256: 'a'.repeat(64),
  status: 'UPLOADED',
  durationMs: null,
  failure: null,
  createdAt: '2026-11-02T08:00:00Z',
  updatedAt: '2026-11-02T08:01:00Z',
}

function selectFile(wrapper: ReturnType<typeof mount>, file: File) {
  const input = wrapper.get<HTMLInputElement>('[data-test="media-file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  return input.trigger('change')
}

describe('MediaUploadPanel', () => {
  beforeEach(() => {
    uploadMediaFile.mockReset()
  })

  it('uploads a small fixture and explains that server validation is still pending', async () => {
    uploadMediaFile.mockImplementation(async (_file, _csrf, _api, options) => {
      options.onHashing()
      options.onProgress(5, 9)
      options.onProgress(9, 9)
      return uploaded
    })
    const wrapper = mount(MediaUploadPanel, { props: { session, uploader: uploadMediaFile } })
    await selectFile(wrapper, new File(['123456789'], 'lesson.mp4', { type: 'video/mp4' }))

    await wrapper.get('[data-test="start-upload"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="upload-status"]').text()).toContain('上传完成')
    expect(wrapper.get('[data-test="upload-progress"]').attributes('value')).toBe('9')
    expect(wrapper.text()).toContain('服务端正在校验内容')
    expect(wrapper.text()).toContain(uploaded.mediaId)
    expect(wrapper.emitted('uploaded')?.[0]).toEqual([uploaded])
  })

  it('rejects a file over the client limit before upload', async () => {
    const wrapper = mount(MediaUploadPanel, { props: { session, uploader: uploadMediaFile } })
    const oversized = new File(['x'], 'too-large.mp4', { type: 'video/mp4' })
    Object.defineProperty(oversized, 'size', { value: 5 * 1024 ** 3 + 1 })

    await selectFile(wrapper, oversized)

    expect(wrapper.get('[role="alert"]').text()).toContain('5 GiB')
    expect(wrapper.find('[data-test="start-upload"]').attributes('disabled')).toBeDefined()
    expect(uploadMediaFile).not.toHaveBeenCalled()
  })

  it('keeps cancellation keyboard-operable and reports an aborted terminal state', async () => {
    uploadMediaFile.mockImplementation((_file, _csrf, _api, options) => new Promise((_resolve, reject) => {
      options.onHashing()
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))
    const wrapper = mount(MediaUploadPanel, { props: { session, uploader: uploadMediaFile } })
    await selectFile(wrapper, new File(['123'], 'lesson.mp4', { type: 'video/mp4' }))
    await wrapper.get('[data-test="start-upload"]').trigger('click')
    await wrapper.get('[data-test="abort-upload"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="upload-status"]').text()).toContain('已中止')
    expect(wrapper.find('[data-test="abort-upload"]').exists()).toBe(false)
  })

  it('distinguishes expiry from retry and generic failures', async () => {
    uploadMediaFile.mockImplementation(async (_file, _csrf, _api, options) => {
      options.onRetry(2, 2)
      throw Object.assign(new Error('expired'), { code: 'UPLOAD_EXPIRED' })
    })
    const wrapper = mount(MediaUploadPanel, { props: { session, uploader: uploadMediaFile } })
    await selectFile(wrapper, new File(['123'], 'lesson.mp4', { type: 'video/mp4' }))
    await wrapper.get('[data-test="start-upload"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="upload-status"]').text()).toContain('已过期')
    expect(wrapper.get('[role="alert"]').text()).toContain('重新选择')
  })

  it('formats file hints across size bands and returns to idle when selection is cleared', async () => {
    const wrapper = mount(MediaUploadPanel, { props: { session, uploader: uploadMediaFile } })
    for (const [size, label] of [
      [1024, '1.0 KiB'],
      [1024 ** 2, '1.0 MiB'],
      [1024 ** 3, '1.00 GiB'],
    ] as const) {
      const selected = new File(['x'], `lesson-${size}.mp4`, { type: 'video/mp4' })
      Object.defineProperty(selected, 'size', { value: size })
      await selectFile(wrapper, selected)
      expect(wrapper.text()).toContain(label)
    }
    const input = wrapper.get<HTMLInputElement>('[data-test="media-file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [] })
    await input.trigger('change')
    expect(wrapper.get('[data-test="upload-status"]').text()).toContain('等待选择')
  })

  it('reports exhausted retryable failures and emits when authentication expires', async () => {
    uploadMediaFile.mockRejectedValueOnce(new ApiClientError(503, { retryable: true }))
    const wrapper = mount(MediaUploadPanel, { props: { session, uploader: uploadMediaFile } })
    await selectFile(wrapper, new File(['123'], 'lesson.mp4', { type: 'video/mp4' }))
    await wrapper.get('[data-test="start-upload"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('有限重试已用尽')

    uploadMediaFile.mockRejectedValueOnce(new ApiClientError(401, { code: 'AUTHENTICATION_REQUIRED' }))
    await selectFile(wrapper, new File(['456'], 'lesson-2.mp4', { type: 'video/mp4' }))
    await wrapper.get('[data-test="start-upload"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
