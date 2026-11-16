import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { AnalysisJob, Session } from '../../api/contracts'
import JobWorkbench from './JobWorkbench.vue'

const session: Session = {
  userId: 'f97a9f68-b14c-412c-a0f2-1ad20c62f769' as Session['userId'],
  username: 'teacher',
  csrfToken: 'csrf-for-jobs',
  expiresAt: '2026-11-03T08:00:00Z',
}
const jobId = '10000000-0000-0000-0000-000000000008' as AnalysisJob['jobId']

describe('JobWorkbench transitional shell', () => {
  it('preserves route props and events for pages waiting on F6-F8 migration', async () => {
    const wrapper = mount(JobWorkbench, {
      props: { session, jobId },
      global: {
        stubs: {
          AnalysisJobsPage: {
            name: 'AnalysisJobsPage',
            props: ['session', 'jobId'],
            emits: ['select-job', 'unauthenticated'],
            template: `
              <div>
                <button data-test="select" @click="$emit('select-job', jobId)">选择</button>
                <button data-test="expire" @click="$emit('unauthenticated')">过期</button>
              </div>
            `,
          },
        },
      },
    })

    expect(wrapper.getComponent({ name: 'AnalysisJobsPage' }).props()).toMatchObject({ session, jobId })
    await wrapper.get('[data-test="select"]').trigger('click')
    await wrapper.get('[data-test="expire"]').trigger('click')
    expect(wrapper.emitted('select-job')).toEqual([[jobId]])
    expect(wrapper.emitted('unauthenticated')).toHaveLength(1)
  })
})
