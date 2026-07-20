import { describe, expect, it } from "vitest";
import { validatePasswordChange, validateRegistrationAccount } from "@/utils/validation";

describe("registration account validation", () => {
  it("requires all account and contact fields", () => {
    expect(validateRegistrationAccount({ username: "", password: "", contactName: "", contactPhone: "" }))
      .toBe("请完整填写账号、密码和联系人信息。");
  });

  it("rejects mismatched confirmation", () => {
    expect(validateRegistrationAccount({ username: "school", password: "123456", confirmPassword: "654321", contactName: "李老师", contactPhone: "13800000000" }))
      .toBe("两次输入的密码不一致。");
  });

  it("accepts a complete account step", () => {
    expect(validateRegistrationAccount({ username: "school", password: "123456", confirmPassword: "123456", contactName: "李老师", contactPhone: "13800000000" })).toBe("");
  });
});

describe("password change validation", () => {
  it("requires the current password", () => {
    expect(validatePasswordChange({ currentPassword: "", newPassword: "123456", confirmPassword: "123456" })).toBe("请输入当前密码。");
  });

  it("accepts matching passwords with six characters", () => {
    expect(validatePasswordChange({ currentPassword: "old123", newPassword: "new123", confirmPassword: "new123" })).toBe("");
  });
});
