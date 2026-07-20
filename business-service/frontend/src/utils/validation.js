export function validateRegistrationAccount(form) {
  if (!form.username?.trim() || !form.password || !form.contactName?.trim() || !form.contactPhone?.trim()) {
    return "请完整填写账号、密码和联系人信息。";
  }
  if (form.password.length < 6) return "密码至少需要 6 位。";
  if (form.password !== form.confirmPassword) return "两次输入的密码不一致。";
  return "";
}

export function validatePasswordChange(form) {
  if (!form.currentPassword) return "请输入当前密码。";
  if (!form.newPassword || form.newPassword.length < 6) return "新密码至少需要 6 位。";
  if (form.newPassword !== form.confirmPassword) return "两次输入的新密码不一致。";
  return "";
}
