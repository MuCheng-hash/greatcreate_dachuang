<script setup>
import { AlertCircle, CheckCircle2, Info } from "@lucide/vue";
const props = defineProps({ tone: { type: String, default: "info" } });
const icons = { info: Info, success: CheckCircle2, error: AlertCircle };
</script>

<template>
  <div class="inline-notice" :class="`notice-${tone}`" role="status">
    <component :is="icons[props.tone] || Info" :size="18" />
    <div><slot /></div>
  </div>
</template>
