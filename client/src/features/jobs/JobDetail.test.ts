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
})
