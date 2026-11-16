import { describe, expect, it } from 'vitest'

import { appRoutes, navigationItems } from './router'

describe('application routes', () => {
  it('defines every frozen primary route without a global answer history', () => {
    expect(appRoutes.map((route) => route.path)).toEqual([
      '/workspace/:jobId?',
      '/library',
      '/analysis',
      '/analysis/:jobId',
      '/context/:jobId?',
      '/answers/:jobId?',
      '/evaluation',
      '/settings',
      '/:pathMatch(.*)*',
    ])
    expect(appRoutes.some((route) => route.path.includes('history'))).toBe(false)
  })

  it('keeps the seven primary navigation items in the approved order', () => {
    expect(navigationItems.map((item) => item.label)).toEqual([
      '视频工作台',
      '媒体资料库',
      '解析任务',
      '视频上下文',
      '证据问答',
      '评测中心',
      '系统设置',
    ])
  })

  it('routes the media library to its own page instead of the transitional job workbench', () => {
    const library = appRoutes.find((route) => route.name === 'library')
    const analysis = appRoutes.find((route) => route.name === 'analysis')

    expect(library?.component).toBeTruthy()
    expect(library?.component).not.toBe(analysis?.component)
  })

  it('routes analysis to a dedicated page without changing transitional workspace routes', () => {
    const analysis = appRoutes.find((route) => route.name === 'analysis')
    const detail = appRoutes.find((route) => route.name === 'analysis-detail')
    const workspace = appRoutes.find((route) => route.name === 'workspace')

    expect(analysis?.component).toBe(detail?.component)
    expect(analysis?.component).not.toBe(workspace?.component)
  })

  it('routes video context to its own current-job page', () => {
    const context = appRoutes.find((route) => route.name === 'context')
    const workspace = appRoutes.find((route) => route.name === 'workspace')

    expect(context?.component).toBeTruthy()
    expect(context?.component).not.toBe(workspace?.component)
  })
})
