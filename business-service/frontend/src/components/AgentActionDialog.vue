<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { AlertTriangle, Check, LoaderCircle, X } from "@lucide/vue";
import type { AgentAction } from "@/types/agent";

const props = defineProps<{
  open: boolean;
  action: AgentAction | null;
  busy?: boolean;
  errorMessage?: string;
}>();

const emit = defineEmits<{
  approve: [];
  reject: [];
}>();

const now = ref(Date.now());
let timer: number | null = null;

const remainingSeconds = computed(() => {
  const expires = Date.parse(props.action?.expiresAt || "");
  if (!Number.isFinite(expires)) return 0;
  return Math.max(0, Math.ceil((expires - now.value) / 1000));
});
const expired = computed(() => remainingSeconds.value <= 0);
const remainingLabel = computed(() => {
  const minutes = Math.floor(remainingSeconds.value / 60);
  const seconds = remainingSeconds.value % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
});

watch(() => props.open, (open) => {
  if (timer !== null) window.clearInterval(timer);
  timer = null;
  now.value = Date.now();
  if (open) timer = window.setInterval(() => { now.value = Date.now(); }, 1000);
}, { immediate: true });

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer);
});
</script>

<template>
  <div v-if="open && action" class="action-dialog-backdrop" role="presentation">
    <section class="action-dialog" role="dialog" aria-modal="true" aria-labelledby="agent-action-title">
      <header>
        <span class="action-warning"><AlertTriangle :size="20" /></span>
        <div>
          <h2 id="agent-action-title">{{ action.title }}</h2>
          <p>{{ action.summary }}</p>
        </div>
      </header>
      <dl>
        <div><dt>操作</dt><dd>{{ action.toolName }}</dd></div>
        <div><dt>风险</dt><dd>{{ action.riskLevel === "HIGH" ? "高风险写操作" : "低风险写操作" }}</dd></div>
        <div><dt>有效期</dt><dd>{{ expired ? "已过期" : `剩余 ${remainingLabel}` }}</dd></div>
      </dl>
      <pre v-if="Object.keys(action.arguments || {}).length">{{ JSON.stringify(action.arguments, null, 2) }}</pre>
      <p v-if="errorMessage" class="action-error" role="alert">{{ errorMessage }}</p>
      <footer>
        <button type="button" :disabled="busy" @click="emit('reject')"><X :size="15" />拒绝</button>
        <button class="approve" type="button" :disabled="busy || expired" @click="emit('approve')">
          <LoaderCircle v-if="busy" class="spin" :size="15" /><Check v-else :size="15" />确认执行
        </button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.action-dialog-backdrop { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 20px; background: rgba(19, 29, 23, .5); }
.action-dialog { width: min(520px, 100%); padding: 20px; border: 1px solid #e2c890; border-radius: 12px; background: #fff; box-shadow: 0 22px 70px rgba(16, 31, 22, .25); }
header { display: flex; align-items: flex-start; gap: 12px; }
.action-warning { display: grid; width: 38px; height: 38px; flex: none; place-items: center; border-radius: 50%; background: #fff2cf; color: #9b6910; }
h2 { margin: 0; color: #2e3d33; font-size: 18px; }
header p { margin: 6px 0 0; color: #68736b; font-size: 13px; line-height: 1.6; }
dl { display: grid; gap: 8px; margin: 18px 0; }
dl div { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 10px; }
dt { color: #7b857e; font-size: 12px; }
dd { margin: 0; color: #35473b; font-size: 13px; overflow-wrap: anywhere; }
pre { max-height: 180px; padding: 10px; overflow: auto; border: 1px solid #dfe7e1; border-radius: 7px; background: #f6f8f6; color: #405047; font-size: 12px; white-space: pre-wrap; }
.action-error { color: #ad3e35; font-size: 12px; }
footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
button { display: inline-flex; min-height: 36px; align-items: center; gap: 6px; padding: 0 13px; border: 1px solid #c9d2cc; border-radius: 7px; background: #fff; color: #536059; cursor: pointer; }
button.approve { border-color: #ad4a3f; background: #b44f43; color: #fff; }
button:disabled { cursor: not-allowed; opacity: .55; }
.spin { animation: spin 900ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
