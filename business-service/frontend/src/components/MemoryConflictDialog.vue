<script setup lang="ts">
import { AlertTriangle, ArrowRightLeft, Archive, Check, X } from "@lucide/vue";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import type { AgentMemoryItem } from "@/types/agent";

const props = withDefaults(defineProps<{
  open: boolean;
  candidate: AgentMemoryItem | null;
  conflicts: AgentMemoryItem[];
  busy?: boolean;
  errorMessage?: string;
}>(), {
  busy: false,
  errorMessage: "",
});

const emit = defineEmits<{
  cancel: [];
  keep: [];
  replace: [];
}>();

const dialogRef = ref<HTMLElement | null>(null);
const cancelButtonRef = ref<HTMLButtonElement | null>(null);
let previousFocus: HTMLElement | null = null;

function focusableElements(): HTMLElement[] {
  if (!dialogRef.value) return [];
  return Array.from(dialogRef.value.querySelectorAll<HTMLElement>([
    "button:not([disabled])",
    "[href]",
    "input:not([disabled])",
    "select:not([disabled])",
    "textarea:not([disabled])",
    '[tabindex]:not([tabindex="-1"])',
  ].join(","))).filter((element) => !element.hasAttribute("aria-hidden"));
}

function restoreFocus(): void {
  const target = previousFocus;
  previousFocus = null;
  if (target?.isConnected) target.focus();
}

function requestCancel(): void {
  if (!props.busy) emit("cancel");
}

function requestKeep(): void {
  if (!props.busy) emit("keep");
}

function requestReplace(): void {
  if (!props.busy) emit("replace");
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    event.preventDefault();
    requestCancel();
    return;
  }
  if (event.key !== "Tab") return;
  const elements = focusableElements();
  if (!elements.length) {
    event.preventDefault();
    dialogRef.value?.focus();
    return;
  }
  const first = elements[0];
  const last = elements[elements.length - 1];
  const current = document.activeElement;
  if (event.shiftKey && (current === first || !dialogRef.value?.contains(current))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (current === last || !dialogRef.value?.contains(current))) {
    event.preventDefault();
    first.focus();
  }
}

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    await nextTick();
    cancelButtonRef.value?.focus();
    return;
  }
  await nextTick();
  restoreFocus();
}, { immediate: true });

onBeforeUnmount(restoreFocus);
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="memory-conflict-backdrop"
      data-memory-conflict-backdrop
      @click.self="requestCancel"
      @keydown="handleKeydown"
    >
      <section
        ref="dialogRef"
        class="memory-conflict-dialog"
        data-memory-conflict-dialog
        role="dialog"
        aria-modal="true"
        aria-labelledby="memory-conflict-title"
        aria-describedby="memory-conflict-description"
        tabindex="-1"
      >
        <span class="memory-conflict-icon" aria-hidden="true"><AlertTriangle :size="22" /></span>
        <div class="memory-conflict-copy">
          <h2 id="memory-conflict-title">记忆内容发生冲突</h2>
          <p id="memory-conflict-description">该字段已有已生效记忆。请选择保留旧值，或用新值替换；被替换的旧值会进入 30 天回收站。</p>
        </div>
        <div class="memory-conflict-values">
          <article class="memory-conflict-value memory-conflict-new">
            <small><ArrowRightLeft :size="13" />准备保存的新记忆</small>
            <strong>{{ candidate?.content || "—" }}</strong>
          </article>
          <article v-for="item in conflicts" :key="item.id" class="memory-conflict-value memory-conflict-old">
            <small><Archive :size="13" />当前已生效{{ item.fieldKey ? ` · ${item.fieldKey}` : "" }}</small>
            <strong>{{ item.content }}</strong>
          </article>
        </div>
        <p v-if="errorMessage" class="memory-conflict-error" role="alert">{{ errorMessage }}</p>
        <footer class="memory-conflict-actions">
          <button
            ref="cancelButtonRef"
            class="secondary-button"
            data-memory-conflict-cancel
            type="button"
            :disabled="busy"
            @click="requestCancel"
          ><X :size="15" />取消</button>
          <button
            class="keep-button"
            data-memory-conflict-keep
            type="button"
            :disabled="busy"
            @click="requestKeep"
          ><Archive :size="15" />保留旧值</button>
          <button
            class="replace-button"
            data-memory-conflict-replace
            type="button"
            :disabled="busy"
            @click="requestReplace"
          ><Check :size="15" />{{ busy ? "处理中…" : "用新值替换" }}</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.memory-conflict-backdrop { position: fixed; inset: 0; z-index: 102; display: grid; place-items: center; padding: 24px; background: rgb(18 35 27 / 58%); }
.memory-conflict-dialog { width: min(100%, 560px); display: grid; grid-template-columns: auto 1fr; gap: 14px; padding: 24px; border: 1px solid #e7d3be; border-radius: 16px; background: #fffdf9; box-shadow: 0 24px 56px rgb(21 43 31 / 28%); outline: none; }
.memory-conflict-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; color: #a5661c; background: #fff0d9; }
.memory-conflict-copy h2 { margin: 2px 0 8px; color: #21352a; font-size: 18px; }
.memory-conflict-copy p { margin: 0; color: #637267; line-height: 1.65; }
.memory-conflict-values { grid-column: 1 / -1; display: grid; gap: 8px; }
.memory-conflict-value { display: grid; gap: 5px; padding: 11px 12px; border: 1px solid #dbe6dd; border-radius: 9px; background: #fff; }
.memory-conflict-value small { display: flex; align-items: center; gap: 5px; color: #64766a; font-size: 12px; }
.memory-conflict-value strong { color: #21352a; line-height: 1.55; overflow-wrap: anywhere; }
.memory-conflict-new { border-color: #b9d9c1; background: #f2faf4; }
.memory-conflict-old { border-color: #ead9c6; background: #fffaf3; }
.memory-conflict-error { grid-column: 1 / -1; margin: 0; color: #b83d32; line-height: 1.55; }
.memory-conflict-actions { grid-column: 1 / -1; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10px; margin-top: 4px; }
.memory-conflict-actions button { display: inline-flex; min-width: 106px; min-height: 38px; align-items: center; justify-content: center; gap: 6px; }
.keep-button { border: 1px solid #c9d8cc; background: #fff; color: #395a46; }
.keep-button:hover:not(:disabled) { background: #f3f7f3; }
.replace-button { border: 1px solid #b83d32; background: #b83d32; color: #fff; }
.replace-button:hover:not(:disabled) { border-color: #962f27; background: #962f27; }
button:disabled { cursor: not-allowed; opacity: .62; }
@media (max-width: 600px) { .memory-conflict-backdrop { padding: 16px; } .memory-conflict-dialog { padding: 20px; } .memory-conflict-actions button { flex: 1 1 100%; } }
</style>
