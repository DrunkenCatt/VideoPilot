import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import EvaluationWorkbench from './features/evaluation/EvaluationWorkbench.vue'
import JobWorkbench from './features/jobs/JobWorkbench.vue'
import SystemSettingsOverview from './features/settings/SystemSettingsOverview.vue'

export type NavigationItem = {
  name: string
  label: string
  index: string
}

export const navigationItems: NavigationItem[] = [
  { name: 'workspace', label: '视频工作台', index: '01' },
  { name: 'library', label: '媒体资料库', index: '02' },
  { name: 'analysis', label: '解析任务', index: '03' },
  { name: 'context', label: '视频上下文', index: '04' },
  { name: 'answers', label: '证据问答', index: '05' },
  { name: 'evaluation', label: '评测中心', index: '06' },
  { name: 'settings', label: '系统设置', index: '07' },
]

const videoRouteMeta = { surface: 'video' }

export const appRoutes: RouteRecordRaw[] = [
  {
    path: '/workspace/:jobId?',
    name: 'workspace',
    component: JobWorkbench,
    meta: { ...videoRouteMeta, title: '视频工作台' },
  },
  {
    path: '/library',
    name: 'library',
    component: JobWorkbench,
    meta: { ...videoRouteMeta, title: '媒体资料库' },
  },
  {
    path: '/analysis',
    name: 'analysis',
    component: JobWorkbench,
    meta: { ...videoRouteMeta, title: '解析任务' },
  },
  {
    path: '/analysis/:jobId',
    name: 'analysis-detail',
    component: JobWorkbench,
    meta: { ...videoRouteMeta, title: '解析任务', navigationName: 'analysis' },
  },
  {
    path: '/context/:jobId?',
    name: 'context',
    component: JobWorkbench,
    meta: { ...videoRouteMeta, title: '视频上下文' },
  },
  {
    path: '/answers/:jobId?',
    name: 'answers',
    component: JobWorkbench,
    meta: { ...videoRouteMeta, title: '证据问答' },
  },
  {
    path: '/evaluation',
    name: 'evaluation',
    component: EvaluationWorkbench,
    meta: { title: '评测中心' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: SystemSettingsOverview,
    meta: { title: '系统设置' },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/workspace',
  },
]

export function createAppRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: appRoutes,
    scrollBehavior: () => ({ top: 0 }),
  })
}
