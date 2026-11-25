<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  description: string
  status?: string
}>()
</script>

<template>
  <header class="product-page-header">
    <div class="product-page-header__copy">
      <p
        class="product-page-header__eyebrow"
        data-test="page-eyebrow"
      >
        {{ eyebrow }}
      </p>
      <h1>{{ title }}</h1>
      <p class="product-page-header__description">
        {{ description }}
      </p>
    </div>
    <div class="product-page-header__aside">
      <div
        v-if="$slots.actions"
        class="product-page-header__actions"
        data-test="page-actions"
      >
        <slot name="actions" />
      </div>
      <p
        v-if="status"
        class="product-page-header__status"
        data-test="page-status"
      >
        <span aria-hidden="true" />{{ status }}
      </p>
    </div>
  </header>
</template>
