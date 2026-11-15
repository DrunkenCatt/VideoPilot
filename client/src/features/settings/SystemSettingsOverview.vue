<script setup lang="ts">
import type { Session } from '../../api/contracts'

defineProps<{ session: Session }>()
</script>

<template>
  <main
    class="settings-overview"
    aria-labelledby="settings-title"
  >
    <header>
      <p class="section-index">
        LOCAL / SYSTEM
      </p>
      <h1 id="settings-title">
        系统设置
      </h1>
      <p>查看当前会话与本地部署边界。服务端密钥和质量硬门不会在网页中展示或修改。</p>
    </header>
    <dl>
      <div><dt>当前账号</dt><dd>{{ session.username }}</dd></div>
      <div><dt>部署模式</dt><dd>本地工作区</dd></div>
      <div><dt>会话有效期</dt><dd>{{ new Date(session.expiresAt).toLocaleString('zh-CN') }}</dd></div>
      <div><dt>凭据策略</dt><dd>HttpOnly Cookie / 页面内存 CSRF</dd></div>
    </dl>
  </main>
</template>
