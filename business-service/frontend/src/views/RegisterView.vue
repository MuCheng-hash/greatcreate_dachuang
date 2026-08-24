<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { ArrowLeft, CheckCircle2 } from "@lucide/vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { api } from "@/services/api";

const loading = ref(false);
const error = ref("");
const success = ref(false);
const schools = ref([]);
const schoolKeyword = ref("");
const form = reactive({ username: "", password: "", confirmPassword: "", realName: "", contactPhone: "", email: "", schoolId: "", roleCode: "student", teacherInviteCode: "" });
const selectedSchool = computed(() => schools.value.find((item) => String(item.schoolId) === String(form.schoolId)));

onMounted(loadSchools);

async function loadSchools() {
  try { schools.value = await api.get(`/api/public/schools?keyword=${encodeURIComponent(schoolKeyword.value.trim())}`, { skipAuthRefresh: true }); }
  catch (requestError) { error.value = requestError.message || "学校目录加载失败，请稍后重试。"; }
}

async function submit() {
  error.value = "";
  if (!form.username.trim() || !form.password || !form.realName.trim() || !form.contactPhone.trim() || !form.schoolId) { error.value = "请完整填写身份、学校和账号资料。"; return; }
  if (form.password.length < 6) { error.value = "密码至少需要 6 位。"; return; }
  if (form.password !== form.confirmPassword) { error.value = "两次输入的密码不一致。"; return; }
  if (form.roleCode === "teacher" && !form.teacherInviteCode.trim()) { error.value = "教师注册需要填写学校管理员提供的邀请码。"; return; }
  loading.value = true;
  try {
    await api.post("/api/auth/register", { username: form.username.trim(), password: form.password, realName: form.realName.trim(), contactPhone: form.contactPhone.trim(), email: form.email.trim() || null, schoolId: Number(form.schoolId), roleCode: form.roleCode, teacherInviteCode: form.roleCode === "teacher" ? form.teacherInviteCode.trim() : null }, { skipAuthRefresh: true });
    success.value = true;
  } catch (requestError) { error.value = requestError.message || "注册失败，请稍后重试。"; }
  finally { loading.value = false; }
}
</script>

<template>
  <div class="register-page">
    <header class="register-header"><RouterLink class="register-brand" to="/"><span class="brand-symbol">乡</span><span>红启乡智</span></RouterLink><RouterLink class="text-button" to="/login"><ArrowLeft :size="17" />返回登录</RouterLink></header>
    <main class="register-main">
      <section v-if="success" class="page-panel registration-success"><CheckCircle2 :size="48" /><h1>账号注册成功</h1><p>你的账号已绑定 {{ selectedSchool?.schoolName || "所选学校" }}，现在可以登录进入工作台。</p><RouterLink class="primary-button" to="/login">前往登录</RouterLink></section>
      <section v-else class="page-panel">
        <div class="panel-header"><div><h1>注册学生或教师账号</h1><p>学校信息由平台统一维护，请选择你的所属学校。</p></div></div>
        <form class="panel-body form-stack" @submit.prevent="submit">
          <InlineNotice v-if="error" tone="error">{{ error }}</InlineNotice>
          <div class="form-grid">
            <label>注册身份<select v-model="form.roleCode"><option value="student">学生</option><option value="teacher">教师</option></select></label>
            <label>姓名<input v-model="form.realName" maxlength="100" autocomplete="name" placeholder="请输入真实姓名" /></label>
            <label class="span-two">搜索学校<div class="search-row"><input v-model="schoolKeyword" placeholder="输入学校名称关键字" @keyup.enter.prevent="loadSchools" /><button class="secondary-button" type="button" @click="loadSchools">查询</button></div></label>
            <label class="span-two">所属学校<select v-model="form.schoolId"><option value="">请选择已启用学校</option><option v-for="item in schools" :key="item.schoolId" :value="item.schoolId">{{ item.schoolName }}{{ item.regionName ? ` · ${item.regionName}` : '' }}</option></select></label>
            <label>账号<input v-model="form.username" maxlength="100" autocomplete="username" placeholder="请输入登录账号" /></label>
            <label>联系电话<input v-model="form.contactPhone" maxlength="50" inputmode="tel" autocomplete="tel" placeholder="请输入联系电话" /></label>
            <label>登录密码<input v-model="form.password" type="password" maxlength="128" autocomplete="new-password" placeholder="至少 6 位" /></label>
            <label>确认密码<input v-model="form.confirmPassword" type="password" maxlength="128" autocomplete="new-password" placeholder="再次输入密码" /></label>
            <label class="span-two">邮箱（可选）<input v-model="form.email" maxlength="100" autocomplete="email" placeholder="用于账号联系" /></label>
            <label v-if="form.roleCode === 'teacher'" class="span-two">教师邀请码<input v-model="form.teacherInviteCode" maxlength="32" autocomplete="off" placeholder="请输入学校管理员提供的邀请码" /></label>
          </div>
          <InlineNotice v-if="form.roleCode === 'teacher'" tone="info">教师邀请码只验证所选学校，注册成功后将直接开通教师账号。</InlineNotice>
          <InlineNotice v-else tone="info">学生注册成功后可使用班级邀请码加入班级。</InlineNotice>
          <div class="button-row form-footer"><button class="primary-button" type="submit" :disabled="loading">{{ loading ? "正在注册" : "完成注册" }}</button></div>
        </form>
      </section>
    </main>
  </div>
</template>

<style scoped>
.panel-header h1 { margin: 0; font-size: 22px; }.span-two { grid-column: 1 / -1; }.search-row { display: flex; gap: 8px; }.search-row input { min-width: 0; }.search-row button { flex: 0 0 auto; }.form-footer { justify-content: flex-end; padding-top: 4px; }.registration-success { min-height: 380px; display: grid; place-items: center; align-content: center; gap: 12px; padding: 40px; color: var(--green); text-align: center; }.registration-success h1 { margin: 8px 0 0; color: var(--text); }.registration-success p { max-width: 540px; color: var(--muted); line-height: 1.7; }@media (max-width: 640px) { .span-two { grid-column: auto; }.search-row { flex-wrap: wrap; }.search-row button { width: 100%; } }
</style>
