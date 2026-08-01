import { afterEach, describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ConfirmDialog from "@/components/ConfirmDialog.vue";

function mountDialog(props = {}) {
  return mount(ConfirmDialog, {
    attachTo: document.body,
    props: {
      open: true,
      title: "移入回收站",
      description: "删除后将立即失效，并保留 30 天供恢复。",
      confirmLabel: "移入回收站",
      ...props,
    },
  });
}

function dialogElement(selector) {
  const element = document.body.querySelector(selector);
  if (!(element instanceof HTMLElement)) {
    throw new Error(`未找到确认弹窗元素：${selector}`);
  }
  return element;
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("ConfirmDialog", () => {
  it("supports focus trapping, Esc/backdrop cancellation, and focus restoration", async () => {
    const trigger = document.createElement("button");
    document.body.append(trigger);
    trigger.focus();

    const wrapper = mountDialog();
    await flushPromises();

    const cancelButton = dialogElement("[data-confirm-dialog-cancel]");
    const confirmButton = dialogElement("[data-confirm-dialog-confirm]");
    expect(document.activeElement).toBe(cancelButton);

    confirmButton.focus();
    confirmButton.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", bubbles: true }));
    expect(document.activeElement).toBe(cancelButton);

    dialogElement("[role=alertdialog]").dispatchEvent(
      new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
    );
    expect(wrapper.emitted("cancel")).toHaveLength(1);

    await wrapper.setProps({ open: false });
    await flushPromises();
    expect(document.activeElement).toBe(trigger);

    await wrapper.setProps({ open: true });
    await flushPromises();
    dialogElement("[data-confirm-dialog-backdrop]").click();
    expect(wrapper.emitted("cancel")).toHaveLength(2);
  });

  it("disables cancellation and duplicate confirmation while the request is in flight", async () => {
    const wrapper = mountDialog({ busy: true });
    await flushPromises();

    const cancelButton = dialogElement("[data-confirm-dialog-cancel]");
    const confirmButton = dialogElement("[data-confirm-dialog-confirm]");
    expect(cancelButton.disabled).toBe(true);
    expect(confirmButton.disabled).toBe(true);

    dialogElement("[role=alertdialog]").dispatchEvent(
      new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
    );
    dialogElement("[data-confirm-dialog-backdrop]").click();
    await confirmButton.click();

    expect(wrapper.emitted("cancel")).toBeUndefined();
    expect(wrapper.emitted("confirm")).toBeUndefined();
  });
});
