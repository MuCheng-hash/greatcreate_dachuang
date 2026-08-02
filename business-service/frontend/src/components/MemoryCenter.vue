<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  BrainCircuit,
  Check,
  Clock3,
  Pencil,
  Plus,
  RotateCcw,
  Save,
  ShieldAlert,
  Trash2,
  X,
} from "@lucide/vue";
import InlineNotice from "@/components/InlineNotice.vue";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import MemoryConflictDialog from "@/components/MemoryConflictDialog.vue";
import { memoryApi, memoryConflictPreviewFromError } from "@/services/memory";
import type {
  AgentMemoryConflictPreview,
  AgentMemoryItem,
  AgentMemorySetting,
  MemoryStatus,
  MemoryType,
} from "@/types/agent";

type CoreFieldKey =
  | "grade"
  | "subject"
  | "teaching_style"
  | "answer_format"
  | "lesson_duration";

interface CoreFieldDefinition {
  key: CoreFieldKey;
  label: string;
  placeholder: string;
}

type MemoryConfirmationKind = "recycle" | "permanent" | "clear";

interface MemoryConfirmation {
  kind: MemoryConfirmationKind;
  title: string;
  description: string;
  confirmLabel: string;
  item?: AgentMemoryItem;
  sourceStatus?: MemoryStatus;
  targets?: AgentMemoryItem[];
}

interface MemoryConflictResolution {
  candidate: AgentMemoryItem;
  conflicts: AgentMemoryItem[];
  replace: () => Promise<unknown>;
  keep: () => Promise<unknown>;
  replaceMessage: string;
  keepMessage: string;
}

const coreFields: CoreFieldDefinition[] = [
  { key: "grade", label: "常教年级", placeholder: "例如：四年级" },
  { key: "subject", label: "常教学科", placeholder: "例如：道德与法治" },
  { key: "teaching_style", label: "教学风格", placeholder: "例如：项目式教学、启发式" },
  { key: "answer_format", label: "回答格式", placeholder: "例如：先结论后步骤、优先表格" },
  { key: "lesson_duration", label: "常用课时", placeholder: "例如：40 分钟" },
];

const statusTabs: Array<{ status: MemoryStatus; label: string; hint: string }> = [
  { status: "pending", label: "待确认", hint: "确认后才会影响回答" },
  { status: "active", label: "已生效", hint: "画像长期保留，阶段任务默认保留 90 天" },
  { status: "deleted", label: "回收站", hint: "删除后保留 30 天，可在到期前恢复" },
];

const setting = ref<AgentMemorySetting | null>(null);
const loading = ref(true);
const settingSaving = ref(false);
const profileSaving = ref(false);
const customSaving = ref(false);
const busyId = ref("");
const confirmation = ref<MemoryConfirmation | null>(null);
const confirmationPending = ref(false);
const confirmationError = ref("");
const memoryConflict = ref<MemoryConflictResolution | null>(null);
const memoryConflictBusy = ref(false);
const memoryConflictError = ref("");
const activeStatus = ref<MemoryStatus>("active");
const editingId = ref("");
const editingContent = ref("");
const notice = reactive<{ tone: "info" | "success" | "error"; text: string }>({
  tone: "info",
  text: "",
});
const profileValues = reactive<Record<CoreFieldKey, string>>({
  grade: "",
  subject: "",
  teaching_style: "",
  answer_format: "",
  lesson_duration: "",
});
const customMemory = reactive<{ memoryType: MemoryType; content: string }>({
  memoryType: "PROFILE",
  content: "",
});
const items = reactive<Record<MemoryStatus, AgentMemoryItem[]>>({
  pending: [],
  active: [],
  deleted: [],
});

const currentItems = computed(() => items[activeStatus.value]);
const currentTab = computed(() => statusTabs.find((item) => item.status === activeStatus.value)!);
const isInteractionLocked = computed(() => Boolean(busyId.value) || Boolean(confirmation.value) || Boolean(memoryConflict.value));

onMounted(loadMemoryCenter);

async function loadMemoryCenter(): Promise<void> {
  loading.value = true;
  notice.text = "";
  try {
    setting.value = await memoryApi.setting();
    await refreshMemoryLists();
  } catch (error) {
    notice.tone = "error";
    notice.text = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

async function refreshMemoryLists(): Promise<void> {
  const [pending, active, deleted] = await Promise.all([
    memoryApi.list("pending"),
    memoryApi.list("active"),
    memoryApi.list("deleted"),
  ]);
  items.pending = pending;
  items.active = active;
  items.deleted = deleted;
  syncCoreFields();
}

function normalizeCoreFieldKey(fieldKey: string | null | undefined): CoreFieldKey | null {
  const canonical = fieldKey === "response_format" ? "answer_format" : fieldKey;
  return coreFields.some((field) => field.key === canonical) ? canonical as CoreFieldKey : null;
}

function syncCoreFields(): void {
  for (const field of coreFields) profileValues[field.key] = "";
  for (const item of items.active) {
    if (item.memoryType !== "PROFILE" || !item.fieldKey) continue;
    const fieldKey = normalizeCoreFieldKey(item.fieldKey);
    if (fieldKey) profileValues[fieldKey] = item.content;
  }
}

function coreMemory(fieldKey: CoreFieldKey): AgentMemoryItem | undefined {
  return items.active.find((item) => item.memoryType === "PROFILE" && normalizeCoreFieldKey(item.fieldKey) === fieldKey);
}

function previewHasConflicts(preview: AgentMemoryConflictPreview): boolean {
  return !preview.duplicate && Array.isArray(preview.conflicts) && preview.conflicts.length > 0;
}

function previewCandidate(preview: AgentMemoryConflictPreview, fallback: AgentMemoryItem): AgentMemoryItem {
  return preview.candidate?.content ? preview.candidate : fallback;
}

function openMemoryConflict(nextConflict: MemoryConflictResolution): void {
  if (memoryConflict.value || memoryConflictBusy.value) return;
  memoryConflictError.value = "";
  memoryConflict.value = nextConflict;
}

function closeMemoryConflict(): void {
  if (memoryConflictBusy.value) return;
  memoryConflictError.value = "";
  memoryConflict.value = null;
}

async function resolveMemoryConflict(action: "replace" | "keep"): Promise<void> {
  const conflict = memoryConflict.value;
  if (!conflict || memoryConflictBusy.value) return;
  memoryConflictBusy.value = true;
  memoryConflictError.value = "";
  try {
    if (action === "replace") await conflict.replace();
    else await conflict.keep();
    await refreshMemoryLists();
    memoryConflict.value = null;
    notice.tone = "success";
    notice.text = action === "replace" ? conflict.replaceMessage : conflict.keepMessage;
  } catch (error) {
    memoryConflictError.value = errorMessage(error);
  } finally {
    memoryConflictBusy.value = false;
  }
}

async function runConflictAwareMutation(
  fallbackCandidate: AgentMemoryItem,
  mutation: (replaceConflicts: boolean) => Promise<unknown>,
  replaceMessage: string,
  keepMessage: string,
): Promise<boolean> {
  try {
    await mutation(false);
    return true;
  } catch (error) {
    const preview = memoryConflictPreviewFromError(error);
    if (preview?.duplicate) {
      await refreshMemoryLists();
      notice.tone = "info";
      notice.text = "该记忆已存在，未创建重复的已生效记忆。";
      return false;
    }
    if (!preview || !previewHasConflicts(preview)) throw error;
    openMemoryConflict({
      candidate: previewCandidate(preview, fallbackCandidate),
      conflicts: preview.conflicts,
      replace: () => mutation(true),
      keep: async () => undefined,
      replaceMessage,
      keepMessage,
    });
    return false;
  }
}

async function toggleMemory(event: Event): Promise<void> {
  if (!setting.value?.available || settingSaving.value) return;
  const input = event.target as HTMLInputElement;
  const previous = setting.value.enabled;
  settingSaving.value = true;
  notice.text = "";
  try {
    setting.value = await memoryApi.updateSetting(input.checked);
    notice.tone = "success";
    notice.text = input.checked
      ? "长期记忆已开启，新对话会按规则提取并使用已确认记忆。"
      : "长期记忆已关闭，已有数据会保留，但不会提取、召回或注入。";
  } catch (error) {
    input.checked = previous;
    notice.tone = "error";
    notice.text = errorMessage(error);
  } finally {
    settingSaving.value = false;
  }
}

async function saveProfileMemories(): Promise<void> {
  if (!setting.value?.available || profileSaving.value) return;
  profileSaving.value = true;
  notice.text = "";
  try {
    for (const field of coreFields) {
      const content = profileValues[field.key].trim();
      const existing = coreMemory(field.key);
      if (content && existing && content !== existing.content) {
        const completed = await runConflictAwareMutation(
          { ...existing, fieldKey: field.key, content, status: "active" },
          (replaceConflicts) => memoryApi.update(existing.id, replaceConflicts ? { content, replaceConflicts: true } : { content }),
          "已用新画像替换同字段旧值，旧值已移入回收站。",
          "已保留当前画像，未保存新的字段值。",
        );
        if (!completed) return;
      } else if (content && !existing) {
        const candidate: AgentMemoryItem = {
          id: `profile-${field.key}`,
          memoryType: "PROFILE",
          fieldKey: field.key,
          content,
          status: "active",
          source: "profile_ui",
        };
        const completed = await runConflictAwareMutation(
          candidate,
          (replaceConflicts) => memoryApi.create({
            memoryType: "PROFILE",
            fieldKey: field.key,
            content,
            ...(replaceConflicts ? { replaceConflicts: true } : {}),
          }),
          "已用新画像替换同字段旧值，旧值已移入回收站。",
          "已保留当前画像，未保存新的字段值。",
        );
        if (!completed) return;
      } else if (!content && existing) {
        await memoryApi.recycle(existing.id);
      }
    }
    await refreshMemoryLists();
    notice.tone = "success";
    notice.text = "核心用户画像已保存。当前对话中的明确要求仍会优先于这些偏好。";
  } catch (error) {
    notice.tone = "error";
    notice.text = errorMessage(error);
  } finally {
    profileSaving.value = false;
  }
}

async function createCustomMemory(): Promise<void> {
  const content = customMemory.content.trim();
  if (!setting.value?.available || customSaving.value) return;
  if (!content) {
    notice.tone = "error";
    notice.text = "请输入要保存的记忆内容。";
    return;
  }
  customSaving.value = true;
  notice.text = "";
  try {
    await memoryApi.create({
      memoryType: customMemory.memoryType,
      fieldKey: null,
      content,
    });
    await refreshMemoryLists();
    customMemory.content = "";
    activeStatus.value = "active";
    notice.tone = "success";
    notice.text = customMemory.memoryType === "PROFILE"
      ? "稳定记忆已保存。" : "阶段任务已保存，默认 90 天后过期。";
  } catch (error) {
    notice.tone = "error";
    notice.text = errorMessage(error);
  } finally {
    customSaving.value = false;
  }
}

async function confirmMemory(item: AgentMemoryItem): Promise<void> {
  await runItemAction(item.id, async () => {
    const preview = await memoryApi.confirmationPreview(item.id);
    if (preview.duplicate) {
      await memoryApi.confirm(item.id);
      await refreshMemoryLists();
      notice.tone = "success";
      notice.text = "该记忆已存在，已保留原有值并将候选移入回收站。";
      return;
    }
    if (previewHasConflicts(preview)) {
      openMemoryConflict({
        candidate: previewCandidate(preview, item),
        conflicts: preview.conflicts,
        replace: () => memoryApi.confirm(item.id, true),
        keep: () => memoryApi.recycle(item.id),
        replaceMessage: "候选记忆已确认，旧值已移入回收站。",
        keepMessage: "已保留了原有记忆，这条候选已移入回收站。",
      });
      return;
    }
    await memoryApi.confirm(item.id);
    await refreshMemoryLists();
    notice.tone = "success";
    notice.text = "候选记忆已确认，后续新对话可按规则使用。";
  });
}

async function recycleMemoryItem(item: AgentMemoryItem, successText: string): Promise<void> {
  await executeItemAction(item.id, async () => {
    const recycled = await memoryApi.recycle(item.id);
    replaceMemory(recycled || { ...item, status: "deleted" });
    syncCoreFields();
    notice.tone = "success";
    notice.text = successText;
  });
}

async function ignoreMemory(item: AgentMemoryItem): Promise<void> {
  try {
    await recycleMemoryItem(item, "候选记忆已忽略，可在回收站中恢复。");
  } catch (error) {
    showActionError(error);
  }
}

async function restoreMemory(item: AgentMemoryItem): Promise<void> {
  await runItemAction(item.id, async () => {
    const preview = await memoryApi.confirmationPreview(item.id);
    if (preview.duplicate) {
      await memoryApi.restore(item.id);
      await refreshMemoryLists();
      notice.tone = "success";
      notice.text = "该记忆已存在，已保留原有值，回收站条目未重复生效。";
      return;
    }
    if (previewHasConflicts(preview)) {
      openMemoryConflict({
        candidate: previewCandidate(preview, item),
        conflicts: preview.conflicts,
        replace: () => memoryApi.restore(item.id, true),
        keep: async () => undefined,
        replaceMessage: "记忆已恢复并替换同字段旧值，旧值已移入回收站。",
        keepMessage: "已保留当前已生效记忆，回收站条目未恢复。",
      });
      return;
    }
    await memoryApi.restore(item.id);
    await refreshMemoryLists();
    notice.tone = "success";
    notice.text = "记忆已恢复并重新生效。";
  });
}

async function permanentlyDeleteMemory(item: AgentMemoryItem): Promise<void> {
  await executeItemAction(item.id, async () => {
    await memoryApi.permanentlyDelete(item.id);
    removeMemory(item.id);
    notice.tone = "success";
    notice.text = "记忆已永久删除。";
  });
}

function startEdit(item: AgentMemoryItem): void {
  editingId.value = item.id;
  editingContent.value = item.content;
}

function cancelEdit(): void {
  editingId.value = "";
  editingContent.value = "";
}

async function saveEdit(item: AgentMemoryItem): Promise<void> {
  const content = editingContent.value.trim();
  if (!content) {
    notice.tone = "error";
    notice.text = "记忆内容不能为空。";
    return;
  }
  await runItemAction(item.id, async () => {
    const completed = await runConflictAwareMutation(
      { ...item, content },
      (replaceConflicts) => memoryApi.update(item.id, replaceConflicts ? { content, replaceConflicts: true } : { content }),
      "已用新值替换同字段旧记忆，旧值已移入回收站。",
      "已保留当前记忆，未保存新的内容。",
    );
    if (!completed) return;
    await refreshMemoryLists();
    cancelEdit();
    notice.tone = "success";
    notice.text = "记忆内容已更新。";
  });
}

async function clearMemoryStatus(sourceStatus: MemoryStatus, targets: AgentMemoryItem[]): Promise<void> {
  if (!targets.length || busyId.value) return;
  busyId.value = "clear";
  notice.text = "";
  try {
    if (sourceStatus === "deleted") {
      await Promise.all(targets.map((item) => memoryApi.permanentlyDelete(item.id)));
      const targetIds = new Set(targets.map((item) => item.id));
      items.deleted = items.deleted.filter((item) => !targetIds.has(item.id));
    } else {
      const recycled = await Promise.all(targets.map((item) => memoryApi.recycle(item.id)));
      const targetIds = new Set(targets.map((item) => item.id));
      items[sourceStatus] = items[sourceStatus].filter((item) => !targetIds.has(item.id));
      for (let index = 0; index < targets.length; index += 1) {
        replaceMemory(recycled[index] || { ...targets[index], status: "deleted" });
      }
    }
    syncCoreFields();
    notice.tone = "success";
    notice.text = sourceStatus === "deleted" ? "回收站已清空。" : "当前记忆已全部移入回收站。";
  } finally {
    busyId.value = "";
  }
}

function openConfirmation(nextConfirmation: MemoryConfirmation): void {
  if (isInteractionLocked.value) return;
  confirmationError.value = "";
  confirmation.value = nextConfirmation;
}

function requestRecycleMemory(item: AgentMemoryItem): void {
  openConfirmation({
    kind: "recycle",
    item,
    title: "移入回收站",
    description: "删除后该记忆会立即失效，并进入 30 天回收站；到期前可以恢复。",
    confirmLabel: "移入回收站",
  });
}

function requestPermanentDeletion(item: AgentMemoryItem): void {
  openConfirmation({
    kind: "permanent",
    item,
    title: "永久删除记忆",
    description: "永久删除后无法恢复，该记忆将从回收站彻底清除。",
    confirmLabel: "永久删除",
  });
}

function requestClearCurrentStatus(): void {
  const sourceStatus = activeStatus.value;
  const targets = [...currentItems.value];
  if (!targets.length) return;
  const isRecycle = sourceStatus !== "deleted";
  openConfirmation({
    kind: "clear",
    sourceStatus,
    targets,
    title: isRecycle ? "清空当前列表" : "清空回收站",
    description: isRecycle
      ? `将当前列表中的 ${targets.length} 条记忆全部移入回收站；它们会立即失效，并可在 30 天内恢复。`
      : `将永久删除回收站中的 ${targets.length} 条记忆，此操作不可恢复。`,
    confirmLabel: isRecycle ? "移入回收站" : "永久删除",
  });
}

function closeConfirmation(): void {
  if (confirmationPending.value) return;
  confirmationError.value = "";
  confirmation.value = null;
}

async function confirmDestructiveAction(): Promise<void> {
  const action = confirmation.value;
  if (!action || confirmationPending.value) return;
  confirmationPending.value = true;
  confirmationError.value = "";
  try {
    if (action.kind === "recycle" && action.item) {
      await recycleMemoryItem(action.item, "记忆已移入回收站。");
    } else if (action.kind === "permanent" && action.item) {
      await permanentlyDeleteMemory(action.item);
    } else if (action.kind === "clear" && action.sourceStatus && action.targets) {
      await clearMemoryStatus(action.sourceStatus, action.targets);
    }
    confirmation.value = null;
  } catch (error) {
    confirmationError.value = errorMessage(error);
  } finally {
    confirmationPending.value = false;
  }
}

async function executeItemAction(id: string, action: () => Promise<void>): Promise<void> {
  if (busyId.value) throw new Error("当前已有记忆操作正在进行");
  busyId.value = id;
  notice.text = "";
  try {
    await action();
  } finally {
    busyId.value = "";
  }
}

function showActionError(error: unknown): void {
  notice.tone = "error";
  notice.text = errorMessage(error);
}

async function runItemAction(id: string, action: () => Promise<void>): Promise<void> {
  try {
    await executeItemAction(id, action);
  } catch (error) {
    showActionError(error);
  }
}

function replaceMemory(item: AgentMemoryItem): void {
  removeMemory(item.id);
  items[item.status].unshift(item);
}

function removeMemory(id: string): void {
  for (const status of ["pending", "active", "deleted"] as MemoryStatus[]) {
    items[status] = items[status].filter((item) => item.id !== id);
  }
}

function typeLabel(memoryType: MemoryType): string {
  return memoryType === "PROFILE" ? "稳定画像" : "阶段任务";
}

function sourceLabel(source: string): string {
  const labels: Record<string, string> = {
    explicit_chat: "对话中明确保存",
    inferred_chat: "对话推断",
    profile_ui: "个人中心",
    teaching_plan: "教学方案推断",
  };
  return labels[source] || "未知来源";
}

function lifecycleText(item: AgentMemoryItem): string {
  if (item.status === "pending" && item.expiresAt) return `候选保留至 ${formatDate(item.expiresAt)}`;
  if (item.status === "deleted" && item.purgeAfter) return `预计 ${formatDate(item.purgeAfter)} 永久清理`;
  if (item.memoryType === "TASK" && item.expiresAt) return `任务有效至 ${formatDate(item.expiresAt)}`;
  return item.memoryType === "PROFILE" ? "稳定画像不过期" : "阶段任务默认保留 90 天";
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium" }).format(date);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "记忆服务请求失败";
}
</script>

<template>
  <section class="page-panel memory-center">
    <div class="panel-header memory-header">
      <div>
        <h2><BrainCircuit :size="21" />长期记忆与用户画像</h2>
        <p>跨会话保存教学偏好和阶段任务；当前明确输入始终优先。</p>
      </div>
      <label class="memory-switch">
        <input
          data-memory-switch
          type="checkbox"
          :checked="Boolean(setting?.enabled && setting?.available)"
          :disabled="loading || settingSaving || !setting?.available"
          @change="toggleMemory"
        />
        <span aria-hidden="true"></span>
        {{ settingSaving ? "保存中" : !setting?.available ? "系统未开放" : setting?.enabled ? "已开启" : "未开启" }}
      </label>
    </div>

    <div class="panel-body memory-body">
      <div v-if="loading" class="memory-loading">正在读取记忆设置...</div>
      <template v-else>
        <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>

        <InlineNotice v-if="setting && !setting.available" tone="info">
          {{ setting.enabled
            ? "系统暂未开放长期记忆。您的个人设置已保存为“开启”，全局开放后会自动生效。"
            : "系统暂未开放长期记忆。全局开关开启后，你可以在这里主动启用。" }}
        </InlineNotice>

        <div v-else-if="setting && !setting.enabled" class="memory-first-use">
          <BrainCircuit :size="22" />
          <div>
            <strong>首次启用前请了解</strong>
            <p>开启后只处理新的对话，不会扫描开启前的历史会话。明确说“记住……”会直接保存；系统推断的内容必须经你确认后才会生效。</p>
          </div>
        </div>

        <div class="memory-privacy">
          <ShieldAlert :size="17" />
          <span>请勿保存密码、令牌、密钥、身份证号、电话或精确住址。关闭功能会保留数据，但停止提取、召回和注入。</span>
        </div>

        <form class="memory-profile-form" @submit.prevent="saveProfileMemories">
          <div class="memory-section-heading">
            <div><h3>核心用户画像</h3><p>这些稳定偏好会用于智能问答和教学方案。</p></div>
            <button class="secondary-button" type="submit" :disabled="profileSaving || !setting?.available">
              <Save :size="16" />{{ profileSaving ? "保存中" : "保存画像" }}
            </button>
          </div>
          <div class="memory-profile-grid">
            <label v-for="field in coreFields" :key="field.key">
              {{ field.label }}
              <input
                v-model="profileValues[field.key]"
                :data-memory-field="field.key"
                :placeholder="field.placeholder"
                maxlength="1000"
                :disabled="!setting?.available"
              />
            </label>
          </div>
        </form>

        <form class="memory-create-form" @submit.prevent="createCustomMemory">
          <div class="memory-section-heading">
            <div><h3>自定义记忆</h3><p>稳定画像长期保留；阶段任务默认 90 天后过期。</p></div>
          </div>
          <div class="memory-create-row">
            <select v-model="customMemory.memoryType" class="memory-create-type" :disabled="!setting?.available">
              <option value="PROFILE">稳定画像</option>
              <option value="TASK">阶段任务</option>
            </select>
            <input
              v-model="customMemory.content"
              class="memory-create-content"
              maxlength="1000"
              placeholder="例如：回答时先给结论，再列操作步骤"
              :disabled="!setting?.available"
            />
            <button class="primary-button" type="submit" :disabled="customSaving || !setting?.available">
              <Plus :size="16" />{{ customSaving ? "保存中" : "添加" }}
            </button>
          </div>
        </form>

        <section class="memory-list-section">
          <div class="memory-tabs" role="tablist" aria-label="记忆状态">
            <button
              v-for="tab in statusTabs"
              :key="tab.status"
              type="button"
              role="tab"
               :aria-selected="activeStatus === tab.status"
               :class="{ active: activeStatus === tab.status }"
               :data-memory-status="tab.status"
               :disabled="isInteractionLocked"
               @click="activeStatus = tab.status"
            >
              {{ tab.label }} <span>{{ items[tab.status].length }}</span>
            </button>
          </div>

          <div class="memory-list-heading">
            <p>{{ currentTab.hint }}</p>
            <button
              class="text-button memory-clear"
              data-action="clear"
              type="button"
              :disabled="!currentItems.length || isInteractionLocked"
              @click="requestClearCurrentStatus"
            >
              <Trash2 :size="14" />{{ activeStatus === "deleted" ? "清空回收站" : "清空当前列表" }}
            </button>
          </div>

          <div v-if="!currentItems.length" class="memory-empty">当前没有{{ currentTab.label }}记忆。</div>
          <article
            v-for="item in currentItems"
            v-else
            :key="item.id"
            class="memory-item"
            :data-memory-id="item.id"
          >
            <div class="memory-item-main">
              <div class="memory-item-labels">
                <span class="memory-type" :class="item.memoryType.toLowerCase()">{{ typeLabel(item.memoryType) }}</span>
                <span v-if="item.fieldKey" class="memory-field-key">{{ item.fieldKey }}</span>
                <span>{{ sourceLabel(item.source) }}</span>
              </div>
              <template v-if="editingId === item.id">
                <textarea v-model="editingContent" maxlength="1000" rows="3"></textarea>
              </template>
              <p v-else>{{ item.content }}</p>
              <small><Clock3 :size="13" />{{ lifecycleText(item) }}</small>
            </div>

            <div class="memory-item-actions">
              <template v-if="editingId === item.id">
                <button data-action="save" type="button" :disabled="isInteractionLocked" @click="saveEdit(item)">
                  <Check :size="14" />保存
                </button>
                <button data-action="cancel" type="button" :disabled="isInteractionLocked" @click="cancelEdit"><X :size="14" />取消</button>
              </template>
              <template v-else-if="item.status === 'pending'">
                <button data-action="confirm" type="button" :disabled="isInteractionLocked" @click="confirmMemory(item)">
                  <Check :size="14" />确认
                </button>
                <button data-action="ignore" type="button" :disabled="isInteractionLocked" @click="ignoreMemory(item)">
                  <X :size="14" />忽略
                </button>
              </template>
              <template v-else-if="item.status === 'active'">
                <button data-action="edit" type="button" :disabled="isInteractionLocked" @click="startEdit(item)">
                  <Pencil :size="14" />编辑
                </button>
                <button data-action="delete" type="button" :disabled="isInteractionLocked" @click="requestRecycleMemory(item)">
                  <Trash2 :size="14" />删除
                </button>
              </template>
              <template v-else>
                <button data-action="restore" type="button" :disabled="isInteractionLocked" @click="restoreMemory(item)">
                  <RotateCcw :size="14" />恢复
                </button>
                <button class="danger" data-action="permanent" type="button" :disabled="isInteractionLocked" @click="requestPermanentDeletion(item)">
                  <Trash2 :size="14" />永久删除
                </button>
              </template>
            </div>
          </article>
        </section>
      </template>
    </div>
    <ConfirmDialog
      :open="Boolean(confirmation)"
      :title="confirmation?.title || ''"
      :description="confirmation?.description || ''"
      :confirm-label="confirmation?.confirmLabel || ''"
      :busy="confirmationPending"
      :error-message="confirmationError"
      @cancel="closeConfirmation"
      @confirm="confirmDestructiveAction"
    />
    <MemoryConflictDialog
      :open="Boolean(memoryConflict)"
      :candidate="memoryConflict?.candidate || null"
      :conflicts="memoryConflict?.conflicts || []"
      :busy="memoryConflictBusy"
      :error-message="memoryConflictError"
      @cancel="closeMemoryConflict"
      @keep="resolveMemoryConflict('keep')"
      @replace="resolveMemoryConflict('replace')"
    />
  </section>
</template>

<style scoped>
.memory-center { overflow: hidden; }
.memory-header { align-items: center; }
.memory-header h2 { display: flex; align-items: center; gap: 8px; }
.memory-header h2 svg { color: var(--green); }
.memory-switch { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: 13px; font-weight: 700; cursor: pointer; }
.memory-switch input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.memory-switch > span { position: relative; width: 42px; height: 23px; border-radius: 999px; background: #cfd5d0; transition: background 160ms ease; }
.memory-switch > span::after { content: ""; position: absolute; top: 3px; left: 3px; width: 17px; height: 17px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgb(0 0 0 / 20%); transition: transform 160ms ease; }
.memory-switch input:checked + span { background: var(--green); }
.memory-switch input:checked + span::after { transform: translateX(19px); }
.memory-switch input:disabled + span { opacity: .55; }
.memory-body { display: grid; gap: 18px; }
.memory-loading, .memory-empty { padding: 24px; border: 1px dashed var(--line); border-radius: 8px; color: var(--muted); text-align: center; }
.memory-first-use, .memory-privacy { display: flex; align-items: flex-start; gap: 11px; border-radius: 8px; }
.memory-first-use { padding: 15px; background: var(--green-soft); color: #315f47; }
.memory-first-use svg, .memory-privacy svg { flex: none; margin-top: 2px; }
.memory-first-use strong { display: block; margin-bottom: 4px; }
.memory-first-use p { margin: 0; color: #526158; font-size: 13px; line-height: 1.7; }
.memory-privacy { padding: 11px 13px; border: 1px solid #ead6a4; background: #fff9e8; color: #75602a; font-size: 12px; line-height: 1.65; }
.memory-profile-form, .memory-create-form, .memory-list-section { display: grid; gap: 13px; padding-top: 18px; border-top: 1px solid var(--line); }
.memory-section-heading, .memory-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.memory-section-heading h3 { margin: 0; font-size: 16px; }
.memory-section-heading p, .memory-list-heading p { margin: 4px 0 0; color: var(--muted); font-size: 12px; }
.memory-profile-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px; }
.memory-profile-grid label { display: grid; gap: 6px; color: #435047; font-size: 12px; font-weight: 650; }
.memory-profile-grid label:last-child { grid-column: 1 / -1; }
.memory-create-row { display: grid; grid-template-columns: 140px minmax(0,1fr) auto; gap: 9px; }
.memory-create-row select, .memory-create-row input { min-width: 0; }
.memory-tabs { display: flex; gap: 5px; padding: 4px; border-radius: 8px; background: #f1f3f0; }
.memory-tabs button { display: flex; flex: 1; min-height: 36px; align-items: center; justify-content: center; gap: 6px; border: 0; border-radius: 6px; background: transparent; color: var(--muted); cursor: pointer; }
.memory-tabs button.active { background: #fff; color: var(--green); box-shadow: 0 1px 4px rgb(0 0 0 / 8%); font-weight: 700; }
.memory-tabs button span { min-width: 20px; padding: 2px 5px; border-radius: 999px; background: #e4e8e4; font-size: 11px; }
.memory-clear { margin: 0; color: var(--muted); }
.memory-item { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 16px; align-items: center; padding: 14px; border: 1px solid var(--line); border-radius: 8px; }
.memory-item-main { min-width: 0; }
.memory-item-labels { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.memory-type, .memory-field-key { padding: 3px 7px; border-radius: 999px; background: #eef1ee; }
.memory-type.profile { background: var(--green-soft); color: var(--green); }
.memory-type.task { background: #fff2df; color: #8a5d17; }
.memory-field-key { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.memory-item p { margin: 8px 0; color: var(--text); line-height: 1.65; overflow-wrap: anywhere; }
.memory-item textarea { width: 100%; margin-top: 9px; resize: vertical; }
.memory-item small { display: flex; align-items: center; gap: 5px; color: var(--muted); }
.memory-item-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.memory-item-actions button { display: inline-flex; min-height: 32px; align-items: center; gap: 5px; padding: 0 9px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--green); cursor: pointer; }
.memory-item-actions button:hover:not(:disabled) { background: var(--green-soft); }
.memory-item-actions button.danger { color: var(--red); }
.memory-item-actions button:disabled { cursor: not-allowed; opacity: .5; }
@media (max-width: 700px) {
  .memory-header, .memory-section-heading, .memory-list-heading { align-items: flex-start; flex-direction: column; }
  .memory-profile-grid { grid-template-columns: 1fr; }
  .memory-profile-grid label:last-child { grid-column: auto; }
  .memory-create-row, .memory-item { grid-template-columns: 1fr; }
  .memory-item-actions { justify-content: flex-start; }
}
</style>
