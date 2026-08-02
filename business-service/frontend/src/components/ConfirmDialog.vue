<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import { AlertTriangle } from "@lucide/vue";

const props = withDefaults(defineProps<{
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  busy?: boolean;
  errorMessage?: string;
}>(), {
  confirmLabel: "确认操作",
  busy: false,
  errorMessage: "",
});

const emit = defineEmits<{
  confirm: [];
  cancel: [];
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

function requestConfirm(): void {
  if (!props.busy) emit("confirm");
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
      class="confirm-dialog-backdrop"
      data-confirm-dialog-backdrop
      @click.self="requestCancel"
      @keydown="handleKeydown"
    >
      <section
        ref="dialogRef"
        class="confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
        tabindex="-1"
      >
        <span class="confirm-dialog-icon" aria-hidden="true"><AlertTriangle :size="22" /></span>
        <div class="confirm-dialog-copy">
          <h2 id="confirm-dialog-title">{{ title }}</h2>
          <p id="confirm-dialog-description">{{ description }}</p>
          <p v-if="errorMessage" class="confirm-dialog-error" role="alert">{{ errorMessage }}</p>
        </div>
        <footer class="confirm-dialog-actions">
          <button
            ref="cancelButtonRef"
            class="secondary-button"
            data-confirm-dialog-cancel
            type="button"
            :disabled="busy"
            @click="requestCancel"
          >
            取消
          </button>
          <button
            class="danger-button"
            data-confirm-dialog-confirm
            type="button"
            :disabled="busy"
            @click="requestConfirm"
          >
            {{ busy ? "处理中…" : confirmLabel }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(18 35 27 / 58%);
}

.confirm-dialog {
  width: min(100%, 440px);
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 14px;
  padding: 24px;
  border: 1px solid #e9cfca;
  border-radius: 16px;
  background: #fffdfb;
  box-shadow: 0 24px 56px rgb(21 43 31 / 28%);
  outline: none;
}

.confirm-dialog-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 12px;
  color: #b83d32;
  background: #fce8e4;
}

.confirm-dialog-copy h2 {
  margin: 2px 0 8px;
  color: #21352a;
  font-size: 18px;
}

.confirm-dialog-copy p {
  margin: 0;
  color: #637267;
  line-height: 1.65;
}

.confirm-dialog-copy .confirm-dialog-error {
  margin-top: 10px;
  color: #b83d32;
}

.confirm-dialog-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 6px;
}

.confirm-dialog-actions button {
  min-width: 96px;
}

.danger-button {
  border: 1px solid #b83d32;
  color: #fff;
  background: #b83d32;
}

.danger-button:hover:not(:disabled) {
  border-color: #962f27;
  background: #962f27;
}

button:disabled {
  cursor: not-allowed;
  opacity: .62;
}

@media (max-width: 600px) {
  .confirm-dialog-backdrop { padding: 16px; }
  .confirm-dialog { padding: 20px; }
  .confirm-dialog-actions button { flex: 1; }
}
</style>
