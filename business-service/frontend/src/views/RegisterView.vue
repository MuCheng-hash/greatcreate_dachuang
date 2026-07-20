<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { ArrowLeft, ArrowRight, CheckCircle2, LocateFixed } from "@lucide/vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { api } from "@/services/api";
import { validateRegistrationAccount } from "@/utils/validation";

const step = ref(1);
const loading = ref(false);
const locating = ref(false);
const error = ref("");
const success = ref(null);
const regions = reactive({ province: [], city: [], county: [], township: [] });
const form = reactive({
  username: "", password: "", confirmPassword: "", contactName: "", contactPhone: "",
  schoolName: "", schoolLevel: "primary", schoolType: "村小", schoolNature: "public",
  provinceId: "", cityId: "", countyRegionId: "", townshipRegionId: "", addressDetail: "",
  longitude: "", latitude: "", intro: ""
});

const address = computed(() => {
  const names = [
    findRegion("province", form.provinceId)?.regionName,
    findRegion("city", form.cityId)?.regionName,
    findRegion("county", form.countyRegionId)?.regionName,
    findRegion("township", form.townshipRegionId)?.regionName,
    form.addressDetail.trim()
  ];
  return names.filter(Boolean).join("");
});

onMounted(loadProvinces);

async function loadProvinces() {
  try {
    regions.province = await api.get("/api/regions?regionLevel=PROVINCE");
    const hebei = regions.province.find((item) => item.adcode === "130000" || item.regionName === "河北省");
    if (hebei) {
      form.provinceId = String(hebei.regionId);
      await changeRegion("province");
    }
  } catch (requestError) {
    error.value = requestError.message;
  }
}

async function changeRegion(level) {
  const flow = { province: "city", city: "county", county: "township" };
  const valueKey = { province: "provinceId", city: "cityId", county: "countyRegionId" };
  const clearAfter = { province: ["cityId", "countyRegionId", "townshipRegionId"], city: ["countyRegionId", "townshipRegionId"], county: ["townshipRegionId"] };
  clearAfter[level]?.forEach((key) => { form[key] = ""; });
  const next = flow[level];
  if (!next || !form[valueKey[level]]) return;
  regions[next] = await api.get(`/api/regions?parentRegionId=${encodeURIComponent(form[valueKey[level]])}`);
}

function findRegion(level, id) {
  return regions[level].find((item) => String(item.regionId) === String(id));
}

function nextStep() {
  error.value = "";
  error.value = validateRegistrationAccount(form);
  if (error.value) return;
  step.value = 2;
}

function locate() {
  error.value = "";
  if (!navigator.geolocation) {
    error.value = "当前浏览器不支持定位，请手动填写经纬度。";
    return;
  }
  locating.value = true;
  navigator.geolocation.getCurrentPosition(
    (position) => {
      form.longitude = position.coords.longitude.toFixed(7);
      form.latitude = position.coords.latitude.toFixed(7);
      locating.value = false;
    },
    () => { error.value = "定位失败，请检查浏览器定位权限或手动填写经纬度。"; locating.value = false; },
    { enableHighAccuracy: true, timeout: 10000 }
  );
}

async function submit() {
  error.value = "";
  if (!form.schoolName.trim() || !form.countyRegionId || !form.townshipRegionId || !address.value) {
    error.value = "请完整填写学校名称和所属地区。";
    return;
  }
  if (!form.longitude || !form.latitude) {
    error.value = "请定位或手动填写学校经纬度。";
    return;
  }
  loading.value = true;
  try {
    success.value = await api.post("/api/auth/school-register", {
      username: form.username.trim(), password: form.password, contactName: form.contactName.trim(), contactPhone: form.contactPhone.trim(),
      schoolName: form.schoolName.trim(), schoolLevel: form.schoolLevel.toUpperCase(), schoolType: form.schoolType,
      schoolNature: form.schoolNature.toUpperCase(), countyRegionId: Number(form.countyRegionId), townshipRegionId: Number(form.townshipRegionId),
      address: address.value, longitude: Number(form.longitude), latitude: Number(form.latitude),
      geoSourceType: "SCHOOL_OFFICIAL", geoConfidence: "MEDIUM", intro: form.intro.trim()
    });
  } catch (requestError) {
    error.value = requestError.message || "提交失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="register-page">
    <header class="register-header">
      <RouterLink class="register-brand" to="/login"><span class="brand-symbol">乡</span><span>思政资源工作台</span></RouterLink>
      <RouterLink class="text-button" to="/login"><ArrowLeft :size="17" />返回登录</RouterLink>
    </header>
    <main class="register-main">
      <div v-if="!success" class="stepper" aria-label="注册步骤">
        <div class="step-item" :class="{ active: step === 1 }"><span class="step-number">1</span><span>账号与联系人</span></div>
        <div class="step-item" :class="{ active: step === 2 }"><span class="step-number">2</span><span>学校与位置</span></div>
      </div>

      <section v-if="success" class="page-panel registration-success">
        <CheckCircle2 :size="48" />
        <h1>注册申请已提交</h1>
        <p>申请编号 {{ success.registrationId }}，管理员审核通过后即可使用学校账号登录。</p>
        <RouterLink class="primary-button" to="/login">返回登录</RouterLink>
      </section>

      <section v-else class="page-panel">
        <div class="panel-header"><div><h1>申请学校账号</h1><p>学校资料提交后需由平台管理员审核。</p></div></div>
        <form class="panel-body form-stack" @submit.prevent="step === 1 ? nextStep() : submit()">
          <InlineNotice v-if="error" tone="error">{{ error }}</InlineNotice>

          <div v-if="step === 1" class="form-grid">
            <label>申请账号<input v-model="form.username" autocomplete="username" maxlength="100" placeholder="建议使用学校简称" /></label>
            <label>联系人<input v-model="form.contactName" maxlength="100" placeholder="联系人姓名" /></label>
            <label>联系电话<input v-model="form.contactPhone" maxlength="50" inputmode="tel" placeholder="请输入联系电话" /></label>
            <span></span>
            <label>登录密码<input v-model="form.password" type="password" autocomplete="new-password" maxlength="128" placeholder="至少 6 位" /></label>
            <label>确认密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" maxlength="128" placeholder="再次输入密码" /></label>
          </div>

          <template v-else>
            <div class="form-grid">
              <label>学校名称<input v-model="form.schoolName" placeholder="请输入学校全称" /></label>
              <label>学校类型<select v-model="form.schoolType"><option>村小</option><option>乡镇中心小学</option><option>完全小学</option><option>教学点</option><option>初级中学</option><option>九年一贯制学校</option></select></label>
              <label>学段<select v-model="form.schoolLevel"><option value="kindergarten">幼儿园</option><option value="primary">小学</option><option value="junior">初中</option><option value="nine_year">九年一贯制</option></select></label>
              <label>办学性质<select v-model="form.schoolNature"><option value="public">公办</option><option value="private">民办</option><option value="other">其他</option></select></label>
              <label>省份<select v-model="form.provinceId" @change="changeRegion('province')"><option value="">请选择</option><option v-for="item in regions.province" :key="item.regionId" :value="item.regionId">{{ item.regionName }}</option></select></label>
              <label>城市<select v-model="form.cityId" :disabled="!regions.city.length" @change="changeRegion('city')"><option value="">请选择</option><option v-for="item in regions.city" :key="item.regionId" :value="item.regionId">{{ item.regionName }}</option></select></label>
              <label>区县<select v-model="form.countyRegionId" :disabled="!regions.county.length" @change="changeRegion('county')"><option value="">请选择</option><option v-for="item in regions.county" :key="item.regionId" :value="item.regionId">{{ item.regionName }}</option></select></label>
              <label>乡镇<select v-model="form.townshipRegionId" :disabled="!regions.township.length"><option value="">请选择</option><option v-for="item in regions.township" :key="item.regionId" :value="item.regionId">{{ item.regionName }}</option></select></label>
              <label class="span-two">详细地址<input v-model="form.addressDetail" placeholder="门牌号、村组或校区" /></label>
              <label>经度<input v-model="form.longitude" type="number" step="0.0000001" placeholder="114.0000000" /></label>
              <label>纬度<input v-model="form.latitude" type="number" step="0.0000001" placeholder="38.0000000" /></label>
              <label class="span-two">学校简介<textarea v-model="form.intro" placeholder="补充学校情况与办学特色"></textarea></label>
            </div>
            <button class="secondary-button locate-button" type="button" :disabled="locating" @click="locate"><LocateFixed :size="18" />{{ locating ? "正在定位" : "定位学校坐标" }}</button>
          </template>

          <div class="button-row form-footer">
            <button v-if="step === 2" class="secondary-button" type="button" @click="step = 1"><ArrowLeft :size="17" />上一步</button>
            <button class="primary-button" type="submit" :disabled="loading">
              {{ step === 1 ? "下一步" : loading ? "正在提交" : "提交注册申请" }}<ArrowRight v-if="step === 1" :size="17" />
            </button>
          </div>
        </form>
      </section>
    </main>
  </div>
</template>

<style scoped>
.panel-header h1 { margin: 0; font-size: 22px; }
.span-two { grid-column: 1 / -1; }
.form-footer { justify-content: flex-end; padding-top: 4px; }
.locate-button { justify-self: start; }
.registration-success { min-height: 380px; display: grid; place-items: center; align-content: center; gap: 12px; padding: 40px; color: var(--green); text-align: center; }
.registration-success h1 { margin: 8px 0 0; color: var(--text); }
.registration-success p { max-width: 540px; color: var(--muted); line-height: 1.7; }
@media (max-width: 640px) { .span-two { grid-column: auto; } .form-footer .primary-button, .form-footer .secondary-button { flex: 1; } }
</style>
