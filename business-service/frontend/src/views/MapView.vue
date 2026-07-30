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
const detailLoading = ref(false);
const mapStatus = ref("正在准备地图");
const layers = reactive({ resources: true, redCulture: true, towns: true, connections: true });
let map;
let AMapRef;
let overlays = [];

const selectedTitle = computed(() => {
  if (selected.value?.kind === "school") return schoolStore.school?.schoolName;
  if (selected.value?.kind === "town") return selected.value?.detail?.regionName || selected.value?.item?.regionName;
  if (selected.value?.kind === "redCulture") return selected.value?.detail?.name || selected.value?.item?.name;
  return selected.value?.detail?.resourceName || selected.value?.item?.resource?.resourceName || "资源详情";
});

onMounted(async () => {
  try {
    await Promise.all([schoolStore.load(), schoolStore.loadConfig(), schoolStore.loadRedCultureSites(), schoolStore.loadTowns()]);
    selected.value = { kind: "school" };
    await nextTick();
    AMapRef = await loadAmap(schoolStore.config);
    initializeMap();
  } catch (error) {
    mapStatus.value = error.message || "地图初始化失败";
  }
});

onBeforeUnmount(() => {
  map?.destroy();
});

watch(() => [layers.resources, layers.redCulture, layers.towns, layers.connections], renderOverlays);

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
  const fitTargets = [];
  const schoolMarker = new AMapRef.Marker({
    position: schoolPoint,
    content: '<span class="map-pin map-pin-school">校</span>',
    offset: new AMapRef.Pixel(-18, -18)
  });
  schoolMarker.on("click", () => { selected.value = { kind: "school" }; drawerOpen.value = true; });
  overlays.push(schoolMarker);
  fitTargets.push(schoolMarker);

  if (layers.resources) {
    schoolStore.resources.forEach((item) => {
      const resource = item.resource;
      if (!resource?.longitude || !resource?.latitude) return;
      const point = [Number(resource.longitude), Number(resource.latitude)];
      const marker = new AMapRef.Marker({
        position: point,
        content: '<span class="map-pin map-pin-resource">资</span>',
        offset: new AMapRef.Pixel(-16, -16)
      });
      marker.on("click", () => void selectResource(item));
      overlays.push(marker);
      fitTargets.push(marker);
      if (layers.connections) {
        overlays.push(new AMapRef.Polyline({
          path: [schoolPoint, point], strokeColor: "#2f6b4f", strokeWeight: 2,
          strokeOpacity: .45, strokeStyle: "dashed"
        }));
      }
    });
  }

  if (layers.redCulture) {
    schoolStore.redCultureSites.forEach((site) => {
      if (!site.longitude || !site.latitude) return;
      const marker = new AMapRef.Marker({
        position: [Number(site.longitude), Number(site.latitude)],
        content: '<span class="map-pin map-pin-red-culture">红</span>',
        offset: new AMapRef.Pixel(-16, -16)
      });
      marker.on("click", () => void selectRedCultureSite(site));
      overlays.push(marker);
      fitTargets.push(marker);
    });
  }

  if (layers.towns) {
    schoolStore.towns.forEach((town) => {
      const center = town.center || {};
      if (!Number.isFinite(Number(center.longitude)) || !Number.isFinite(Number(center.latitude))) return;
      const label = new AMapRef.Marker({
        position: [Number(center.longitude), Number(center.latitude)],
        content: `<button class="town-label" type="button">${escapeHtml(town.regionName || "未命名乡镇")}</button>`,
        offset: new AMapRef.Pixel(-34, -14),
        zIndex: selected.value?.kind === "town" && selected.value?.item?.regionId === town.regionId ? 80 : 30
      });
      label.on("click", () => void selectTown(town));
      overlays.push(label);
    });
  }

  map.add(overlays);
  if (fitTargets.length) map.setFitView(fitTargets, false, [70, 70, 70, 70], 15);
}

async function refresh() {
  mapStatus.value = "正在刷新";
  await Promise.all([schoolStore.load(true), schoolStore.loadRedCultureSites(), schoolStore.loadTowns()]);
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
    const marker = new AMapRef.Marker({
      position: point, content: '<span class="map-pin map-pin-user">我</span>',
      offset: new AMapRef.Pixel(-16, -16)
    });
    map.add(marker); overlays.push(marker); map.setZoomAndCenter(14, point); mapStatus.value = "已定位到当前位置";
  }, () => { mapStatus.value = "定位失败，请检查浏览器权限"; }, { enableHighAccuracy: true, timeout: 10000 });
}

async function selectResource(item) {
  const resource = item.resource;
  if (map && resource?.longitude && resource?.latitude) {
    map.setZoomAndCenter(16, [Number(resource.longitude), Number(resource.latitude)]);
  }
  selected.value = { kind: "resource", item, detail: resource };
  drawerOpen.value = true;
  detailLoading.value = true;
  try {
    selected.value.detail = await schoolStore.loadApprovedResource(item.resourceId);
  } catch {
    selected.value.detail = resource;
  } finally {
    detailLoading.value = false;
  }
}

async function selectRedCultureSite(item) {
  map?.setZoomAndCenter(16, [Number(item.longitude), Number(item.latitude)]);
  selected.value = { kind: "redCulture", item, detail: item };
  drawerOpen.value = true;
  detailLoading.value = true;
  try {
    selected.value.detail = await schoolStore.loadRedCultureSite(item.id);
  } catch {
    selected.value.detail = item;
  } finally {
    detailLoading.value = false;
  }
}

async function selectTown(item) {
  const center = item.center || {};
  if (map && Number.isFinite(Number(center.longitude)) && Number.isFinite(Number(center.latitude))) {
    map.setZoomAndCenter(12, [Number(center.longitude), Number(center.latitude)]);
  }
  selected.value = { kind: "town", item, detail: item };
  drawerOpen.value = true;
  detailLoading.value = true;
  mapStatus.value = `正在加载 ${item.regionName || "乡镇"} 红色资源`;
  try {
    selected.value.detail = await schoolStore.loadTownDetail(item.regionId);
    mapStatus.value = selected.value.detail?.graphStatusMessage || "乡镇红色资源已加载";
  } catch {
    selected.value.detail = item;
    mapStatus.value = schoolStore.townError || "乡镇红色资源加载失败";
  } finally {
    detailLoading.value = false;
  }
}

function distanceText(meters) {
  if (meters == null) return "距离待计算";
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} 公里` : `${meters} 米`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

</script>

<template>
  <AppShell title="地图资源" subtitle="查看本校正式资源，并发现周边可能具有思政教育价值的场所">
    <div class="map-workspace page-panel">
      <div class="map-toolbar">
        <div class="map-summary">
          <MapPinned :size="19" />
          <span><strong>{{ schoolStore.school?.schoolName || "当前学校" }}</strong><small>{{ schoolStore.resources.length }} 个正式资源 · {{ schoolStore.redCultureSites.length }} 个图谱地点</small></span>
        </div>
        <div class="map-actions">
          <span class="map-status">{{ mapStatus }}</span>
          <button class="icon-button" type="button" title="定位我的位置" @click="locateMe"><LocateFixed :size="18" /></button>
          <button class="icon-button" type="button" title="刷新资源" @click="refresh"><RefreshCw :size="18" /></button>
          <span class="layer-menu-wrap">
            <button class="icon-button" type="button" title="地图图层" @click="layerMenuOpen = !layerMenuOpen"><Layers3 :size="18" /></button>
            <span v-if="layerMenuOpen" class="layer-menu">
              <label><input v-model="layers.towns" type="checkbox" />乡镇名称</label>
              <label><input v-model="layers.resources" type="checkbox" />正式资源</label>
              <label><input v-model="layers.redCulture" type="checkbox" />红色文化图谱</label>
              <label><input v-model="layers.connections" type="checkbox" />关联线</label>
            </span>
          </span>
        </div>
      </div>

      <InlineNotice v-if="schoolStore.error" tone="error">{{ schoolStore.error }}</InlineNotice>
      <InlineNotice v-else-if="schoolStore.redCultureError" tone="info">{{ schoolStore.redCultureError }}，学校正式资源仍可正常使用。</InlineNotice>
      <LoadingBlock v-if="schoolStore.loading && !schoolStore.detail" />
      <div v-else class="map-body">
        <div ref="mapCanvas" class="map-canvas" aria-label="学校周边思政教育资源地图"></div>
        <aside v-if="drawerOpen" class="resource-drawer">
          <header>
            <div>
              <span class="badge" :class="selected?.kind === 'school' ? 'badge-green' : selected?.kind === 'town' ? 'badge-amber' : 'badge-red'">
                {{ selected?.kind === "school" ? "学校" : selected?.kind === "town" ? "乡镇资源" : selected?.kind === "redCulture" ? "图谱资源" : "正式资源" }}
              </span>
              <h2>{{ selectedTitle }}</h2>
            </div>
            <button class="icon-button" type="button" aria-label="关闭详情" @click="drawerOpen = false"><PanelRightClose :size="18" /></button>
          </header>

          <template v-if="selected?.kind === 'school'">
            <p class="drawer-intro">{{ schoolStore.school?.address || "暂无学校地址" }}</p>
            <div class="drawer-metrics">
              <span><strong>{{ schoolStore.resources.length }}</strong>正式资源</span>
              <span><strong>{{ schoolStore.redCultureSites.length }}</strong>图谱地点</span>
            </div>
            <h3>正式资源</h3>
            <div class="resource-list">
              <button v-for="item in schoolStore.resources" :key="item.resourceId" type="button" @click="selectResource(item)">
                <span><strong>{{ item.resource?.resourceName }}</strong><small>{{ item.resource?.resourceCategory || "思政资源" }} · {{ distanceText(item.distanceMeters) }}</small></span><Route :size="17" />
              </button>
              <div v-if="!schoolStore.resources.length" class="empty-state">暂无正式资源</div>
            </div>
          </template>

          <template v-else-if="selected?.kind === 'resource'">
            <p class="drawer-intro">{{ selected.detail?.intro || selected.detail?.educationValue || "暂无资源简介" }}</p>
            <LoadingBlock v-if="detailLoading" />
            <dl v-else class="detail-list">
              <div><dt>地址</dt><dd>{{ selected.detail?.address || "暂无" }}</dd></div>
              <div><dt>距离</dt><dd>{{ distanceText(selected.detail?.distanceMeters ?? selected.item?.distanceMeters) }}</dd></div>
              <div><dt>开放信息</dt><dd>{{ selected.detail?.openingTimeDesc || "待核实" }}</dd></div>
              <div><dt>联系电话</dt><dd>{{ selected.detail?.contactPhone || "待核实" }}</dd></div>
              <div><dt>教育主题</dt><dd>{{ selected.detail?.educationThemeSummary || selected.detail?.educationValue || "暂无" }}</dd></div>
              <div><dt>活动建议</dt><dd>{{ selected.detail?.activitySuggestion || "暂无" }}</dd></div>
              <div><dt>数据来源</dt><dd>{{ selected.detail?.externalProvider === "amap" ? "高德地图 POI（已审核）" : "平台审核资源" }}</dd></div>
            </dl>
          </template>

          <template v-else-if="selected?.kind === 'town'">
            <p class="drawer-intro">{{ selected.detail?.intro || "该乡镇暂无补充简介，可从已审核红色资源、人物、事件和故事中继续了解。" }}</p>
            <LoadingBlock v-if="detailLoading" />
            <template v-else>
              <div class="drawer-metrics">
                <span><strong>{{ selected.detail?.markers?.length || 0 }}</strong>遗址/纪念馆/事件</span>
                <span><strong>{{ selected.detail?.heroes?.length || 0 }}</strong>英雄人物</span>
                <span><strong>{{ selected.detail?.events?.length || 0 }}</strong>历史事件</span>
                <span><strong>{{ selected.detail?.stories?.length || 0 }}</strong>红色故事</span>
              </div>
              <dl class="detail-list">
                <div><dt>图谱状态</dt><dd>{{ selected.detail?.graphAvailable ? "图谱已命中" : "基础数据回退" }}</dd></div>
                <div><dt>状态说明</dt><dd>{{ selected.detail?.graphStatusMessage || "已按行政区和图谱关系聚合该乡镇资源" }}</dd></div>
              </dl>
              <h3>红色遗址、纪念馆与事件点位</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.markers || []" :key="`${item.type}-${item.id}`"><strong>{{ item.name }}</strong><small>{{ item.relationHint || item.address || item.summary }}</small></article><div v-if="!selected.detail?.markers?.length" class="empty-state">当前乡镇暂无已审核点位资源</div></div>
              <h3>英雄人物</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.heroes || []" :key="item.heroId"><strong>{{ item.heroName }}</strong><small>{{ item.nativePlaceText || item.profileSummary || item.relatedResourceNames?.join('、') }}</small></article><div v-if="!selected.detail?.heroes?.length" class="empty-state">当前乡镇暂无已审核关联人物</div></div>
              <h3>历史事件</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.events || []" :key="item.eventId"><strong>{{ item.eventName }}</strong><small>{{ item.eventTimeText || item.summary }}</small></article><div v-if="!selected.detail?.events?.length" class="empty-state">当前乡镇暂无已审核历史事件</div></div>
              <h3>红色故事</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.stories || []" :key="item.storyId"><strong>{{ item.storyTitle }}</strong><small>{{ item.summary || item.relatedEntityNames?.join('、') }}</small></article><div v-if="!selected.detail?.stories?.length" class="empty-state">当前乡镇暂无已审核红色故事</div></div>
            </template>
          </template>

          <template v-else-if="selected?.kind === 'redCulture'">
            <p class="drawer-intro">{{ selected.detail?.intro || selected.detail?.summary || "暂无简介" }}</p>
            <LoadingBlock v-if="detailLoading" />
            <template v-else>
              <dl class="detail-list">
                <div><dt>地点类别</dt><dd>{{ selected.detail?.category || "暂无" }}</dd></div>
                <div><dt>地址</dt><dd>{{ selected.detail?.address || "暂无" }}</dd></div>
                <div><dt>历史时期</dt><dd>{{ selected.detail?.historicalPeriod || "暂无" }}</dd></div>
                <div><dt>教学标签</dt><dd>{{ selected.detail?.teachingTags || "暂无" }}</dd></div>
              </dl>
              <h3>相关事件</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.events || []" :key="item.id"><strong>{{ item.name }}</strong><small>{{ item.extra || item.summary }}</small></article><div v-if="!selected.detail?.events?.length" class="empty-state">暂无相关事件</div></div>
              <h3>相关人物</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.people || []" :key="item.id"><strong>{{ item.name }}</strong><small>{{ item.extra || item.summary }}</small></article><div v-if="!selected.detail?.people?.length" class="empty-state">暂无相关人物</div></div>
              <h3>教学资源</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.teachingResources || []" :key="item.id"><strong>{{ item.name }}</strong><small>{{ item.extra || item.summary }}</small></article><div v-if="!selected.detail?.teachingResources?.length" class="empty-state">暂无教学资源</div></div>
              <h3>官方来源</h3>
              <div class="graph-list"><article v-for="item in selected.detail?.sources || []" :key="item.id"><a :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a><small>{{ item.publisher }} · {{ item.trustLevel }}</small></article><div v-if="!selected.detail?.sources?.length" class="empty-state">暂无来源</div></div>
            </template>
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
.map-status { max-width: 160px; overflow: hidden; color: var(--muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.radius-control { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 12px; }
.radius-control select { min-height: 34px; padding: 0 28px 0 9px; border: 1px solid var(--line); border-radius: 5px; background: #fff; color: var(--text); }
.layer-menu-wrap { position: relative; }
.layer-menu { position: absolute; z-index: 9; top: 44px; right: 0; width: 150px; display: grid; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: #fff; box-shadow: var(--shadow); }
.layer-menu label { display: flex; align-items: center; gap: 9px; font-size: 13px; }
.layer-menu input { width: 16px; min-height: 16px; }
.map-body { position: relative; display: grid; grid-template-columns: minmax(0,1fr) 380px; height: calc(100vh - 174px); min-height: 560px; }
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
.resource-list button { width: 100%; min-height: 58px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 11px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--text); text-align: left; }
.resource-list button:hover { border-color: #9ab1a0; background: var(--green-soft); }
.resource-list button span { min-width: 0; display: grid; gap: 4px; }
.resource-list strong, .resource-list small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-list small { color: var(--muted); }
.detail-list { display: grid; gap: 0; margin: 20px 0; }
.detail-list div { padding: 13px 0; border-bottom: 1px solid var(--line); }
.detail-list dt { color: var(--muted); font-size: 12px; }
.detail-list dd { margin: 5px 0 0; overflow-wrap: anywhere; font-size: 14px; line-height: 1.6; }
.badge-amber { background: #fff1cf; color: #805515; }
.graph-list { display: grid; gap: 8px; }
.graph-list article { display: grid; gap: 4px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 6px; }
.graph-list strong, .graph-list a { color: var(--text); font-size: 14px; font-weight: 700; }
.graph-list small { color: var(--muted); line-height: 1.5; }
.drawer-reopen { position: absolute; top: 14px; right: 14px; }
:global(.map-pin) { display: grid; place-items: center; border: 3px solid #fff; border-radius: 50%; color: #fff; font-size: 12px; font-weight: 800; box-shadow: 0 4px 12px rgba(20,40,28,.22); }
:global(.map-pin-school) { width: 36px; height: 36px; background: #2f6b4f; }
:global(.map-pin-resource), :global(.map-pin-user) { width: 32px; height: 32px; background: #a6382f; }
:global(.map-pin-user) { background: #2667a7; }
:global(.map-pin-red-culture) { width: 32px; height: 32px; background: #8f241f; }
:global(.town-label) { min-width: 68px; max-width: 120px; min-height: 28px; padding: 0 10px; border: 1px solid rgba(143,36,31,.28); border-radius: 5px; background: rgba(255,255,255,.94); color: #7b211d; font-size: 12px; font-weight: 700; box-shadow: 0 4px 12px rgba(20,40,28,.14); cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:global(.town-label:hover) { border-color: #8f241f; background: #fff7f2; }
@media (max-width: 900px) {
  .map-body { height: calc(100svh - 214px); min-height: 520px; grid-template-columns: 1fr; }
  .resource-drawer { position: absolute; z-index: 6; inset: auto 0 0; max-height: 52%; border-top: 1px solid var(--line); border-left: 0; box-shadow: 0 -10px 30px rgba(31,48,38,.12); }
  .map-status { display: none; }
}
@media (max-width: 620px) {
  .map-toolbar { align-items: flex-start; padding-left: 12px; }
  .map-summary small { display: none; }
  .radius-control span { display: none; }
  .map-body { height: calc(100svh - 198px); min-height: 480px; }
}
</style>
