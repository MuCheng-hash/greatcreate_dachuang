<script setup>
import { onMounted, reactive, ref } from "vue";
import { Building2, KeyRound, Save, ShieldCheck, UserRound } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";
import { useSchoolStore } from "@/stores/school";
import { validatePasswordChange } from "@/utils/validation";

const auth = useAuthStore();
const schoolStore = useSchoolStore();
const profile = reactive({ displayName: "", contactName: "", contactPhone: "" });
const password = reactive({ currentPassword: "", newPassword: "", confirmPassword: "" });
const profileSaving = ref(false);
const passwordSaving = ref(false);
const profileNotice = reactive({ tone: "", text: "" });
const passwordNotice = reactive({ tone: "", text: "" });

onMounted(async () => {
  await schoolStore.load();
  syncProfile();
});

function syncProfile() {
  profile.displayName = auth.user?.displayName || "";
  profile.contactName = auth.user?.contactName || "";
  profile.contactPhone = auth.user?.contactPhone || "";
}

async function saveProfile() {
  profileNotice.text = "";
  profileSaving.value = true;
  try {
    const user = await api.put("/api/auth/profile", profile);
    auth.setUser(user);
    syncProfile();
    profileNotice.tone = "success"; profileNotice.text = "个人资料已更新。";
  } catch (error) {
    profileNotice.tone = "error"; profileNotice.text = error.message;
  } finally {
    profileSaving.value = false;
  }
}

async function changePassword() {
  passwordNotice.text = "";
  const validationError = validatePasswordChange(password);
  if (validationError) { passwordNotice.tone = "error"; passwordNotice.text = validationError; return; }
  passwordSaving.value = true;
  try {
    await api.put("/api/auth/password", { currentPassword: password.currentPassword, newPassword: password.newPassword });
    password.currentPassword = ""; password.newPassword = ""; password.confirmPassword = "";
    passwordNotice.tone = "success"; passwordNotice.text = "密码已修改，当前登录状态保持不变。";
  } catch (error) {
    passwordNotice.tone = "error"; passwordNotice.text = error.message;
  } finally {
    passwordSaving.value = false;
  }
}

function formatDate(value) {
  if (!value) return "暂无记录";
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
</script>

<template>
  <AppShell title="个人中心" subtitle="管理账号联系人信息并查看绑定学校资料">
    <LoadingBlock v-if="schoolStore.loading && !schoolStore.detail" />
    <div v-else class="profile-layout">
      <section class="profile-summary page-panel">
        <span class="profile-avatar"><UserRound :size="28" /></span>
        <h2>{{ auth.user?.displayName || auth.user?.username }}</h2>
        <p>{{ auth.user?.schoolName }}</p>
        <div class="account-meta"><span><ShieldCheck :size="16" />学校账号</span><span>最近登录 {{ formatDate(auth.user?.lastLoginAt) }}</span></div>
      </section>

      <div class="profile-forms">
        <section class="page-panel">
          <div class="panel-header"><div><h2>联系人信息</h2><p>这些信息用于平台与学校账号负责人联系。</p></div><UserRound :size="21" /></div>
          <form class="panel-body form-stack" @submit.prevent="saveProfile">
            <InlineNotice v-if="profileNotice.text" :tone="profileNotice.tone">{{ profileNotice.text }}</InlineNotice>
            <div class="form-grid">
              <label>显示名称<input v-model="profile.displayName" maxlength="120" placeholder="例如：李老师" /></label>
              <label>联系人<input v-model="profile.contactName" maxlength="100" placeholder="联系人姓名" /></label>
              <label>联系电话<input v-model="profile.contactPhone" maxlength="50" inputmode="tel" placeholder="联系电话" /></label>
              <label>登录账号<input :value="auth.user?.username" disabled /></label>
            </div>
            <div class="button-row"><button class="primary-button" type="submit" :disabled="profileSaving"><Save :size="17" />{{ profileSaving ? "保存中" : "保存资料" }}</button></div>
          </form>
        </section>

        <section class="page-panel">
          <div class="panel-header"><div><h2>绑定学校</h2><p>学校主数据由平台管理员统一维护。</p></div><Building2 :size="21" /></div>
          <dl class="school-info panel-body">
            <div><dt>学校名称</dt><dd>{{ schoolStore.school?.schoolName || "-" }}</dd></div>
            <div><dt>学校编码</dt><dd>{{ schoolStore.school?.schoolCode || "-" }}</dd></div>
            <div><dt>学段与类型</dt><dd>{{ schoolStore.school?.schoolLevel || "-" }} / {{ schoolStore.school?.schoolType || "-" }}</dd></div>
            <div><dt>学校地址</dt><dd>{{ schoolStore.school?.address || "-" }}</dd></div>
            <div><dt>学校坐标</dt><dd>{{ schoolStore.school?.longitude || "-" }}, {{ schoolStore.school?.latitude || "-" }}</dd></div>
          </dl>
        </section>

        <section class="page-panel">
          <div class="panel-header"><div><h2>修改密码</h2><p>修改后当前设备仍保持登录。</p></div><KeyRound :size="21" /></div>
          <form class="panel-body form-stack" @submit.prevent="changePassword">
            <InlineNotice v-if="passwordNotice.text" :tone="passwordNotice.tone">{{ passwordNotice.text }}</InlineNotice>
            <div class="form-grid">
              <label>当前密码<input v-model="password.currentPassword" type="password" autocomplete="current-password" /></label><span></span>
              <label>新密码<input v-model="password.newPassword" type="password" autocomplete="new-password" placeholder="至少 6 位" /></label>
              <label>确认新密码<input v-model="password.confirmPassword" type="password" autocomplete="new-password" /></label>
            </div>
            <div class="button-row"><button class="secondary-button" type="submit" :disabled="passwordSaving"><KeyRound :size="17" />{{ passwordSaving ? "修改中" : "修改密码" }}</button></div>
          </form>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.profile-layout { display: grid; grid-template-columns: 260px minmax(0,1fr); gap: 16px; align-items: start; }
.profile-summary { display: grid; place-items: center; padding: 28px 20px; text-align: center; }
.profile-avatar { display: grid; place-items: center; width: 64px; height: 64px; border-radius: 50%; background: var(--green-soft); color: var(--green); }
.profile-summary h2 { margin: 15px 0 5px; font-size: 20px; }
.profile-summary p { margin: 0; color: var(--muted); }
.account-meta { width: 100%; display: grid; gap: 9px; margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; text-align: left; }
.account-meta span:first-child { display: flex; align-items: center; gap: 7px; color: var(--green); font-weight: 700; }
.profile-forms { display: grid; gap: 16px; }
.panel-header > svg { color: var(--green); }
.school-info { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0 28px; margin: 0; }
.school-info div { padding: 13px 0; border-bottom: 1px solid var(--line); }
.school-info dt { color: var(--muted); font-size: 12px; }
.school-info dd { margin: 5px 0 0; line-height: 1.6; }
@media (max-width: 980px) { .profile-layout { grid-template-columns: 1fr; } .profile-summary { place-items: start; text-align: left; } }
@media (max-width: 640px) { .school-info { grid-template-columns: 1fr; } }
</style>
