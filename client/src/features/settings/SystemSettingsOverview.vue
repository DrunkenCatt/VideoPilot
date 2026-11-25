<script setup lang="ts">
import { ref } from 'vue'

import type { Session } from '../../api/contracts'
import ProductPageHeader from '../../components/ProductPageHeader.vue'
import { loadUiPreferences, saveUiDensity, type UiDensity } from './uiPreferences'

const props = defineProps<{ session: Session }>()
const emit = defineEmits<{ logout: [] }>()

const loadedPreferences = loadUiPreferences(props.session.userId)
const density = ref<UiDensity>(loadedPreferences.density)
const preferenceError = ref(loadedPreferences.failed ? '无法读取本地界面偏好，可重试保存当前选择。' : '')
const preferenceStatus = ref('')
const infrastructure = ['MySQL 8', 'Redis 7', 'RabbitMQ', 'MinIO', 'Qdrant']
const models = [
  { role: '语言模型', provider: 'SiliconFlow', model: 'deepseek-ai/DeepSeek-V4-Flash' },
  { role: '语音识别', provider: 'SiliconFlow', model: 'TeleAI/TeleSpeechASR' },
  { role: '向量模型', provider: 'SiliconFlow', model: 'BAAI/bge-m3' },
  { role: '画面文字', provider: '本地进程', model: 'Tesseract 5.x' },
]
const experiments = [
  { name: 'Agent Loop', enabled: import.meta.env.VITE_AGENT_ENABLED === 'true' },
  { name: '自动目标分析', enabled: import.meta.env.VITE_GOAL_ANALYSIS_ENABLED === 'true' },
]

function persistDensity(nextDensity = density.value) {
  density.value = nextDensity
  if (saveUiDensity(props.session.userId, density.value)) {
    preferenceError.value = ''
    preferenceStatus.value = '界面偏好已保存到此浏览器。'
    return
  }
  preferenceStatus.value = ''
  preferenceError.value = '无法保存本地界面偏好；当前预览仍然有效。'
}
</script>

<template>
  <main
    class="product-page settings-product-page"
    :data-density="density"
  >
    <ProductPageHeader
      eyebrow="SYSTEM SETTINGS"
      title="系统设置"
      description="查看本地部署、模型适配器和安全边界；服务端密钥与质量硬门不可在网页中修改。"
      status="本地工作区"
    />

    <div
      class="settings-product-grid"
      data-test="settings-product-grid"
    >
      <section
        class="settings-product-card product-surface"
        data-test="settings-section"
      >
        <header><div><p>INFRASTRUCTURE</p><h2>基础设施连接</h2></div><span class="settings-neutral-badge">未检测</span></header>
        <p class="settings-card-note">
          浏览器端未提供运行时健康合同；以下仅为本地部署拓扑，不代表连接健康。
        </p>
        <ul data-test="infrastructure-settings">
          <li
            v-for="service in infrastructure"
            :key="service"
          >
            <span><strong>{{ service }}</strong><small>由 Docker Compose / 服务端配置管理</small></span><span>未提供浏览器健康合同</span>
          </li>
        </ul>
      </section>

      <section
        class="settings-product-card product-surface"
        data-test="settings-section"
      >
        <header><div><p>MODELS & ANALYSIS</p><h2>模型与分析</h2></div><span class="settings-readonly-badge">只读</span></header>
        <p class="settings-card-note">
          展示工程默认适配器合同；实际执行版本仍以每次 Job、Answer 与 Evaluation Trace 为准。
        </p>
        <ul data-test="model-settings">
          <li
            v-for="model in models"
            :key="model.role"
          >
            <span><strong>{{ model.role }}</strong><small>{{ model.provider }}</small></span><code>{{ model.model }}</code>
          </li>
        </ul>
      </section>

      <section
        class="settings-product-card product-surface"
        data-test="settings-section"
      >
        <header><div><p>EXPERIMENTS</p><h2>实验能力</h2></div><span class="settings-readonly-badge">构建期</span></header>
        <p class="settings-card-note">
          这些能力由构建期安全标志决定，网页中不能开启，也不能绕过证据门或预算。
        </p>
        <ul>
          <li
            v-for="experiment in experiments"
            :key="experiment.name"
          >
            <span><strong>{{ experiment.name }}</strong><small>质量硬门始终由服务端确定性代码执行</small></span><span :class="experiment.enabled ? 'settings-enabled' : 'settings-disabled'">{{ experiment.enabled ? '已启用' : '未启用' }}</span>
          </li>
        </ul>
      </section>

      <section
        class="settings-product-card product-surface"
        data-test="settings-section"
      >
        <header><div><p>ACCOUNT & SECURITY</p><h2>本地账户与安全</h2></div><span class="settings-readonly-badge">Owner scoped</span></header>
        <dl>
          <div><dt>当前账号</dt><dd>{{ session.username }}</dd></div>
          <div><dt>部署模式</dt><dd>本地工作区</dd></div>
          <div><dt>会话有效期</dt><dd>{{ new Date(session.expiresAt).toLocaleString('zh-CN') }}</dd></div>
          <div><dt>凭据策略</dt><dd>HttpOnly Cookie / 页面内存 CSRF</dd></div>
        </dl>
        <fieldset>
          <legend>界面密度（本地偏好）</legend>
          <label><input
            type="radio"
            value="comfortable"
            :checked="density === 'comfortable'"
            @change="persistDensity('comfortable')"
          >舒适</label>
          <label><input
            type="radio"
            value="compact"
            :checked="density === 'compact'"
            @change="persistDensity('compact')"
          >紧凑</label>
        </fieldset>
        <p
          v-if="preferenceError"
          class="settings-preference-message settings-preference-error"
          role="alert"
        >
          {{ preferenceError }}
          <button
            type="button"
            data-test="retry-preference"
            @click="persistDensity()"
          >
            重试保存
          </button>
        </p>
        <p
          v-else-if="preferenceStatus"
          class="settings-preference-message"
          data-test="preference-status"
          role="status"
        >
          {{ preferenceStatus }}
        </p>
        <button
          class="settings-logout"
          data-test="settings-logout"
          type="button"
          @click="emit('logout')"
        >
          退出本地账户
        </button>
      </section>
    </div>
  </main>
</template>
