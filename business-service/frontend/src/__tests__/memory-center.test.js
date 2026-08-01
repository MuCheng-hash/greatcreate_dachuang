import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
}));

vi.mock("@/services/api", () => ({ api: apiMock }));

import MemoryCenter from "@/components/MemoryCenter.vue";

function dialogControl(action) {
  const element = document.body.querySelector(`[data-confirm-dialog-${action}]`);
  if (!(element instanceof HTMLButtonElement)) {
    throw new Error(`未找到确认弹窗按钮：${action}`);
  }
  return element;
}

function memory(overrides = {}) {
  return {
    id: "memory-1",
    memoryType: "PROFILE",
    fieldKey: null,
    content: "偏好结构化回答",
    status: "active",
    source: "profile_ui",
    createdAt: "2026-07-31T00:00:00Z",
    updatedAt: "2026-07-31T00:00:00Z",
    ...overrides,
  };
}

describe("memory center", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, "confirm").mockImplementation(() => {
      throw new Error("记忆中心不应调用浏览器原生确认框");
    });
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/memory-settings") {
        return { available: true, enabled: false, effectiveEnabled: false };
      }
      if (path === "/api/ai/memories?status=active") {
        return [
          memory({ id: "grade", fieldKey: "grade", content: "四年级" }),
          memory({ id: "style", fieldKey: "teaching_style", content: "项目式教学" }),
          memory({ id: "custom-active", memoryType: "TASK", content: "准备下周红色研学活动" }),
        ];
      }
      if (path === "/api/ai/memories?status=pending") {
        return [
          memory({ id: "pending-1", status: "pending", content: "可能偏好表格回答" }),
          memory({ id: "pending-2", status: "pending", content: "可能常教五年级" }),
        ];
      }
      if (path === "/api/ai/memories?status=deleted") {
        return [
          memory({ id: "deleted-1", status: "deleted", content: "已删除画像" }),
          memory({ id: "deleted-2", status: "deleted", content: "待永久删除" }),
        ];
      }
      return [];
    });
    apiMock.put.mockResolvedValue({ available: true, enabled: true, effectiveEnabled: true });
    apiMock.post.mockImplementation(async (path, body) => (
      path.endsWith("/confirm") || path.endsWith("/restore")
        ? memory({ id: path.split("/").at(-2), status: "active" })
        : memory({ id: "created", ...body })
    ));
    apiMock.patch.mockImplementation(async (path, body) => memory({
      id: path.split("/").at(-1),
      ...body,
    }));
    apiMock.delete.mockResolvedValue(undefined);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("shows availability, first-use guidance, five core fields, and saves settings and memories", async () => {
    const wrapper = mount(MemoryCenter);
    await flushPromises();

    expect(wrapper.text()).toContain("长期记忆与用户画像");
    expect(wrapper.text()).toContain("不会扫描开启前的历史会话");
    expect(wrapper.findAll("[data-memory-field]")).toHaveLength(5);
    expect(wrapper.get('[data-memory-field="grade"]').element.value).toBe("四年级");
    expect(wrapper.get('[data-memory-field="teaching_style"]').element.value).toBe("项目式教学");
    expect(wrapper.get("[data-memory-switch]").element.checked).toBe(false);

    await wrapper.get("[data-memory-switch]").setValue(true);
    await flushPromises();
    expect(apiMock.put).toHaveBeenCalledWith("/api/ai/memory-settings", { enabled: true });

    await wrapper.get('[data-memory-field="subject"]').setValue("道德与法治");
    await wrapper.get(".memory-profile-form").trigger("submit.prevent");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/memories", {
      memoryType: "PROFILE",
      fieldKey: "subject",
      content: "道德与法治",
    });

    await wrapper.get(".memory-create-type").setValue("TASK");
    await wrapper.get(".memory-create-content").setValue("本月完成红色研学方案");
    await wrapper.get(".memory-create-form").trigger("submit.prevent");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/memories", {
      memoryType: "TASK",
      fieldKey: null,
      content: "本月完成红色研学方案",
    });
  });

  it("disables activation when the server feature flag is unavailable", async () => {
    apiMock.get.mockImplementation(async (path) => (
      path === "/api/ai/memory-settings"
        ? { available: false, enabled: false, effectiveEnabled: false }
        : []
    ));

    const wrapper = mount(MemoryCenter);
    await flushPromises();

    expect(wrapper.text()).toContain("系统暂未开放长期记忆");
    expect(wrapper.get("[data-memory-switch]").attributes("disabled")).toBeDefined();
  });

  it("confirms, ignores, edits, recycles, restores, permanently deletes, and clears memories", async () => {
    const wrapper = mount(MemoryCenter, { attachTo: document.body });
    await flushPromises();

    await wrapper.get('[data-memory-status="pending"]').trigger("click");
    await wrapper.get('[data-memory-id="pending-1"] [data-action="confirm"]').trigger("click");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/memories/pending-1/confirm");

    await wrapper.get('[data-memory-id="pending-2"] [data-action="ignore"]').trigger("click");
    await flushPromises();
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/pending-2");

    await wrapper.get('[data-memory-status="active"]').trigger("click");
    await wrapper.get('[data-memory-id="custom-active"] [data-action="edit"]').trigger("click");
    await wrapper.get('[data-memory-id="custom-active"] textarea').setValue("下周完成红色研学活动");
    await wrapper.get('[data-memory-id="custom-active"] [data-action="save"]').trigger("click");
    await flushPromises();
    expect(apiMock.patch).toHaveBeenCalledWith("/api/ai/memories/custom-active", {
      content: "下周完成红色研学活动",
    });

    const recycleButton = wrapper.get('[data-memory-id="custom-active"] [data-action="delete"]');
    recycleButton.element.focus();
    await recycleButton.trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain("移入回收站");
    expect(apiMock.delete).not.toHaveBeenCalledWith("/api/ai/memories/custom-active");
    await dialogControl("cancel").click();
    await flushPromises();
    expect(document.activeElement).toBe(recycleButton.element);
    expect(apiMock.delete).not.toHaveBeenCalledWith("/api/ai/memories/custom-active");

    await recycleButton.trigger("click");
    await dialogControl("confirm").click();
    await flushPromises();
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/custom-active");

    await wrapper.get('[data-memory-status="deleted"]').trigger("click");
    await wrapper.get('[data-memory-id="deleted-1"] [data-action="restore"]').trigger("click");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/memories/deleted-1/restore");

    await wrapper.get('[data-memory-id="deleted-2"] [data-action="permanent"]').trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain("永久删除");
    await dialogControl("confirm").click();
    await flushPromises();
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/deleted-2/permanent");

    await wrapper.get("[data-action='clear']").trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain("清空回收站");
    await dialogControl("confirm").click();
    await flushPromises();
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/custom-active/permanent");
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/pending-2/permanent");
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it("keeps a destructive dialog open and prevents duplicate submission while its request is pending", async () => {
    let resolveRecycle;
    apiMock.delete.mockImplementation((path) => {
      if (path === "/api/ai/memories/custom-active") {
        return new Promise((resolve) => {
          resolveRecycle = resolve;
        });
      }
      return Promise.resolve(undefined);
    });
    const wrapper = mount(MemoryCenter, { attachTo: document.body });
    await flushPromises();

    await wrapper.get('[data-memory-id="custom-active"] [data-action="delete"]').trigger("click");
    await dialogControl("confirm").click();
    await flushPromises();

    const dialog = document.body.querySelector('[role="alertdialog"]');
    expect(dialog).not.toBeNull();
    expect(dialogControl("cancel").disabled).toBe(true);
    expect(dialogControl("confirm").disabled).toBe(true);
    dialog.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await dialogControl("confirm").click();
    expect(apiMock.delete).toHaveBeenCalledTimes(1);

    resolveRecycle();
    await flushPromises();
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull();
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it("keeps the dialog open and presents the request error when destructive confirmation fails", async () => {
    apiMock.delete.mockRejectedValueOnce(new Error("回收站服务暂不可用"));
    const wrapper = mount(MemoryCenter, { attachTo: document.body });
    await flushPromises();

    await wrapper.get('[data-memory-id="custom-active"] [data-action="delete"]').trigger("click");
    await dialogControl("confirm").click();
    await flushPromises();

    const dialog = document.body.querySelector('[role="alertdialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain("回收站服务暂不可用");
    expect(dialogControl("confirm").disabled).toBe(false);
    expect(window.confirm).not.toHaveBeenCalled();
  });

  it("freezes the bulk target list while waiting for confirmation", async () => {
    const wrapper = mount(MemoryCenter, { attachTo: document.body });
    await flushPromises();

    await wrapper.get("[data-action='clear']").trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain("3 条记忆");
    expect(wrapper.get('[data-memory-status="deleted"]').attributes("disabled")).toBeDefined();
    await dialogControl("confirm").click();
    await flushPromises();

    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/grade");
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/style");
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/custom-active");
    expect(apiMock.delete).toHaveBeenCalledTimes(3);
    expect(window.confirm).not.toHaveBeenCalled();
  });
});
