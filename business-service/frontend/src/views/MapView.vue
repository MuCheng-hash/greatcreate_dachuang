<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { Layers3, LocateFixed, MapPinned, PanelRightClose, RefreshCw, Route } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { loadAmap } from "@/services/amap";
import { useSchoolStore } from "@/stores/school";

const schoolStore = useSchoolStore();
const mapCanvas = ref(null);
const layerMenuOpen = ref(false);
const drawerOpen = ref(true);
const selected = ref(null);
const mapStatus = ref("正在准备地图");
const layers = reactive({ resources: true, connections: true });
let map;
let AMapRef;
let overlays = [];

const selectedTitle = computed(() => selected.value?.kind === "school"
  ? schoolStore.school?.schoolName
  : selected.value?.item?.resource?.resourceName || "资源详情");

onMounted(async () => {
  try {
    await Promise.all([schoolStore.load(), schoolStore.loadConfig()]);
    selected.value = { kind: "school" };
    await nextTick();
    AMapRef = await loadAmap(schoolStore.config);
    initializeMap();
  } catch (error) {
    mapStatus.value = error.message || "地图初始化失败";
  }
});

onBeforeUnmount(() => map?.destroy());
watch(() => [layers.resources, layers.connections], renderOverlays);

function initializeMap() {
  const school = schoolStore.school;
  if (!school?.longitude || !school?.latitude || !mapCanvas.value) {
    mapStatus.value = "学校坐标不完整";
    return;
  }
  map = new AMapRef.Map(mapCanvas.value, {
    zoom: 13,
    center: [Number(school.longitude), Number(school.latitude)],
    mapStyle: "amap://styles/normal",
    viewMode: "2D"
  });
  map.on("complete", () => { mapStatus.value = "地图已加载"; });
  renderOverlays();
}

function clearOverlays() {
  if (map && overlays.length) map.remove(overlays);
  overlays = [];
}

function renderOverlays() {
  if (!map || !AMapRef || !schoolStore.school) return;
  clearOverlays();
  const school = schoolStore.school;
  const schoolPoint = [Number(school.longitude), Number(school.latitude)];
  const schoolMarker = new AMapRef.Marker({ position: schoolPoint, content: '<span class="map-pin map-pin-school">校</span>', offset: new AMapRef.Pixel(-18, -18) });
  schoolMarker.on("click", () => { selected.value = { kind: "school" }; drawerOpen.value = true; });
  overlays.push(schoolMarker);

  if (layers.resources) {
    schoolStore.resources.forEach((item) => {
      const resource = item.resource;
      if (!resource?.longitude || !resource?.latitude) return;
      const point = [Number(resource.longitude), Number(resource.latitude)];
      const marker = new AMapRef.Marker({ position: point, content: '<span class="map-pin map-pin-resource">资</span>', offset: new AMapRef.Pixel(-16, -16) });
      marker.on("click", () => { selected.value = { kind: "resource", item }; drawerOpen.value = true; });
      overlays.push(marker);
      if (layers.connections) {
        overlays.push(new AMapRef.Polyline({ path: [schoolPoint, point], strokeColor: "#2f6b4f", strokeWeight: 2, strokeOpacity: .45, strokeStyle: "dashed" }));
      }
    });
  }
  map.add(overlays);
  map.setFitView(overlays.filter((item) => item instanceof AMapRef.Marker), false, [70, 70, 70, 70], 15);
}

async function refresh() {
  mapStatus.value = "正在刷新";
  await schoolStore.load(true);
  renderOverlays();
  mapStatus.value = "数据已刷新";
}

function locateMe() {
  if (!navigator.geolocation || !map) {
    mapStatus.value = "当前浏览器无法定位";
    return;
  }
  mapStatus.value = "正在定位";
  navigator.geolocation.getCurrentPosition((position) => {
    const point = [position.coords.longitude, position.coords.latitude];
    const marker = new AMapRef.Marker({ position: point, content: '<span class="map-pin map-pin-user">我</span>', offset: new AMapRef.Pixel(-16, -16) });
    map.add(marker); overlays.push(marker); map.setZoomAndCenter(14, point); mapStatus.value = "已定位到当前位置";
  }, () => { mapStatus.value = "定位失败，请检查浏览器权限"; }, { enableHighAccuracy: true, timeout: 10000 });
}

function focusResource(item) {
  const resource = item.resource;
  if (map && resource?.longitude && resource?.latitude) map.setZoomAndCenter(16, [Number(resource.longitude), Number(resource.latitude)]);
  selected.value = { kind: "resource", item };
  drawerOpen.value = true;
}

function distanceText(meters) {
  if (meters == null) return "距离待计算";
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} 公里` : `${meters} 米`;
}
</script>

<template>
  <AppShell title="地图资源" subtitle="查看本校位置与周边可用于课堂和实践的教育资源">
    <div class="map-workspace page-panel">
      <div class="map-toolbar">
        <div class="map-summary"><MapPinned :size="19" /><span><strong>{{ schoolStore.school?.schoolName || "当前学校" }}</strong><small>{{ schoolStore.resources.length }} 个周边资源</small></span></div>
        <div class="map-actions">
          <span class="map-status">{{ mapStatus }}</span>
          <button class="icon-button" type="button" title="定位我的位置" @click="locateMe"><LocateFixed :size="18" /></button>
          <button class="icon-button" type="button" title="刷新资源" @click="refresh"><RefreshCw :size="18" /></button>
          <span class="layer-menu-wrap">
            <button class="icon-button" type="button" title="地图图层" @click="layerMenuOpen = !layerMenuOpen"><Layers3 :size="18" /></button>
            <span v-if="layerMenuOpen" class="layer-menu">
              <label><input v-model="layers.resources" type="checkbox" />资源点</label>
              <label><input v-model="layers.connections" type="checkbox" />关联线</label>
            </span>
          </span>
        </div>
      </div>

      <InlineNotice v-if="schoolStore.error" tone="error">{{ schoolStore.error }}</InlineNotice>
      <LoadingBlock v-if="schoolStore.loading && !schoolStore.detail" />
      <div v-else class="map-body">
        <div ref="mapCanvas" class="map-canvas" aria-label="学校周边思政教育资源地图"></div>
        <aside v-if="drawerOpen" class="resource-drawer">
          <header>
            <div><span class="badge" :class="selected?.kind === 'school' ? 'badge-green' : 'badge-red'">{{ selected?.kind === "school" ? "学校" : "周边资源" }}</span><h2>{{ selectedTitle }}</h2></div>
            <button class="icon-button" type="button" aria-label="关闭详情" @click="drawerOpen = false"><PanelRightClose :size="18" /></button>
          </header>

          <template v-if="selected?.kind === 'school'">
            <p class="drawer-intro">{{ schoolStore.school?.address || "暂无学校地址" }}</p>
            <div class="drawer-metrics"><span><strong>{{ schoolStore.resources.length }}</strong>周边资源</span><span><strong>{{ schoolStore.activityPlans.length }}</strong>教学方案</span></div>
            <h3>资源列表</h3>
            <div class="resource-list">
              <button v-for="item in schoolStore.resources" :key="item.resourceId" type="button" @click="focusResource(item)">
                <span><strong>{{ item.resource?.resourceName }}</strong><small>{{ item.resource?.resourceCategory || "思政资源" }} · {{ distanceText(item.distanceMeters) }}</small></span><Route :size="17" />
              </button>
              <div v-if="!schoolStore.resources.length" class="empty-state">暂无周边资源</div>
            </div>
          </template>

          <template v-else-if="selected?.item">
            <p class="drawer-intro">{{ selected.item.resource?.intro || selected.item.resource?.educationValue || "暂无资源简介" }}</p>
            <dl class="detail-list">
              <div><dt>地址</dt><dd>{{ selected.item.resource?.address || "暂无" }}</dd></div>
              <div><dt>距离</dt><dd>{{ distanceText(selected.item.distanceMeters) }}</dd></div>
              <div><dt>建议方式</dt><dd>{{ selected.item.recommendedTravelMode || "待评估" }}</dd></div>
              <div><dt>教育主题</dt><dd>{{ selected.item.educationThemeSummary || selected.item.resource?.educationValue || "暂无" }}</dd></div>
            </dl>
          </template>
        </aside>
        <button v-if="!drawerOpen" class="drawer-reopen secondary-button" type="button" @click="drawerOpen = true">打开详情</button>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.map-workspace { overflow: hidden; }
.map-toolbar { min-height: 58px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 9px 12px 9px 16px; border-bottom: 1px solid var(--line); }
.map-summary { display: flex; align-items: center; gap: 10px; min-width: 0; color: var(--green); }
.map-summary span { display: grid; min-width: 0; }
.map-summary strong { overflow: hidden; color: var(--text); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.map-summary small { margin-top: 2px; color: var(--muted); font-size: 12px; }
.map-actions { display: flex; align-items: center; gap: 7px; }
.map-status { max-width: 220px; overflow: hidden; color: var(--muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.layer-menu-wrap { position: relative; }
.layer-menu { position: absolute; z-index: 9; top: 44px; right: 0; width: 150px; display: grid; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: #fff; box-shadow: var(--shadow); }
.layer-menu label { display: flex; align-items: center; gap: 9px; font-size: 13px; }
.layer-menu input { width: 16px; min-height: 16px; }
.map-body { position: relative; display: grid; grid-template-columns: minmax(0,1fr) 360px; height: calc(100vh - 174px); min-height: 560px; }
.map-canvas { width: 100%; height: 100%; background: var(--blue-soft); }
.resource-drawer { overflow-y: auto; padding: 18px; border-left: 1px solid var(--line); background: #fff; }
.resource-drawer header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.resource-drawer h2 { margin: 9px 0 0; font-size: 19px; line-height: 1.35; }
.resource-drawer h3 { margin: 24px 0 10px; font-size: 14px; }
.drawer-intro { margin-top: 18px; color: var(--muted); font-size: 14px; line-height: 1.75; }
.drawer-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 16px; }
.drawer-metrics span { display: grid; gap: 3px; padding: 12px; border-radius: 6px; background: var(--surface-muted); color: var(--muted); font-size: 12px; }
.drawer-metrics strong { color: var(--text); font-size: 22px; }
.resource-list { display: grid; gap: 7px; }
.resource-list button { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 11px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--text); text-align: left; }
.resource-list button:hover { border-color: #9ab1a0; background: var(--green-soft); }
.resource-list button span { min-width: 0; display: grid; gap: 4px; }
.resource-list strong, .resource-list small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-list small { color: var(--muted); }
.detail-list { display: grid; gap: 0; margin: 20px 0; }
.detail-list div { padding: 13px 0; border-bottom: 1px solid var(--line); }
.detail-list dt { color: var(--muted); font-size: 12px; }
.detail-list dd { margin: 5px 0 0; font-size: 14px; line-height: 1.6; }
.drawer-reopen { position: absolute; top: 14px; right: 14px; }
:global(.map-pin) { display: grid; place-items: center; border: 3px solid #fff; border-radius: 50%; color: #fff; font-size: 12px; font-weight: 800; box-shadow: 0 4px 12px rgba(20,40,28,.22); }
:global(.map-pin-school) { width: 36px; height: 36px; background: #2f6b4f; }
:global(.map-pin-resource), :global(.map-pin-user) { width: 32px; height: 32px; background: #a6382f; }
:global(.map-pin-user) { background: #2667a7; }
@media (max-width: 900px) {
  .map-body { height: calc(100svh - 214px); min-height: 520px; grid-template-columns: 1fr; }
  .resource-drawer { position: absolute; z-index: 6; inset: auto 0 0; max-height: 52%; border-top: 1px solid var(--line); border-left: 0; box-shadow: 0 -10px 30px rgba(31,48,38,.12); }
  .map-status { display: none; }
}
@media (max-width: 520px) {
  .map-toolbar { padding-left: 12px; }
  .map-summary small { display: none; }
  .map-body { height: calc(100svh - 198px); min-height: 480px; }
}
</style>
