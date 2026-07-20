<script setup>
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowRight, Eye, EyeOff, LogIn } from "@lucide/vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const form = reactive({ username: "", password: "" });
const showPassword = ref(false);
const error = ref("");

async function submit() {
  error.value = "";
  if (!form.username.trim() || !form.password) {
    error.value = "请输入账号和密码。";
    return;
  }
  try {
    const user = await auth.login({ username: form.username.trim(), password: form.password });
    if (user?.roleCode === "platform_admin") {
      window.location.assign("/admin.html");
      return;
    }
    const redirect = typeof route.query.redirect === "string" && route.query.redirect.startsWith("/")
      ? route.query.redirect
      : "/map";
    await router.replace(redirect);
  } catch (requestError) {
    error.value = requestError.message || "登录失败，请核对账号信息。";
  }
}
</script>

<template>
  <div class="auth-page">
    <section class="auth-visual" aria-label="乡村学校与本地教育资源">
      <div class="auth-visual-copy">
        <span class="brand-symbol">乡</span>
        <h1>从学校出发，发现身边可用的思政教育资源</h1>
        <p>查看学校周边资源，组织实践活动，并生成能够真正落地的教学方案。</p>
      </div>
    </section>
    <main class="auth-form-side">
      <form class="auth-form-wrap form-stack" @submit.prevent="submit">
        <div>
          <p class="auth-kicker">乡村学校教师端</p>
          <h2>登录工作台</h2>
          <p class="auth-intro">使用审核通过的学校账号登录。</p>
        </div>
        <InlineNotice v-if="error" tone="error">{{ error }}</InlineNotice>
        <label>学校账号<input v-model="form.username" autocomplete="username" placeholder="请输入学校账号" /></label>
        <label>
          密码
          <span class="password-field">
            <input v-model="form.password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" />
            <button type="button" class="password-toggle" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              <EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" />
            </button>
          </span>
        </label>
        <button class="primary-button full-button" type="submit" :disabled="auth.loading">
          <LogIn :size="18" />{{ auth.loading ? "正在登录" : "登录" }}
        </button>
        <div class="auth-links">
          <RouterLink to="/register">前往注册 <ArrowRight :size="15" /></RouterLink>
        </div>
      </form>
    </main>
  </div>
</template>

<style scoped>
.password-field { position: relative; display: block; }
.password-field input { padding-right: 44px; }
.password-toggle { position: absolute; top: 2px; right: 2px; width: 38px; height: 38px; display: grid; place-items: center; border: 0; background: transparent; color: #667169; }
.auth-links { justify-content: flex-end; }
.auth-links a:last-child { display: inline-flex; align-items: center; gap: 4px; }
</style>
