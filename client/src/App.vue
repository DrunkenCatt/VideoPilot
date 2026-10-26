<script setup lang="ts">
const projectStage = 'M0 · 工程骨架与契约冻结'
</script>

<template>
  <main class="shell">
    <p class="eyebrow">
      VIDEOPILOT / LOCAL WORKSPACE
    </p>
    <h1>让长视频中的每个结论，都能回到证据。</h1>
    <p class="intro">
      教学视频解析、检索与带时间戳问答工作台正在构建中。
    </p>
    <section aria-labelledby="stage-title">
      <span>当前阶段</span>
      <h2 id="stage-title">
        {{ projectStage }}
      </h2>
    </section>
  </main>
</template>
