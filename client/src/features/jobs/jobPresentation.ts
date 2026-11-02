import type { JobStage, JobStatus } from '../../api/contracts'

const statusLabels: Record<JobStatus, string> = {
  CREATED: '已提交',
  QUEUED: '排队中',
  CONSUMING: '已取得执行权',
  PROBING: '检查媒体',
  CONTEXT_BUILDING: '构建上下文',
  INDEXING: '建立索引',
  READY: '已就绪',
  ANSWERING: '生成回答',
  VERIFYING: '核验证据',
  COMPLETED: '已完成',
  COMPLETED_WITH_WARNINGS: '完成，但有提示',
  RETRY_WAIT: '等待重试',
  CANCEL_REQUESTED: '正在取消',
  CANCELED: '已取消',
  FAILED: '已失败',
  DEAD_LETTERED: '需要人工处理',
  MANUAL_REPLAY: '人工重放中',
}

const stageLabels: Record<JobStage, string> = {
  PROBING: '媒体检查',
  CONTEXT_BUILDING: '上下文构建',
  INDEXING: '索引构建',
  ANSWERING: '回答生成',
  VERIFYING: '证据核验',
}

export function statusLabel(status: JobStatus): string {
  return statusLabels[status]
}

export function stageLabel(stage: JobStage | null): string {
  return stage ? stageLabels[stage] : '尚未进入处理阶段'
}

export function canCancel(status: JobStatus): boolean {
  return ['CREATED', 'QUEUED', 'CONSUMING', 'PROBING', 'CONTEXT_BUILDING', 'INDEXING', 'ANSWERING'].includes(status)
}
