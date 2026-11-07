import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { AnalysisJob, CitedEvidence } from '../../api/contracts'
import JobDetail from './JobDetail.vue'

const job = {
  jobId: '10000000-0000-0000-0000-000000000008',
  mediaId: '20000000-0000-0000-0000-000000000008',
  goal: '理解 Redis 数据结构',
  status: 'READY',
  currentStage: 'INDEXING',
  attempt: 0,
  version: 6,
  failure: null,
  reliability: { recoveryState: 'NONE', operatorAction: 'NONE', attempt: 0, nextRetryAt: null, reasonCode: null },
} as AnalysisJob
const citation = {
  citationId: '70000000-0000-0000-0000-000000000008',
  segmentId: '40000000-0000-0000-0000-000000000008',
  startMs: 72_000,
  endMs: 89_000,
  channel: 'ASR',
  quote: 'Redis 的 String 可以保存字符串。',
} as CitedEvidence

describe('JobDetail answer integration', () => {
  it('forwards cited evidence to the existing timeline instead of creating another player', async () => {
    const wrapper = mount(JobDetail, {
      props: {
        job,
        media: null,
        mediaUnavailable: false,
        events: [],
        actionBusy: false,
        csrfToken: 'csrf-answer',
      },
      global: {
        stubs: {
          VideoContextTimeline: {
            props: ['seekTarget'],
            template: '<div data-test="timeline-target">{{ seekTarget?.segmentId }}</div>',
          },
          AnswerWorkspace: {
            props: ['csrfToken'],
            emits: ['cite'],
            setup: () => ({ citation }),
            template: '<button data-test="emit-citation" @click="$emit(\'cite\', citation)">{{ csrfToken }}</button>',
          },
        },
      },
    })

    expect(wrapper.get('[data-test="emit-citation"]').text()).toBe('csrf-answer')
    await wrapper.get('[data-test="emit-citation"]').trigger('click')
    expect(wrapper.get('[data-test="timeline-target"]').text()).toBe(citation.segmentId)
    expect(wrapper.findAll('[data-test="timeline-target"]')).toHaveLength(1)
  })

  it('explains when MySQL has scheduled an automatic retry', () => {
    const retrying = {
      ...job,
      status: 'RETRY_WAIT',
      reliability: {
        recoveryState: 'RETRY_WAIT',
        operatorAction: 'WAIT',
        attempt: 0,
        nextRetryAt: '2026-11-07T08:00:10Z',
        reasonCode: 'PROVIDER_503',
      },
    } as AnalysisJob
    const wrapper = mount(JobDetail, {
      props: { job: retrying, media: null, mediaUnavailable: false, events: [], actionBusy: false, csrfToken: 'csrf' },
      global: { stubs: { VideoContextTimeline: true, AnswerWorkspace: true } },
    })

    expect(wrapper.get('[data-test="job-reliability"]').text()).toContain('系统将在稍后自动重试')
    expect(wrapper.get('[data-test="job-reliability"]').text()).toContain('PROVIDER_503')
  })

  it('makes exhausted automatic retries an explicit manual action', () => {
    const exhausted = {
      ...job,
      status: 'DEAD_LETTERED',
      reliability: {
        recoveryState: 'MANUAL_ACTION_REQUIRED',
        operatorAction: 'MANUAL_REPLAY',
        attempt: 0,
        nextRetryAt: null,
        reasonCode: 'RETRY_EXHAUSTED',
      },
    } as AnalysisJob
    const wrapper = mount(JobDetail, {
      props: { job: exhausted, media: null, mediaUnavailable: false, events: [], actionBusy: false, csrfToken: 'csrf' },
      global: { stubs: { VideoContextTimeline: true, AnswerWorkspace: true } },
    })

    expect(wrapper.get('[data-test="job-reliability"]').text()).toContain('自动重试已用尽，需要人工处理')
    expect(wrapper.get('[data-test="open-replay"]').text()).toContain('人工重放')
  })

  it('requires an explicit reason and confirmation before emitting replay', async () => {
    const exhausted = {
      ...job,
      status: 'DEAD_LETTERED',
      reliability: {
        recoveryState: 'MANUAL_ACTION_REQUIRED', operatorAction: 'MANUAL_REPLAY',
        attempt: 0, nextRetryAt: null, reasonCode: 'RETRY_EXHAUSTED',
      },
    } as AnalysisJob
    const wrapper = mount(JobDetail, {
      props: { job: exhausted, media: null, mediaUnavailable: false, events: [], actionBusy: false, csrfToken: 'csrf' },
      global: { stubs: { VideoContextTimeline: true, AnswerWorkspace: true } },
    })

    await wrapper.get('[data-test="open-replay"]').trigger('click')
    expect(wrapper.get('[data-test="confirm-replay"]').attributes('disabled')).toBeDefined()
    await wrapper.get('#replay-reason').setValue('  Provider incident resolved  ')
    await wrapper.get('[data-test="replay-confirmation"]').trigger('submit')

    expect(wrapper.emitted('replay')).toEqual([['Provider incident resolved']])
  })
})
