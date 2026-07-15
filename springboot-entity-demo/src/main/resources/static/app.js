const state = {
    appConfig: {
        amapKey: "",
        amapSecurityJsCode: "",
        qaServiceBaseUrl: "http://127.0.0.1:5050"
    },
    userLocation: null,
    userLocationAddress: null,
    userLocationAccuracy: null,
    currentTownId: null,
    currentTownName: "",
    currentTownDetail: null,
    currentSchoolId: null,
    currentSchoolDetail: null,
    nearbySchools: [],
    towns: [],
    regions: [],
    allRegions: [],
    regionRecords: [],
    mapReady: false,
    mapSdkLoading: false,
    currentZoom: 8.9,
    lastLocateErrorText: "",
    regionBoundaryPolygons: [],
    boundaryPolygons: [],
    regionLabelOverlays: [],
    hebeiAdministrativeLayer: null,
    basemapDetailed: true,
    layerVisibility: {
        province: true,
        city: true,
        township: true
    },
    activeMarkerKey: "",
    markerInstances: [],
    markerIndex: new Map(),
    geolocationPlugin: null,
    geocoderPlugin: null,
    districtSearchPluginReady: false,
    registerRegionOptions: {
        province: [],
        city: [],
        district: [],
        township: [],
        village: []
    },
    registerGeoLookupTimer: null,
    registerGeoLastQuery: "",
    registerGeoResolvedBy: "",
    userLocationMarker: null,
    userLocationAccuracyCircle: null,
    activeInfoWindow: null,
    preserveUserLocationCenter: false,
    drawerOpen: false,
    currentAccount: null,
    authMode: "login"
};

const elements = {
    mapStage: document.querySelector(".map-stage"),
    toolbarTownName: document.querySelector("#toolbarTownName"),
    townCenterLabel: document.querySelector("#townCenterLabel"),
    townName: document.querySelector("#townName"),
    townIntro: document.querySelector("#townIntro"),
    graphStatus: document.querySelector("#graphStatus"),
    mapStatus: document.querySelector("#mapStatus"),
    regionDrawer: document.querySelector("#regionDrawer"),
    currentRegionTrigger: document.querySelector("#currentRegionTrigger"),
    closeDrawerButton: document.querySelector("#closeDrawerButton"),
    factMarkerCount: document.querySelector("#factMarkerCount"),
    factHeroCount: document.querySelector("#factHeroCount"),
    factStoryCount: document.querySelector("#factStoryCount"),
    factEventCount: document.querySelector("#factEventCount"),
    eventCount: document.querySelector("#eventCount"),
    markerCount: document.querySelector("#markerCount"),
    heroCount: document.querySelector("#heroCount"),
    storyCount: document.querySelector("#storyCount"),
    questionCount: document.querySelector("#questionCount"),
    qaStatus: document.querySelector("#qaStatus"),
    eventList: document.querySelector("#eventList"),
    markerList: document.querySelector("#markerList"),
    heroList: document.querySelector("#heroList"),
    storyList: document.querySelector("#storyList"),
    questionList: document.querySelector("#questionList"),
    qaAnswer: document.querySelector("#qaAnswer"),
    qaCitations: document.querySelector("#qaCitations"),
    qaQuestionInput: document.querySelector("#qaQuestionInput"),
    authEntryButton: document.querySelector("#authEntryButton"),
    adminEntryLink: document.querySelector("#adminEntryLink"),
    locateButton: document.querySelector("#locateButton"),
    refreshTownButton: document.querySelector("#refreshTownButton"),
    explainButton: document.querySelector("#explainButton"),
    askButton: document.querySelector("#askButton"),
    clearQaButton: document.querySelector("#clearQaButton"),
    toggleBasemapDetail: document.querySelector("#toggleBasemapDetail"),
    toggleProvinceLayer: document.querySelector("#toggleProvinceLayer"),
    toggleCityLayer: document.querySelector("#toggleCityLayer"),
    toggleTownshipLayer: document.querySelector("#toggleTownshipLayer"),
    emptyStateTemplate: document.querySelector("#emptyStateTemplate"),
    authModalShell: document.querySelector("#authModalShell"),
    authCloseButton: document.querySelector("#authCloseButton"),
    showLoginModeButton: document.querySelector("#showLoginModeButton"),
    showRegisterModeButton: document.querySelector("#showRegisterModeButton"),
    authStatusText: document.querySelector("#authStatusText"),
    loginForm: document.querySelector("#loginForm"),
    loginUsernameInput: document.querySelector("#loginUsernameInput"),
    loginPasswordInput: document.querySelector("#loginPasswordInput"),
    registerForm: document.querySelector("#registerForm"),
    registerUsernameInput: document.querySelector("#registerUsernameInput"),
    registerPasswordInput: document.querySelector("#registerPasswordInput"),
    registerContactNameInput: document.querySelector("#registerContactNameInput"),
    registerContactPhoneInput: document.querySelector("#registerContactPhoneInput"),
    registerSchoolNameInput: document.querySelector("#registerSchoolNameInput"),
    registerSchoolLevelInput: document.querySelector("#registerSchoolLevelInput"),
    registerSchoolTypeInput: document.querySelector("#registerSchoolTypeInput"),
    registerSchoolNatureInput: document.querySelector("#registerSchoolNatureInput"),
    registerLongitudeInput: document.querySelector("#registerLongitudeInput"),
    registerLatitudeInput: document.querySelector("#registerLatitudeInput"),
    registerProvinceInput: document.querySelector("#registerProvinceInput"),
    registerCityInput: document.querySelector("#registerCityInput"),
    registerDistrictInput: document.querySelector("#registerDistrictInput"),
    registerTownshipInput: document.querySelector("#registerTownshipInput"),
    registerVillageInput: document.querySelector("#registerVillageInput"),
    registerAddressDetailInput: document.querySelector("#registerAddressDetailInput"),
    registerIntroInput: document.querySelector("#registerIntroInput")
};

let mapInstance = null;

document.addEventListener("DOMContentLoaded", () => {
    bindEvents();
    void bootstrap();
});

function bindEvents() {
    elements.locateButton?.addEventListener("click", () => {
        void triggerLocateFlow(true);
    });

    elements.refreshTownButton?.addEventListener("click", () => {
        if (state.currentSchoolId != null) {
            void loadSchoolDetail(state.currentSchoolId, true, true);
        } else if (state.currentTownId != null) {
            void loadTownDetail(state.currentTownId, true);
        } else if (state.userLocation) {
            void loadNearbySchoolsByLocation(state.userLocation, true);
        }
    });

    elements.authEntryButton?.addEventListener("click", () => {
        toggleAuthModal(true);
    });

    elements.authCloseButton?.addEventListener("click", () => {
        toggleAuthModal(false);
    });

    elements.authModalShell?.addEventListener("click", (event) => {
        const target = event.target;
        if (target instanceof HTMLElement && target.dataset.authClose === "true") {
            toggleAuthModal(false);
        }
    });

    elements.showLoginModeButton?.addEventListener("click", () => {
        setAuthMode("login");
    });

    elements.showRegisterModeButton?.addEventListener("click", () => {
        setAuthMode("register");
    });

    elements.registerSchoolNameInput?.addEventListener("input", scheduleRegisterGeoResolve);
    elements.registerProvinceInput?.addEventListener("change", () => void handleRegisterRegionChange("province"));
    elements.registerCityInput?.addEventListener("change", () => void handleRegisterRegionChange("city"));
    elements.registerDistrictInput?.addEventListener("change", () => void handleRegisterRegionChange("district"));
    elements.registerTownshipInput?.addEventListener("change", () => void handleRegisterRegionChange("township"));
    elements.registerVillageInput?.addEventListener("change", () => handleRegisterRegionChange("village"));
    elements.registerAddressDetailInput?.addEventListener("input", () => {
        scheduleRegisterGeoResolve();
    });
    elements.registerLongitudeInput?.addEventListener("input", () => {
        state.registerGeoResolvedBy = "manual";
    });
    elements.registerLatitudeInput?.addEventListener("input", () => {
        state.registerGeoResolvedBy = "manual";
    });

    elements.loginForm?.addEventListener("submit", (event) => {
        event.preventDefault();
        void submitLogin();
    });

    elements.registerForm?.addEventListener("submit", (event) => {
        event.preventDefault();
        void submitRegister();
    });

    elements.currentRegionTrigger?.addEventListener("click", () => {
        if (state.currentSchoolDetail || state.currentTownDetail) {
            setDrawerOpen(!state.drawerOpen);
        }
    });

    elements.closeDrawerButton?.addEventListener("click", () => {
        setDrawerOpen(false);
    });

    elements.explainButton?.addEventListener("click", () => {
        void requestTownExplain();
    });

    elements.askButton?.addEventListener("click", () => {
        void askTownQuestion();
    });

    elements.clearQaButton?.addEventListener("click", () => {
        elements.qaAnswer.innerHTML = "<p>问答结果会显示在这里。</p>";
        elements.qaCitations.innerHTML = "";
        elements.qaStatus.textContent = "已清空";
    });

    elements.toggleBasemapDetail?.addEventListener("click", () => {
        state.basemapDetailed = !state.basemapDetailed;
        applyBasemapDetailMode();
        syncLayerToggleButtons();
        refreshOverlayVisualState();
    });

    elements.toggleProvinceLayer?.addEventListener("click", () => {
        state.layerVisibility.province = !state.layerVisibility.province;
        syncLayerToggleButtons();
        syncAdministrativeLayerStyle();
    });

    elements.toggleCityLayer?.addEventListener("click", () => {
        state.layerVisibility.city = !state.layerVisibility.city;
        syncLayerToggleButtons();
        syncAdministrativeLayerStyle();
    });

    elements.toggleTownshipLayer?.addEventListener("click", () => {
        state.layerVisibility.township = !state.layerVisibility.township;
        syncLayerToggleButtons();
        syncAdministrativeLayerStyle();
        syncRegionPolygonVisibility();
    });
}

async function bootstrap() {
    await loadClientConfig();
    updateMapStatus("正在加载地图。");
    await ensureMapReady();
    await renderHebeiAdministrativeLayer();
    await preloadRegionBoundaries();
    await waitForMapComplete();
    const loggedIn = await tryLoadCurrentAccount();
    if (loggedIn) {
        return;
    }
    setAuthMode("login");
    toggleAuthModal(true);
    setAuthStatus("请先登录；如果还没有账号，可以切换到“学校注册”提交申请。");
    await triggerLocateFlow(false);
}

async function loadClientConfig() {
    try {
        const config = await requestJson("/api/map/client-config");
        state.appConfig = {
            amapKey: String(config?.amapKey || "").trim(),
            amapSecurityJsCode: String(config?.amapSecurityJsCode || "").trim(),
            qaServiceBaseUrl: String(config?.qaServiceBaseUrl || "").trim()
        };
    } catch (error) {
        updateMapStatus("前端地图配置读取失败。");
    }
}

async function tryLoadCurrentAccount() {
    try {
        const currentUser = await requestJson("/api/auth/me");
        if (!currentUser?.accountId) {
            updateAuthEntryLabel(null);
            syncAdminEntry(null);
            return false;
        }
        state.currentAccount = currentUser;
        updateAuthEntryLabel(currentUser);
        syncAdminEntry(currentUser);
        if (currentUser.roleCode === "platform_admin") {
            window.location.href = "/admin.html";
            return true;
        }
        updateMapStatus(`已识别学校账号：${currentUser.schoolName || currentUser.username}，正在进入本校地图。`);
        await loadSchoolDetail(currentUser.schoolId, true, false);
        return true;
    } catch (error) {
        updateAuthEntryLabel(null);
        syncAdminEntry(null);
        return false;
    }
}

function updateAuthEntryLabel(currentUser) {
    if (!elements.authEntryButton) {
        return;
    }
    elements.authEntryButton.textContent = currentUser?.schoolName
        ? `当前学校：${currentUser.schoolName}`
        : currentUser?.roleCode === "platform_admin"
            ? `当前管理员：${currentUser.displayName || currentUser.username}`
            : "学校登录 / 注册";
}

function syncAdminEntry(currentUser) {
    if (!elements.adminEntryLink) {
        return;
    }
    const isAdmin = currentUser?.roleCode === "platform_admin";
    elements.adminEntryLink.classList.toggle("is-active", isAdmin);
    elements.adminEntryLink.textContent = isAdmin ? "进入后台管理" : "管理员登录";
    elements.adminEntryLink.href = isAdmin ? "/admin.html" : "#";
    elements.adminEntryLink.onclick = isAdmin
        ? null
        : (event) => {
            event.preventDefault();
            setAuthMode("login");
            toggleAuthModal(true);
            setAuthStatus("请使用管理员账号登录后进入后台管理页面。");
        };
}

function toggleAuthModal(open) {
    if (!elements.authModalShell) {
        return;
    }
    elements.authModalShell.hidden = !open;
}

function setAuthMode(mode) {
    state.authMode = mode;
    elements.showLoginModeButton?.classList.toggle("is-active", mode === "login");
    elements.showRegisterModeButton?.classList.toggle("is-active", mode === "register");
    elements.loginForm?.classList.toggle("is-hidden", mode !== "login");
    elements.registerForm?.classList.toggle("is-hidden", mode !== "register");
    if (mode === "register") {
        void initRegisterRegionCascade();
        void autofillRegisterCoordinates();
    }
}

function setAuthStatus(text) {
    if (elements.authStatusText) {
        elements.authStatusText.textContent = text;
    }
}

async function submitLogin() {
    const username = firstText(elements.loginUsernameInput?.value);
    const password = String(elements.loginPasswordInput?.value || "");
    if (!username || !password) {
        setAuthStatus("请输入学校账号和密码。");
        return;
    }

    try {
        const currentUser = await requestJson("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({ username, password })
        });
        state.currentAccount = currentUser;
        updateAuthEntryLabel(currentUser);
        syncAdminEntry(currentUser);
        if (currentUser.roleCode === "platform_admin") {
            setAuthStatus("管理员登录成功，正在进入后台管理页面。");
            window.location.href = "/admin.html";
            return;
        }
        setAuthStatus(`登录成功，正在进入 ${currentUser.schoolName || currentUser.username} 的学校地图。`);
        toggleAuthModal(false);
        await loadSchoolDetail(currentUser.schoolId, true, false);
    } catch (error) {
        setAuthStatus(error.message || "登录失败，请重试。");
    }
}

async function submitRegister() {
    const registerCoordinates = await ensureRegisterCoordinates();
    const payload = {
        username: firstText(elements.registerUsernameInput?.value),
        password: String(elements.registerPasswordInput?.value || ""),
        contactName: firstText(elements.registerContactNameInput?.value),
        contactPhone: firstText(elements.registerContactPhoneInput?.value),
        schoolName: firstText(elements.registerSchoolNameInput?.value),
        schoolLevel: elements.registerSchoolLevelInput?.value || "primary",
        schoolType: firstText(elements.registerSchoolTypeInput?.value),
        schoolNature: elements.registerSchoolNatureInput?.value || "public",
        longitude: registerCoordinates.longitude,
        latitude: registerCoordinates.latitude,
        address: buildRegisterAddressText(),
        intro: firstText(elements.registerIntroInput?.value),
        geoSourceType: registerCoordinates.geoSourceType,
        geoConfidence: registerCoordinates.geoConfidence
    };

    if (!payload.username || !payload.password || !payload.schoolName) {
        setAuthStatus("请至少填写账号、密码和学校名称。");
        return;
    }

    try {
        const result = await requestJson("/api/auth/school-register", {
            method: "POST",
            body: JSON.stringify(payload)
        });
        setAuthStatus(`注册申请已提交，申请编号 ${result.registrationId}，请等待管理员审核。`);
        elements.registerForm?.reset();
        state.registerGeoLastQuery = "";
        state.registerGeoResolvedBy = "";
        resetRegisterRegionLevels("province");
        applyRegisterRegionVisibility();
        setAuthMode("login");
    } catch (error) {
        setAuthStatus(error.message || "注册提交失败，请重试。");
    }
}

function parseCoordinate(value) {
    if (value == null || value === "") {
        return null;
    }
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
}

async function ensureRegisterCoordinates() {
    let longitude = parseCoordinate(elements.registerLongitudeInput?.value);
    let latitude = parseCoordinate(elements.registerLatitudeInput?.value);

    if (Number.isFinite(longitude) && Number.isFinite(latitude)) {
        return buildRegisterCoordinatePayload(longitude, latitude);
    }

    const resolved = await resolveRegisterCoordinates();
    if (resolved) {
        return buildRegisterCoordinatePayload(resolved.longitude, resolved.latitude, resolved.resolvedBy);
    }

    return buildRegisterCoordinatePayload(longitude, latitude);
}

function buildRegisterCoordinatePayload(longitude, latitude, resolvedBy = state.registerGeoResolvedBy) {
    const hasCoordinates = Number.isFinite(longitude) && Number.isFinite(latitude);
    if (!hasCoordinates) {
        return {
            longitude: null,
            latitude: null,
            geoSourceType: "manual",
            geoConfidence: "unknown"
        };
    }

    if (resolvedBy === "geocode") {
        return {
            longitude,
            latitude,
            geoSourceType: "amap_poi",
            geoConfidence: "high"
        };
    }

    if (resolvedBy === "locate") {
        return {
            longitude,
            latitude,
            geoSourceType: "amap_poi",
            geoConfidence: "medium"
        };
    }

    return {
        longitude,
        latitude,
        geoSourceType: "manual",
        geoConfidence: "medium"
    };
}

function scheduleRegisterGeoResolve() {
    if (state.authMode !== "register") {
        return;
    }
    if (state.registerGeoLookupTimer) {
        window.clearTimeout(state.registerGeoLookupTimer);
    }
    state.registerGeoLookupTimer = window.setTimeout(() => {
        void resolveRegisterCoordinates();
    }, 500);
}

async function initRegisterRegionCascade() {
    if (!elements.registerProvinceInput) {
        return;
    }
    if (state.registerRegionOptions.province.length > 0) {
        fillRegisterRegionSelect(elements.registerProvinceInput, state.registerRegionOptions.province, "选择省份");
        applyRegisterRegionVisibility();
        return;
    }

    state.registerRegionOptions.province = getFallbackProvinceOptions();
    fillRegisterRegionSelect(elements.registerProvinceInput, state.registerRegionOptions.province, "选择省份");
    resetRegisterRegionLevels("province");
    applyRegisterRegionVisibility();

    const provinces = await loadRegisterRegionChildren("100000", "province");
    if (provinces.length > 0 && !elements.registerProvinceInput.value) {
        state.registerRegionOptions.province = mergeRegionOptions(provinces, getFallbackProvinceOptions());
        fillRegisterRegionSelect(elements.registerProvinceInput, state.registerRegionOptions.province, "选择省份");
        applyRegisterRegionVisibility();
    }
}

async function handleRegisterRegionChange(level) {
    const nextLevel = getNextRegisterRegionLevel(level);
    resetRegisterRegionLevels(level);

    if (!nextLevel) {
        scheduleRegisterGeoResolve();
        return;
    }

    const currentSelect = getRegisterRegionSelect(level);
    const selectedOption = findRegisterRegionOption(level, currentSelect?.value);
    const nextSelect = getRegisterRegionSelect(nextLevel);
    if (!selectedOption || !nextSelect) {
        applyRegisterRegionVisibility();
        scheduleRegisterGeoResolve();
        return;
    }

    setRegionSelectLoading(nextSelect, getRegisterRegionPlaceholder(nextLevel, true));
    let children = await loadRegisterRegionChildren(selectedOption.adcode || selectedOption.name, nextLevel);
    if (children.length === 0) {
        children = await loadLocalRegisterRegionChildren(selectedOption, nextLevel);
    }
    state.registerRegionOptions[nextLevel] = children;
    fillRegisterRegionSelect(nextSelect, children, getRegisterRegionPlaceholder(nextLevel));
    applyRegisterRegionVisibility();
    scheduleRegisterGeoResolve();
}

function resetRegisterRegionLevels(changedLevel) {
    const levels = ["province", "city", "district", "township", "village"];
    const changedIndex = levels.indexOf(changedLevel);
    levels.slice(changedIndex + 1).forEach((level) => {
        state.registerRegionOptions[level] = [];
        const select = getRegisterRegionSelect(level);
        if (select) {
            fillRegisterRegionSelect(select, [], getRegisterRegionPlaceholder(level));
        }
    });
}

async function loadRegisterRegionChildren(keyword, targetLevel) {
    const ready = await ensureAmapDistrictSearchPlugin();
    if (!ready || !window.AMap?.DistrictSearch) {
        return [];
    }

    const config = createDistrictSearchConfig(targetLevel);
    return new Promise((resolve) => {
        let settled = false;
        const finish = (items) => {
            if (settled) {
                return;
            }
            settled = true;
            resolve(items);
        };
        const timer = window.setTimeout(() => finish([]), 3500);

        try {
            const search = new window.AMap.DistrictSearch(config);
            search.search(keyword, (status, result) => {
                window.clearTimeout(timer);
                if (status !== "complete" || !result?.districtList?.length) {
                    finish([]);
                    return;
                }

                const parent = result.districtList[0];
                const children = Array.isArray(parent.districtList) ? parent.districtList : [];
                const sourceList = children.length ? children : result.districtList;
                finish(sourceList.map(normalizeDistrictOption).filter((item) => item && item.level === targetLevel));
            });
        } catch (error) {
            window.clearTimeout(timer);
            finish([]);
        }
    });
}

async function loadLocalRegisterRegionChildren(parentOption, targetLevel) {
    const parentName = firstText(parentOption?.name);
    if (!isHebeiRegisterSelection() && parentName !== "河北省") {
        return [];
    }

    try {
        const params = new URLSearchParams();
        params.set("regionLevel", normalizeLocalRegionLevel(targetLevel));
        const parentRegionId = await resolveLocalRegisterRegionId(parentOption);
        if (parentRegionId != null) {
            params.set("parentRegionId", String(parentRegionId));
        } else if (parentName) {
            params.set("regionName", parentName);
        }
        const records = await requestJson(`/api/regions?${params.toString()}`);
        return Array.isArray(records)
            ? records.map((record) => ({
                name: record.regionName,
                adcode: firstText(record.adcode, `local-${record.regionId}`),
                level: normalizeDistrictLevel(record.regionLevel),
                localRegionId: record.regionId,
                center: Number.isFinite(Number(record.centerLongitude)) && Number.isFinite(Number(record.centerLatitude))
                    ? { longitude: Number(record.centerLongitude), latitude: Number(record.centerLatitude) }
                    : null
            })).filter((item) => item.name)
            : [];
    } catch (error) {
        return [];
    }
}

async function resolveLocalRegisterRegionId(option) {
    const directId = Number(option?.localRegionId);
    if (Number.isFinite(directId) && directId > 0) {
        return directId;
    }

    const regionName = firstText(option?.name);
    const regionLevel = normalizeLocalRegionLevel(option?.level);
    if (!regionName || !regionLevel) {
        return null;
    }

    try {
        const params = new URLSearchParams();
        params.set("regionName", regionName);
        params.set("regionLevel", regionLevel);
        const records = await requestJson(`/api/regions?${params.toString()}`);
        if (!Array.isArray(records)) {
            return null;
        }
        const matchedRecord = records.find((record) => firstText(record.regionName) === regionName);
        return matchedRecord?.regionId ?? null;
    } catch (error) {
        return null;
    }
}

function createDistrictSearchConfig(targetLevel) {
    const subdistrict = targetLevel === "province" ? 1 : 1;
    return {
        subdistrict,
        extensions: "base",
        showbiz: false
    };
}

async function ensureAmapDistrictSearchPlugin() {
    const ready = await ensureMapReady();
    if (!ready || !window.AMap?.plugin) {
        return false;
    }
    if (state.districtSearchPluginReady && window.AMap?.DistrictSearch) {
        return true;
    }

    return new Promise((resolve) => {
        window.AMap.plugin("AMap.DistrictSearch", () => {
            state.districtSearchPluginReady = Boolean(window.AMap?.DistrictSearch);
            resolve(state.districtSearchPluginReady);
        });
    });
}

function normalizeDistrictOption(item) {
    const name = firstText(item?.name);
    if (!name) {
        return null;
    }
    return {
        name,
        adcode: firstText(item.adcode),
        level: normalizeDistrictLevel(item.level),
        center: normalizeLngLat(item.center)
    };
}

function normalizeDistrictLevel(level) {
    switch (String(level || "").toLowerCase()) {
        case "country":
            return "country";
        case "province":
            return "province";
        case "city":
            return "city";
        case "district":
        case "county":
            return "district";
        case "street":
        case "township":
            return "township";
        case "village":
            return "village";
        default:
            return "";
    }
}

function normalizeLocalRegionLevel(level) {
    if (level === "district") {
        return "county";
    }
    return level;
}

function fillRegisterRegionSelect(select, options, placeholder) {
    if (!select) {
        return;
    }
    select.innerHTML = [
        `<option value="">${escapeHtml(placeholder)}</option>`,
        ...options.map((item) => `<option value="${escapeHtml(item.adcode || item.name)}">${escapeHtml(item.name)}</option>`)
    ].join("");
    select.disabled = options.length === 0;
}

function mergeRegionOptions(primaryOptions, fallbackOptions) {
    const seen = new Set();
    return [...primaryOptions, ...fallbackOptions].filter((item) => {
        const key = item.adcode || item.name;
        if (!key || seen.has(key)) {
            return false;
        }
        seen.add(key);
        return true;
    });
}

function setRegionSelectLoading(select, text) {
    if (!select) {
        return;
    }
    select.hidden = false;
    select.disabled = true;
    select.innerHTML = `<option value="">${escapeHtml(text)}</option>`;
}

function applyRegisterRegionVisibility() {
    const levels = ["province", "city", "district", "township", "village"];
    let shouldShow = true;
    levels.forEach((level) => {
        const select = getRegisterRegionSelect(level);
        if (!select) {
            return;
        }
        const hasOptions = state.registerRegionOptions[level]?.length > 0;
        select.hidden = !shouldShow && !hasOptions;
        if (level !== "province" && !hasOptions) {
            select.hidden = true;
        }
        shouldShow = Boolean(select.value) && hasOptions;
    });
}

function buildRegisterAddressText() {
    const names = ["province", "city", "district", "township", "village"]
        .map((level) => findRegisterRegionOption(level, getRegisterRegionSelect(level)?.value)?.name)
        .filter(Boolean);
    const detail = firstText(elements.registerAddressDetailInput?.value);
    return firstText([...names, detail].join(""));
}

function getRegisterRegionSelect(level) {
    switch (level) {
        case "province":
            return elements.registerProvinceInput;
        case "city":
            return elements.registerCityInput;
        case "district":
            return elements.registerDistrictInput;
        case "township":
            return elements.registerTownshipInput;
        case "village":
            return elements.registerVillageInput;
        default:
            return null;
    }
}

function findRegisterRegionOption(level, value) {
    const normalizedValue = firstText(value);
    if (!normalizedValue) {
        return null;
    }
    return state.registerRegionOptions[level]?.find((item) => item.adcode === normalizedValue || item.name === normalizedValue) || null;
}

function isHebeiRegisterSelection() {
    return ["province", "city", "district", "township", "village"]
        .some((level) => findRegisterRegionOption(level, getRegisterRegionSelect(level)?.value)?.name === "河北省");
}

function getNextRegisterRegionLevel(level) {
    const levels = ["province", "city", "district", "township", "village"];
    const index = levels.indexOf(level);
    return index >= 0 ? levels[index + 1] : null;
}

function getRegisterRegionPlaceholder(level, loading = false) {
    const labels = {
        province: "省份",
        city: "城市",
        district: "区县",
        township: "乡镇/街道",
        village: "村/社区"
    };
    return loading ? `正在加载${labels[level]}` : `选择${labels[level]}`;
}

function getFallbackProvinceOptions() {
    return [
        ["北京市", "110000"], ["天津市", "120000"], ["河北省", "130000"], ["山西省", "140000"],
        ["内蒙古自治区", "150000"], ["辽宁省", "210000"], ["吉林省", "220000"], ["黑龙江省", "230000"],
        ["上海市", "310000"], ["江苏省", "320000"], ["浙江省", "330000"], ["安徽省", "340000"],
        ["福建省", "350000"], ["江西省", "360000"], ["山东省", "370000"], ["河南省", "410000"],
        ["湖北省", "420000"], ["湖南省", "430000"], ["广东省", "440000"], ["广西壮族自治区", "450000"],
        ["海南省", "460000"], ["重庆市", "500000"], ["四川省", "510000"], ["贵州省", "520000"],
        ["云南省", "530000"], ["西藏自治区", "540000"], ["陕西省", "610000"], ["甘肃省", "620000"],
        ["青海省", "630000"], ["宁夏回族自治区", "640000"], ["新疆维吾尔自治区", "650000"],
        ["香港特别行政区", "810000"], ["澳门特别行政区", "820000"], ["台湾省", "710000"]
    ].map(([name, adcode]) => ({ name, adcode, level: "province", center: null }));
}

async function autofillRegisterCoordinates() {
    const hasCoordinates = Number.isFinite(parseCoordinate(elements.registerLongitudeInput?.value))
        && Number.isFinite(parseCoordinate(elements.registerLatitudeInput?.value));
    if (hasCoordinates || state.registerGeoResolvedBy === "manual") {
        return;
    }
    await resolveRegisterCoordinates();
}

async function resolveRegisterCoordinates() {
    const schoolName = firstText(elements.registerSchoolNameInput?.value);
    const address = buildRegisterAddressText();
    const geocodeQuery = firstText([schoolName, address].filter(Boolean).join(" "));

    if (geocodeQuery && geocodeQuery === state.registerGeoLastQuery && state.registerGeoResolvedBy === "geocode") {
        return readRegisterCoordinateValues("geocode");
    }

    if (geocodeQuery) {
        const geocodeResult = await geocodeRegisterAddress(geocodeQuery);
        if (geocodeResult) {
            applyRegisterCoordinates(geocodeResult.longitude, geocodeResult.latitude, "geocode");
            state.registerGeoLastQuery = geocodeQuery;
            setAuthStatus("已根据学校名称和地址自动解析经纬度，可继续手动修正。");
            return geocodeResult;
        }
    }

    if (state.userLocation) {
        applyRegisterCoordinates(state.userLocation.longitude, state.userLocation.latitude, "locate");
        setAuthStatus("已自动填入当前位置经纬度；若学校不在当前位置，请补充学校地址后系统会重新解析。");
        return readRegisterCoordinateValues("locate");
    }

    const located = await locateWithAmap();
    if (located) {
        state.userLocation = {
            longitude: Number(located.longitude),
            latitude: Number(located.latitude),
            source: "gcj02",
            accuracy: Number.isFinite(Number(located.accuracy)) ? Number(located.accuracy) : null
        };
        state.userLocationAccuracy = state.userLocation.accuracy;
        state.userLocationAddress = await reverseGeocodeLocation(state.userLocation);
        applyRegisterCoordinates(state.userLocation.longitude, state.userLocation.latitude, "locate");
        setAuthStatus("已自动填入当前位置经纬度；若学校不在当前位置，请补充学校地址后系统会重新解析。");
        return readRegisterCoordinateValues("locate");
    }

    setAuthStatus("暂时无法自动定位，请继续填写信息，或手动补充学校地址 / 经纬度。");
    return null;
}

function applyRegisterCoordinates(longitude, latitude, resolvedBy) {
    if (!Number.isFinite(Number(longitude)) || !Number.isFinite(Number(latitude))) {
        return;
    }
    if (elements.registerLongitudeInput) {
        elements.registerLongitudeInput.value = Number(longitude).toFixed(7);
    }
    if (elements.registerLatitudeInput) {
        elements.registerLatitudeInput.value = Number(latitude).toFixed(7);
    }
    state.registerGeoResolvedBy = resolvedBy;
}

function readRegisterCoordinateValues(resolvedBy) {
    const longitude = parseCoordinate(elements.registerLongitudeInput?.value);
    const latitude = parseCoordinate(elements.registerLatitudeInput?.value);
    if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
        return null;
    }
    return {
        longitude,
        latitude,
        resolvedBy
    };
}

async function ensureMapReady() {
    if (state.mapReady) {
        return true;
    }

    const amapKey = state.appConfig.amapKey;
    if (!amapKey) {
        updateMapStatus("未配置高德地图 Key，请在 application.yml 中填写 app.map.amap-key。");
        return false;
    }

    if (!window.AMap && !state.mapSdkLoading) {
        state.mapSdkLoading = true;
        const securityJsCode = state.appConfig.amapSecurityJsCode;
        if (securityJsCode) {
            window._AMapSecurityConfig = { securityJsCode };
        }
        try {
            await loadScript(`https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(amapKey)}`);
            if (!window.AMap?.Map) {
                updateMapStatus("高德地图鉴权未通过，已启用地区点击视图。");
                state.mapSdkLoading = false;
                return false;
            }
        } catch (error) {
            updateMapStatus("高德地图 SDK 加载失败，已启用地区点击视图。");
            state.mapSdkLoading = false;
            return false;
        }
    }

    if (!window.AMap?.Map) {
        return false;
    }

    if (!mapInstance) {
        mapInstance = new window.AMap.Map("mapCanvas", {
            zoom: 8.9,
            center: [114.48, 38.45],
            mapStyle: "amap://styles/normal",
            viewMode: "2D",
            showLabel: true,
            zooms: [4, 20]
        });

        state.currentZoom = readCurrentZoom();
        mapInstance.on?.("zoomend", () => {
            state.currentZoom = readCurrentZoom();
            applyBasemapDetailMode();
            syncLayerToggleButtons();
            refreshOverlayVisualState();
            syncRegionLabelVisibility();
            syncRegionLabelStyles();
        });
    }

    state.mapReady = true;
    state.mapSdkLoading = false;
    state.currentZoom = readCurrentZoom();
    applyBasemapDetailMode();
    syncLayerToggleButtons();
    updateMapStatus("地图已初始化，可直接点击地区。");
    return true;
}

async function renderHebeiAdministrativeLayer() {
    const ready = await ensureMapReady();
    if (!ready || !mapInstance || !window.AMap?.DistrictLayer?.Province) {
        return;
    }

    if (state.hebeiAdministrativeLayer) {
        return;
    }

    state.hebeiAdministrativeLayer = new window.AMap.DistrictLayer.Province({
        adcode: ["130000"],
        depth: 2,
        zIndex: 6,
        opacity: 1,
        zooms: [6, 20],
        styles: createAdministrativeLayerStyles()
    });

    mapInstance.add(state.hebeiAdministrativeLayer);
}

async function triggerLocateFlow(isManual) {
    state.lastLocateErrorText = "";
    updateMapStatus("正在通过高德定位获取当前位置。");
    const amapLocation = await locateWithAmap();
    if (amapLocation) {
        await handleLocatedUserPosition(amapLocation, isManual);
        return;
    }
    clearTownDetail(false);
    updateMapStatus(state.lastLocateErrorText || "高德定位失败，请检查定位权限、浏览器站点定位授权或网络后重试。");
}

async function locateWithAmap() {
    const plugin = await ensureAmapGeolocationPlugin();
    if (!plugin) {
        return null;
    }

    const firstAttempt = await requestAmapCurrentPosition(plugin);
    if (firstAttempt?.position) {
        return {
            longitude: firstAttempt.position.longitude,
            latitude: firstAttempt.position.latitude,
            source: "amap",
            accuracy: firstAttempt.accuracy
        };
    }

    if (firstAttempt?.errorText) {
        updateMapStatus(firstAttempt.errorText);
    }

    await waitFor(1200);

    const secondAttempt = await requestAmapCurrentPosition(plugin);
    if (secondAttempt?.position) {
        return {
            longitude: secondAttempt.position.longitude,
            latitude: secondAttempt.position.latitude,
            source: "amap",
            accuracy: secondAttempt.accuracy
        };
    }

    if (secondAttempt?.errorText) {
        updateMapStatus(secondAttempt.errorText);
    }

    return null;
}

async function requestAmapCurrentPosition(plugin) {
    return new Promise((resolve) => {
        plugin.getCurrentPosition((status, result) => {
            console.log("AMap geolocation callback", { status, result });
            const normalizedPosition = normalizeLngLat(result?.position);
            if (status === "complete" && normalizedPosition) {
                const accuracy = Number(result.accuracy);
                const accuracyText = Number.isFinite(accuracy) ? `，精度约 ${Math.round(accuracy)} 米` : "";
                updateMapStatus(`高德定位成功${accuracyText}。`);
                resolve({ position: normalizedPosition, accuracy: Number.isFinite(accuracy) ? accuracy : null });
                return;
            }

            const info = String(result?.info || "").trim();
            const message = String(result?.message || "").trim();
            const locationType = String(result?.location_type || "").trim();
            const errorText = formatAmapLocateError(info, message, locationType);
            state.lastLocateErrorText = errorText;
            resolve({ position: null, errorText });
        });
    });
}

async function ensureAmapGeolocationPlugin() {
    const ready = await ensureMapReady();
    if (!ready || !mapInstance || !window.AMap?.plugin) {
        return null;
    }

    if (state.geolocationPlugin) {
        return state.geolocationPlugin;
    }

    return new Promise((resolve) => {
        window.AMap.plugin("AMap.Geolocation", () => {
            if (!window.AMap?.Geolocation) {
                resolve(null);
                return;
            }

            state.geolocationPlugin = new window.AMap.Geolocation({
                enableHighAccuracy: true,
                timeout: 15000,
                maximumAge: 0,
                convert: true,
                useNative: false,
                GeoLocationFirst: true,
                noIpLocate: 3,
                noGeoLocation: 0,
                getCityWhenFail: true,
                needAddress: true,
                extensions: "all",
                zoomToAccuracy: false,
                showMarker: false,
                showCircle: false,
                panToLocation: false,
                showButton: false
            });
            resolve(state.geolocationPlugin);
        });
    });
}

async function handleLocatedUserPosition(locationResult, shouldOpenDrawer) {
    if (!Number.isFinite(Number(locationResult?.longitude)) || !Number.isFinite(Number(locationResult?.latitude))) {
        clearTownDetail(false);
        updateMapStatus("高德已返回定位结果，但经纬度解析失败。");
        return;
    }

    const location = {
        longitude: Number(locationResult.longitude),
        latitude: Number(locationResult.latitude),
        source: "gcj02",
        accuracy: Number.isFinite(Number(locationResult.accuracy)) ? Number(locationResult.accuracy) : null
    };
    state.userLocation = location;
    state.userLocationAccuracy = location.accuracy;
    state.userLocationAddress = await reverseGeocodeLocation(location);
    await showUserLocation(location, true);
    syncUserLocationSummary();
    await loadNearbySchoolsByLocation(location, shouldOpenDrawer);
}

async function loadNearbySchoolsByLocation(location, shouldOpenDrawer) {
    if (!location) {
        return;
    }

    try {
        const params = new URLSearchParams({
            longitude: String(location.longitude),
            latitude: String(location.latitude),
            radiusKm: "30",
            limit: "10"
        });
        const schools = await requestJson(`/api/school-map/schools/nearby?${params.toString()}`);
        state.nearbySchools = Array.isArray(schools) ? schools : [];
        renderNearbySchoolList(state.nearbySchools);

        if (state.nearbySchools.length > 0) {
            const nearestSchool = state.nearbySchools[0];
            updateMapStatus(`已定位到当前位置，并找到附近 ${state.nearbySchools.length} 所学校。`);
            await loadSchoolDetail(nearestSchool.schoolId, shouldOpenDrawer, true);
            return;
        }

        clearSchoolDetail(false);
        await renderSchoolMarkers(state.nearbySchools, []);
        updateMapStatus("当前位置附近暂无学校数据，请从学校列表选择或先录入学校样例数据。");
    } catch (error) {
        clearSchoolDetail(false);
        updateMapStatus("已定位到你的真实位置，但附近学校接口暂不可用。");
    }
}

async function locateTownByCoordinate(longitude, latitude, shouldOpenDrawer) {
    try {
        const response = await requestJson("/api/map/locate-town", {
            method: "POST",
            body: JSON.stringify({ longitude, latitude })
        });

        const town = response?.town;
        if (!town?.regionId) {
            clearTownDetail(false);
            updateMapStatus(response?.message || "已定位到你的真实位置，但当前未匹配到项目中的乡镇边界。");
            return;
        }

        updateMapStatus(response?.message || "已完成乡镇定位。");
        await loadTownDetail(town.regionId, shouldOpenDrawer, true);
    } catch (error) {
        clearTownDetail(false);
        updateMapStatus("已定位到你的真实位置，但乡镇匹配接口暂不可用。");
    }
}

async function showUserLocation(location, recenter = false) {
    const ready = await ensureMapReady();
    if (!ready || !mapInstance || !location) {
        return;
    }

    const position = [Number(location.longitude), Number(location.latitude)];
    upsertUserLocationMarker(position);
    upsertUserAccuracyCircle(position, location.accuracy);

    if (recenter) {
        mapInstance.setCenter(position);
        mapInstance.setZoom(Math.max(readCurrentZoom(), 16));
    }
}

function upsertUserLocationMarker(position) {
    if (!mapInstance || !window.AMap?.Marker) {
        return;
    }

    if (!state.userLocationMarker) {
        state.userLocationMarker = new window.AMap.Marker({
            position,
            title: "我的位置",
            anchor: "bottom-center",
            content: renderMapMarkerContent({ type: "user", name: "我的位置" }, true),
            offset: new window.AMap.Pixel(0, 0),
            zIndex: 44
        });
        mapInstance.add(state.userLocationMarker);
        return;
    }

    state.userLocationMarker.setPosition(position);
}

function clearTownDetail(clearUserLocation = false) {
    state.currentTownId = null;
    state.currentTownName = "";
    state.currentTownDetail = null;
    state.currentSchoolId = null;
    state.currentSchoolDetail = null;
    state.nearbySchools = [];
    state.preserveUserLocationCenter = false;
    if (clearUserLocation) {
        state.userLocation = null;
        state.userLocationAddress = null;
        state.userLocationAccuracy = null;
    }
    clearMapMarkers();
    clearBoundaryPolygons();
    clearUserAccuracyCircle();
    highlightActiveRegion(null);
    setDrawerOpen(false);

    renderCollection(elements.eventList, [], renderEventCard);
    renderCollection(elements.markerList, [], renderMarkerCard);
    renderCollection(elements.heroList, [], renderHeroCard);
    renderCollection(elements.storyList, [], renderStoryCard);
    renderQuestionList([]);

    elements.eventCount.textContent = "0";
    elements.markerCount.textContent = "0";
    elements.heroCount.textContent = "0";
    elements.storyCount.textContent = "0";
    elements.questionCount.textContent = "0";
    elements.factMarkerCount.textContent = "0";
    elements.factHeroCount.textContent = "0";
    elements.factStoryCount.textContent = "0";
    elements.factEventCount.textContent = "0";

    syncUserLocationSummary();
    elements.graphStatus.textContent = "未匹配乡镇";
    elements.townName.textContent = "尚未匹配到项目乡镇";
    elements.townIntro.textContent = "已经定位到你的真实位置，但该位置尚未落入项目当前维护的乡镇边界内。你可以直接点击地图中的地区查看详情。";
}

function clearSchoolDetail(clearUserLocation = false) {
    clearTownDetail(clearUserLocation);

    syncUserLocationSummary();
    elements.graphStatus.textContent = "未匹配学校";
    elements.townName.textContent = "尚未匹配到附近学校";
    elements.townIntro.textContent = "已经定位到你的真实位置，但当前还没有找到可展示的学校数据。你可以从学校列表中选择，或者先补充学校样例数据。";

    renderCollection(elements.eventList, [], renderSchoolCard);
    renderCollection(elements.markerList, [], renderResourceCard);
    renderCollection(elements.heroList, [], renderActivityPlanCard);
    renderCollection(elements.storyList, [], renderThemeCard);
    renderQuestionList([]);

    elements.eventCount.textContent = "0";
    elements.markerCount.textContent = "0";
    elements.heroCount.textContent = "0";
    elements.storyCount.textContent = "0";
    elements.questionCount.textContent = "0";
    elements.factMarkerCount.textContent = "0";
    elements.factHeroCount.textContent = "0";
    elements.factStoryCount.textContent = "0";
    elements.factEventCount.textContent = "0";
}

async function ensureAmapGeocoderPlugin() {
    const ready = await ensureMapReady();
    if (!ready || !window.AMap?.plugin) {
        return null;
    }

    if (state.geocoderPlugin) {
        return state.geocoderPlugin;
    }

    return new Promise((resolve) => {
        window.AMap.plugin("AMap.Geocoder", () => {
            if (!window.AMap?.Geocoder) {
                resolve(null);
                return;
            }

            state.geocoderPlugin = new window.AMap.Geocoder({
                radius: 1000,
                extensions: "all"
            });
            resolve(state.geocoderPlugin);
        });
    });
}

async function reverseGeocodeLocation(location) {
    if (!location) {
        return null;
    }

    const geocoder = await ensureAmapGeocoderPlugin();
    if (!geocoder) {
        return null;
    }

    return new Promise((resolve) => {
        geocoder.getAddress([location.longitude, location.latitude], (status, result) => {
            const regeocode = status === "complete" ? result?.regeocode : null;
            if (!regeocode) {
                resolve(null);
                return;
            }

            const displayName = firstText(
                regeocode.pois?.[0]?.name,
                regeocode.aois?.[0]?.name,
                regeocode.addressComponent?.building?.name,
                regeocode.addressComponent?.neighborhood?.name,
                "我的位置"
            );
            const formattedAddress = firstText(
                regeocode.formattedAddress,
                [
                    regeocode.addressComponent?.province,
                    regeocode.addressComponent?.city,
                    regeocode.addressComponent?.district,
                    regeocode.addressComponent?.township,
                    regeocode.addressComponent?.street,
                    regeocode.addressComponent?.streetNumber
                ].filter(Boolean).join("")
            );
            resolve({
                displayName,
                formattedAddress
            });
        });
    });
}

async function geocodeRegisterAddress(address) {
    const keyword = firstText(address);
    if (!keyword) {
        return null;
    }

    const geocoder = await ensureAmapGeocoderPlugin();
    if (!geocoder) {
        return null;
    }

    return new Promise((resolve) => {
        geocoder.getLocation(keyword, (status, result) => {
            const geocodes = status === "complete" ? result?.geocodes : null;
            const firstGeocode = Array.isArray(geocodes) ? geocodes[0] : null;
            const location = normalizeLngLat(firstGeocode?.location);
            if (!location) {
                resolve(null);
                return;
            }

            resolve({
                longitude: location.longitude,
                latitude: location.latitude,
                formattedAddress: firstText(firstGeocode.formattedAddress, keyword)
            });
        });
    });
}

function syncUserLocationSummary() {
    elements.toolbarTownName.textContent = state.currentSchoolDetail?.school?.schoolName
        || state.currentTownDetail?.regionName
        || state.userLocationAddress?.displayName
        || "我的位置";

    const address = state.userLocationAddress?.formattedAddress;
    const accuracy = Number.isFinite(Number(state.userLocationAccuracy)) ? `，误差约 ${Math.round(state.userLocationAccuracy)} 米` : "";
    if (state.currentSchoolDetail?.school) {
        const school = state.currentSchoolDetail.school;
        const schoolAddress = school.address || address || "学校位置未填写";
        elements.townCenterLabel.textContent = `学校位置：${schoolAddress}${accuracy}`;
        return;
    }

    if (state.currentTownDetail?.center) {
        const center = state.currentTownDetail.center;
        elements.townCenterLabel.textContent = `当前位置：${center.longitude ?? "--"}, ${center.latitude ?? "--"}${accuracy}`;
        return;
    }

    if (address) {
        elements.townCenterLabel.textContent = `当前位置：${address}${accuracy}`;
        return;
    }

    if (state.userLocation) {
        elements.townCenterLabel.textContent = `当前位置：${state.userLocation.longitude.toFixed(6)}, ${state.userLocation.latitude.toFixed(6)}${accuracy}`;
        return;
    }

    elements.townCenterLabel.textContent = "当前位置：--";
}

function upsertUserAccuracyCircle(position, accuracy) {
    if (!mapInstance || !window.AMap?.Circle || !Number.isFinite(Number(accuracy))) {
        clearUserAccuracyCircle();
        return;
    }

    const radius = Math.max(25, Math.min(Number(accuracy), 1200));
    if (!state.userLocationAccuracyCircle) {
        state.userLocationAccuracyCircle = new window.AMap.Circle({
            center: position,
            radius,
            strokeColor: "rgba(33, 123, 255, 0.34)",
            strokeWeight: 1.5,
            fillColor: "rgba(33, 123, 255, 0.12)",
            fillOpacity: 0.16,
            zIndex: 18
        });
        mapInstance.add(state.userLocationAccuracyCircle);
        return;
    }

    state.userLocationAccuracyCircle.setCenter(position);
    state.userLocationAccuracyCircle.setRadius(radius);
}

function clearUserAccuracyCircle() {
    if (mapInstance && state.userLocationAccuracyCircle) {
        mapInstance.remove(state.userLocationAccuracyCircle);
    }
    state.userLocationAccuracyCircle = null;
}

function waitFor(timeoutMs) {
    return new Promise((resolve) => {
        window.setTimeout(resolve, timeoutMs);
    });
}

async function waitForMapComplete() {
    if (!mapInstance?.on) {
        return;
    }

    await new Promise((resolve) => {
        let done = false;
        const finish = () => {
            if (done) {
                return;
            }
            done = true;
            resolve();
        };

        mapInstance.on("complete", finish);
        window.setTimeout(finish, 2500);
    });
}

function normalizeLngLat(position) {
    if (!position) {
        return null;
    }

    const longitude = extractCoordinate(position, "lng", "getLng");
    const latitude = extractCoordinate(position, "lat", "getLat");
    if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
        return null;
    }

    return { longitude, latitude };
}

function extractCoordinate(target, propertyName, getterName) {
    const directValue = target?.[propertyName];
    if (Number.isFinite(Number(directValue))) {
        return Number(directValue);
    }

    const getter = target?.[getterName];
    if (typeof getter === "function") {
        const value = getter.call(target);
        if (Number.isFinite(Number(value))) {
            return Number(value);
        }
    }

    return NaN;
}

function firstText(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function formatAmapLocateError(info, message, locationType) {
    const combined = `${info} ${message} ${locationType}`.toLowerCase();
    if (combined.includes("permission denied")) {
        return "浏览器已拒绝定位权限，当前只能拿到城市级粗定位，无法定位到你本人。请点击地址栏左侧站点信息，允许“位置”权限后刷新页面，或点击“定位我的位置”重试。";
    }

    if (combined.includes("ipcity")) {
        return "高德只返回了城市级 IP 粗定位，没有拿到你的真实位置。请确认浏览器位置权限已允许，并在系统里开启定位服务后重试。";
    }

    return [
        "高德定位失败",
        locationType ? `来源：${locationType}` : "",
        info ? `信息：${info}` : "",
        message ? `原因：${message}` : ""
    ].filter(Boolean).join("，");
}

async function preloadRegionBoundaries() {
    try {
        const boundaries = await requestJson("/api/map/regions/boundaries?ancestorRegionId=1");
        state.regionRecords = Array.isArray(boundaries) ? boundaries : [];
        state.allRegions = state.regionRecords.slice();
        state.regions = state.regionRecords.filter((item) => item.regionLevel === "township");
        state.towns = state.regions.slice().sort((left, right) => {
            return String(left.regionName || "").localeCompare(String(right.regionName || ""), "zh-CN");
        });
        await renderRegionBoundaries(state.towns);
        await renderRegionLabels(state.regionRecords);
    } catch (error) {
        updateMapStatus("地区轮廓加载失败。");
    }
}

async function loadTownDetail(regionId, shouldOpenDrawer = true, preserveUserLocationCenter = false) {
    try {
        const detail = await requestJson(`/api/map/towns/${encodeURIComponent(regionId)}`);
        state.currentTownId = detail.regionId;
        state.currentTownName = detail.regionName || "";
        state.currentTownDetail = detail;
        state.currentSchoolId = null;
        state.currentSchoolDetail = null;
        renderTownDetail(detail);
        highlightActiveRegion(detail.regionId);
        state.preserveUserLocationCenter = preserveUserLocationCenter;
        if (shouldOpenDrawer) {
            setDrawerOpen(true);
        }
    } catch (error) {
        updateMapStatus("地区详情加载失败。");
    }
}

async function loadSchoolDetail(schoolId, shouldOpenDrawer = true, preserveUserLocationCenter = false) {
    if (schoolId == null) {
        return;
    }

    try {
        const detail = await requestJson(`/api/school-map/schools/${encodeURIComponent(schoolId)}/detail`);
        state.currentTownId = null;
        state.currentTownName = "";
        state.currentTownDetail = null;
        state.currentSchoolId = detail.school?.schoolId ?? schoolId;
        state.currentSchoolDetail = detail;
        state.preserveUserLocationCenter = preserveUserLocationCenter;
        renderSchoolDetail(detail);
        highlightActiveRegion(null);
        if (shouldOpenDrawer) {
            setDrawerOpen(true);
        }
    } catch (error) {
        updateMapStatus("学校详情加载失败。");
    }
}

function renderSchoolDetail(detail) {
    const school = detail.school || {};
    const resources = Array.isArray(detail.resources) ? detail.resources : [];
    const activityPlans = Array.isArray(detail.activityPlans) ? detail.activityPlans : [];
    const themes = buildSchoolThemeSummaries(resources);

    elements.toolbarTownName.textContent = school.schoolName || "未知学校";
    elements.townName.textContent = school.schoolName || "未知学校";
    elements.townIntro.textContent = buildSchoolIntro(school, resources, activityPlans);
    syncUserLocationSummary();
    elements.graphStatus.textContent = "学校资源";

    updateMapStatus(`已加载 ${school.schoolName || "学校"} 的周边思政教育资源。`);

    renderNearbySchoolList(state.nearbySchools);
    renderCollection(elements.markerList, resources, renderResourceCard);
    renderCollection(elements.heroList, activityPlans, renderActivityPlanCard);
    renderCollection(elements.storyList, themes, renderThemeCard);
    renderQuestionList(buildSchoolQuestions(detail));

    elements.eventCount.textContent = String(state.nearbySchools.length || 0);
    elements.markerCount.textContent = String(resources.length);
    elements.heroCount.textContent = String(activityPlans.length);
    elements.storyCount.textContent = String(themes.length);
    elements.questionCount.textContent = "4";
    elements.factMarkerCount.textContent = String(resources.length);
    elements.factHeroCount.textContent = String(activityPlans.length);
    elements.factStoryCount.textContent = String(state.nearbySchools.length || 0);
    elements.factEventCount.textContent = String(countResourceCategories(resources));

    void syncMapWithSchool(detail);
}

function renderNearbySchoolList(schools) {
    renderCollection(elements.eventList, Array.isArray(schools) ? schools : [], renderSchoolCard);
}

function buildSchoolIntro(school, resources, activityPlans) {
    const parts = [];
    if (school.address) {
        parts.push(`学校地址：${school.address}`);
    }
    if (school.distanceKm != null) {
        parts.push(`距离当前位置约 ${school.distanceKm} 公里`);
    }
    parts.push(`当前已关联 ${resources.length} 个周边思政资源、${activityPlans.length} 条教学活动建议。`);
    return parts.join("。");
}

function buildSchoolThemeSummaries(resources) {
    return resources
        .filter((item) => item.educationThemeSummary || item.resource?.educationValue)
        .map((item) => ({
            title: item.educationThemeSummary || item.resource?.resourceName || "资源主题",
            summary: item.resource?.educationValue || item.resource?.intro || "可结合学校周边真实资源开展课堂讲解或实践活动。",
            meta: [
                categoryLabel(item.resource?.resourceCategory),
                distanceText(item.distanceMeters)
            ].filter(Boolean)
        }));
}

function buildSchoolQuestions(detail) {
    const schoolName = detail.school?.schoolName || "这所学校";
    return [
        `${schoolName}周边最适合开展哪类思政实践活动？`,
        `${schoolName}可以怎样设计一条敬老或志愿服务路线？`,
        `${schoolName}周边有哪些资源适合融入课堂教学？`,
        `${schoolName}如何把本地文化资源转化为一节思政课？`
    ];
}

async function syncMapWithSchool(detail) {
    const school = detail.school || {};
    const resources = Array.isArray(detail.resources) ? detail.resources : [];

    clearMapMarkers();
    clearBoundaryPolygons();

    const ready = await ensureMapReady();
    if (!ready || !mapInstance) {
        return;
    }

    await renderHebeiAdministrativeLayer();

    const markers = [];
    const schoolMarker = toSchoolMarker(school);
    if (schoolMarker) {
        markers.push(schoolMarker);
    }
    for (const item of resources) {
        const marker = toResourceMarker(item);
        if (marker) {
            markers.push(marker);
        }
    }

    await renderSchoolMarkers([], markers);

    const targets = state.markerInstances.slice();
    if (state.userLocation && state.preserveUserLocationCenter) {
        mapInstance.setCenter([Number(state.userLocation.longitude), Number(state.userLocation.latitude)]);
        mapInstance.setZoom(Math.max(readCurrentZoom(), 13.8));
        return;
    }

    if (targets.length > 0) {
        mapInstance.setFitView(targets, false, [60, 70, 60, 430]);
        return;
    }

    if (Number.isFinite(Number(school.longitude)) && Number.isFinite(Number(school.latitude))) {
        mapInstance.setCenter([Number(school.longitude), Number(school.latitude)]);
        mapInstance.setZoom(15);
    }
}

async function renderSchoolMarkers(schools, resourceMarkers) {
    const ready = await ensureMapReady();
    if (!ready || !mapInstance || !window.AMap?.Marker) {
        return;
    }

    clearMapMarkers();

    const markers = [];
    const schoolItems = Array.isArray(schools) ? schools.map(toSchoolMarker).filter(Boolean) : [];
    const resourceItems = Array.isArray(resourceMarkers) ? resourceMarkers.filter(Boolean) : [];
    markers.push(...schoolItems, ...resourceItems);

    for (const marker of markers) {
        const instance = createMapMarker(marker);
        state.markerInstances.push(instance);
        state.markerIndex.set(`${marker.type}-${marker.id}`, instance);
    }

    if (state.markerInstances.length > 0) {
        mapInstance.add(state.markerInstances);
    }
}

function toSchoolMarker(school) {
    if (!school || !Number.isFinite(Number(school.longitude)) || !Number.isFinite(Number(school.latitude))) {
        return null;
    }
    return {
        type: "school",
        id: school.schoolId,
        name: school.schoolName,
        longitude: school.longitude,
        latitude: school.latitude,
        address: school.address,
        summary: school.schoolType || "乡村学校",
        relationHint: school.distanceKm != null ? `距你约 ${school.distanceKm} 公里` : "学校坐标"
    };
}

function toResourceMarker(item) {
    const resource = item?.resource || {};
    if (!Number.isFinite(Number(resource.longitude)) || !Number.isFinite(Number(resource.latitude))) {
        return null;
    }
    return {
        type: `resource_${resource.resourceCategory || "other"}`,
        id: resource.resourceId,
        name: resource.resourceName,
        longitude: resource.longitude,
        latitude: resource.latitude,
        address: resource.address,
        summary: resource.intro || resource.educationValue || "本土思政教育资源",
        relationHint: [
            categoryLabel(resource.resourceCategory),
            distanceText(item.distanceMeters)
        ].filter(Boolean).join(" · ")
    };
}

function renderTownDetail(detail) {
    const regionName = detail.regionName || "未知地区";
    const center = detail.center || {};

    elements.toolbarTownName.textContent = regionName;
    elements.townName.textContent = regionName;
    elements.townIntro.textContent = detail.intro || "该地区暂无补充简介，可结合革命事件、遗址和人物内容继续讲解。";
    elements.townCenterLabel.textContent = `中心点：${center.longitude ?? "--"}, ${center.latitude ?? "--"}`;
    elements.graphStatus.textContent = detail.graphAvailable ? "图谱已命中" : "基础回退";

    updateMapStatus(detail.graphStatusMessage || "已加载地区详情，可继续点击其他地区切换。");

    renderCollection(elements.eventList, detail.events, renderEventCard);
    renderCollection(elements.markerList, detail.markers, renderMarkerCard);
    renderCollection(elements.heroList, detail.heroes, renderHeroCard);
    renderCollection(elements.storyList, detail.stories, renderStoryCard);
    renderQuestionList(detail.suggestedQuestions || []);

    elements.eventCount.textContent = String(detail.events?.length ?? 0);
    elements.markerCount.textContent = String(detail.markers?.length ?? 0);
    elements.heroCount.textContent = String(detail.heroes?.length ?? 0);
    elements.storyCount.textContent = String(detail.stories?.length ?? 0);
    elements.questionCount.textContent = String(detail.suggestedQuestions?.length ?? 0);
    elements.factMarkerCount.textContent = String(detail.markers?.length ?? 0);
    elements.factHeroCount.textContent = String(detail.heroes?.length ?? 0);
    elements.factStoryCount.textContent = String(detail.stories?.length ?? 0);
    elements.factEventCount.textContent = String(detail.events?.length ?? 0);

    void syncMapWithTown(detail);
}

async function syncMapWithTown(detail) {
    const center = detail.center || {};
    const centerPoint = [Number(center.longitude || 113.9776), Number(center.latitude || 38.3432)];

    clearMapMarkers();
    clearBoundaryPolygons();

    const ready = await ensureMapReady();
    if (ready && mapInstance) {
        await renderHebeiAdministrativeLayer();

        const boundsTargets = [];
        if (detail.markers?.length) {
            for (const marker of detail.markers) {
                if (marker.longitude == null || marker.latitude == null) {
                    continue;
                }
                const instance = createMapMarker(marker);
                state.markerInstances.push(instance);
                state.markerIndex.set(`${marker.type}-${marker.id}`, instance);
                boundsTargets.push(instance);
            }
            mapInstance.add(state.markerInstances);
        }

        if (detail.boundaryGeoJson) {
            drawBoundary(detail);
        }

        if (state.userLocation && state.preserveUserLocationCenter) {
            mapInstance.setCenter([Number(state.userLocation.longitude), Number(state.userLocation.latitude)]);
            mapInstance.setZoom(Math.max(readCurrentZoom(), 13.2));
            return;
        }

        if (state.boundaryPolygons.length > 0 || boundsTargets.length > 0) {
            mapInstance.setFitView([...state.boundaryPolygons, ...boundsTargets], false, [56, 72, 56, 420]);
            return;
        }

        mapInstance.setCenter(centerPoint);
        mapInstance.setZoom(12.2);
        return;
    }

    if (detail.boundaryGeoJson) {
        await renderStandaloneBoundary(detail);
    }
}

function drawBoundary(detail) {
    if (!mapInstance || !window.AMap?.Polygon || !detail.boundaryGeoJson) {
        return;
    }

    try {
        const json = JSON.parse(detail.boundaryGeoJson);
        const polygons = geoJsonToPathList(json).map((path) => new window.AMap.Polygon({
            path,
            strokeColor: "#2d86ff",
            strokeWeight: state.basemapDetailed ? 2.2 : 2.4,
            fillColor: "#2d86ff",
            fillOpacity: state.basemapDetailed ? 0.035 : 0.06,
            bubble: true,
            zIndex: 11
        }));

        polygons.forEach((polygon) => {
            polygon.on("click", () => {
                if (state.currentTownId !== detail.regionId) {
                    void loadTownDetail(detail.regionId, true);
                }
            });
        });

        state.boundaryPolygons = polygons;
        mapInstance.add(polygons);
    } catch (error) {
        updateMapStatus("当前地区边界解析失败。");
    }
}

async function renderStandaloneBoundary(detail) {
    const canvas = document.querySelector("#mapCanvas");
    if (!canvas) {
        return;
    }
    canvas.style.background = `
        radial-gradient(circle at 18% 14%, rgba(45, 134, 255, 0.15), transparent 20%),
        linear-gradient(180deg, rgba(109, 182, 255, 0.08), rgba(255, 255, 255, 0) 30%),
        linear-gradient(180deg, #eef6ff 0%, #e7f1fb 100%)
    `;
}

async function renderRegionBoundaries(boundaries) {
    clearRegionBoundaryPolygons();

    const ready = await ensureMapReady();
    if (!ready || !mapInstance || !window.AMap?.Polygon) {
        updateMapStatus("地图底图未启用，但地区详情可通过当前定位与接口加载。");
        return;
    }

    const polygons = [];
    for (const boundary of boundaries) {
        if (!boundary?.boundaryGeoJson) {
            continue;
        }
        try {
            const json = JSON.parse(boundary.boundaryGeoJson);
            const pathList = geoJsonToPathList(json);
            pathList.forEach((path) => {
                const polygon = new window.AMap.Polygon({
                    path,
                    ...createTownshipPolygonStyle(boundary.regionId === state.currentTownId),
                    bubble: true,
                    zIndex: 9
                });

                polygon.__regionId = boundary.regionId;
                polygon.__regionLevel = boundary.regionLevel;
                polygon.__regionName = boundary.regionName;

                polygon.on("mouseover", () => {
                    if (polygon.__regionId !== state.currentTownId) {
                        polygon.setOptions({
                            fillOpacity: state.basemapDetailed ? 0.02 : 0.035,
                            strokeColor: "#5b9eff"
                        });
                    }
                });

                polygon.on("mouseout", () => {
                    polygon.setOptions(createTownshipPolygonStyle(polygon.__regionId === state.currentTownId));
                });

                polygon.on("click", () => {
                    void loadTownDetail(boundary.regionId, true);
                });

                polygons.push(polygon);
            });
        } catch (error) {
            // skip broken geometry
        }
    }

    state.regionBoundaryPolygons = polygons;
    mapInstance.add(polygons);
    syncRegionPolygonVisibility();
}

function highlightActiveRegion(regionId) {
    state.regionBoundaryPolygons.forEach((polygon) => {
        polygon.setOptions(createTownshipPolygonStyle(polygon.__regionId === regionId));
    });
    syncRegionLabelStyles();
}

function syncRegionPolygonVisibility() {
    state.regionBoundaryPolygons.forEach((polygon) => {
        const style = createTownshipPolygonStyle(polygon.__regionId === state.currentTownId);
        polygon.show?.();
        polygon.setOptions({
            strokeColor: style.strokeColor,
            strokeWeight: style.strokeWeight,
            fillColor: style.fillColor,
            strokeOpacity: state.layerVisibility.township ? 1 : 0,
            fillOpacity: state.layerVisibility.township ? style.fillOpacity : 0
        });
    });
}

function clearBoundaryPolygons() {
    if (mapInstance && state.boundaryPolygons.length > 0) {
        mapInstance.remove(state.boundaryPolygons);
    }
    state.boundaryPolygons = [];
}

function clearRegionBoundaryPolygons() {
    if (mapInstance && state.regionBoundaryPolygons.length > 0) {
        mapInstance.remove(state.regionBoundaryPolygons);
    }
    state.regionBoundaryPolygons = [];
}

async function renderRegionLabels(boundaries) {
    clearRegionLabelOverlays();

    const ready = await ensureMapReady();
    if (!ready || !mapInstance || !window.AMap?.Text) {
        return;
    }

    const overlays = [];
    boundaries
        .filter((boundary) => {
            const longitude = Number(boundary?.center?.longitude);
            const latitude = Number(boundary?.center?.latitude);
            return Number.isFinite(longitude) && Number.isFinite(latitude);
        })
        .forEach((boundary) => {
            const level = normalizeRegionLevel(boundary.regionLevel);
            if (!level) {
                return;
            }

            const label = new window.AMap.Text({
                text: boundary.regionName || "",
                position: [Number(boundary.center.longitude), Number(boundary.center.latitude)],
                anchor: "center",
                bubble: true,
                zIndex: level === "province" ? 8 : 10,
                zooms: regionLabelZooms(level),
                style: createRegionLabelStyle(level, boundary.regionId === state.currentTownId)
            });

            label.__regionId = boundary.regionId;
            label.__regionLevel = level;
            label.on("click", () => {
                if (boundary.regionLevel === "township") {
                    void loadTownDetail(boundary.regionId, true);
                }
            });
            overlays.push(label);
        });

    state.regionLabelOverlays = overlays;
    mapInstance.add(overlays);
    syncRegionLabelVisibility();
    syncRegionLabelStyles();
}

function clearRegionLabelOverlays() {
    if (mapInstance && state.regionLabelOverlays.length > 0) {
        mapInstance.remove(state.regionLabelOverlays);
    }
    state.regionLabelOverlays = [];
}

function syncRegionLabelVisibility() {
    state.regionLabelOverlays.forEach((label) => {
        const visible = isRegionLevelVisible(label.__regionLevel) && isRegionLevelAllowedAtZoom(label.__regionLevel);
        if (visible) {
            label.show?.();
        } else {
            label.hide?.();
        }
    });
}

function syncRegionLabelStyles() {
    state.regionLabelOverlays.forEach((label) => {
        label.setStyle?.(createRegionLabelStyle(label.__regionLevel, label.__regionId === state.currentTownId));
    });
}

function clearMapMarkers() {
    if (mapInstance && state.markerInstances.length > 0) {
        mapInstance.remove(state.markerInstances);
    }
    state.markerInstances = [];
    state.markerIndex.clear();
    state.activeMarkerKey = "";
}

function createMapMarker(marker) {
    const markerKey = `${marker.type}-${marker.id}`;
    const instance = new window.AMap.Marker({
        position: [Number(marker.longitude), Number(marker.latitude)],
        title: marker.name,
        anchor: "bottom-center",
        content: renderMapMarkerContent(marker, false),
        offset: new window.AMap.Pixel(0, 0),
        zIndex: marker.type === "event" ? 32 : 30
    });
    instance.__resourceMarker = marker;
    instance.__resourceKey = markerKey;

    const infoWindow = new window.AMap.InfoWindow({
        content: renderInfoWindowContent(marker),
        offset: new window.AMap.Pixel(0, -34)
    });

    instance.on("click", () => {
        if (state.activeInfoWindow) {
            state.activeInfoWindow.close();
        }
        setActiveMapMarker(markerKey);
        infoWindow.open(mapInstance, instance.getPosition());
        state.activeInfoWindow = infoWindow;
    });

    return instance;
}

function focusMarkerOnMap(type, id) {
    const marker = state.markerIndex.get(`${type}-${id}`);
    if (!marker || !mapInstance) {
        return;
    }
    setDrawerOpen(false);
    setActiveMapMarker(`${type}-${id}`);
    mapInstance.setCenter(marker.getPosition());
    mapInstance.setZoom(14);
    marker.emit("click", { target: marker });
}

function setActiveMapMarker(markerKey) {
    state.activeMarkerKey = markerKey;
    state.markerInstances.forEach((instance) => {
        const marker = instance.__resourceMarker;
        if (!marker) {
            return;
        }
        const isActive = instance.__resourceKey === markerKey;
        instance.setzIndex?.(isActive ? 42 : (marker.type === "event" ? 32 : 30));
        instance.setContent?.(renderMapMarkerContent(marker, isActive));
    });
    syncMarkerCardSelection(markerKey);
}

function syncMarkerCardSelection(markerKey) {
    document.querySelectorAll(".resource-card[data-marker-key]").forEach((card) => {
        card.classList.toggle("is-active", card.dataset.markerKey === markerKey);
    });
}

function renderMapMarkerContent(marker, isActive) {
    if (marker?.type === "user") {
        return `
            <div class="user-location-pin${isActive ? " is-active" : ""}" aria-label="我的位置">
                <span class="user-location-pin__pulse"></span>
                <span class="user-location-pin__ring"></span>
                <span class="user-location-pin__dot"></span>
            </div>
        `;
    }

    const theme = markerTypeTheme(marker.type);
    const activeClass = isActive ? " is-active" : "";
    return `
        <div class="map-resource-marker map-resource-marker--${escapeHtml(theme.key)}${activeClass}">
            <span class="map-resource-marker__halo"></span>
            <span class="map-resource-marker__glyph">${escapeHtml(theme.glyph)}</span>
            <span class="map-resource-marker__label">${escapeHtml(marker.name || "未命名资源")}</span>
        </div>
    `;
}

function renderInfoWindowContent(marker) {
    const theme = markerTypeTheme(marker.type);
    return `
        <article class="map-info-card map-info-card--${escapeHtml(theme.key)}">
            <div class="map-info-card__topline">
                <span class="map-info-card__badge">${escapeHtml(typeLabel(marker.type))}</span>
                ${marker.relationHint ? `<span class="map-info-card__hint">${escapeHtml(marker.relationHint)}</span>` : ""}
            </div>
            <h3>${escapeHtml(marker.name || "未命名资源")}</h3>
            <p>${escapeHtml(marker.summary || "暂无简介")}</p>
            ${marker.address ? `<div class="map-info-card__address">${escapeHtml(marker.address)}</div>` : ""}
        </article>
    `;
}

async function requestTownExplain() {
    if (state.currentSchoolDetail) {
        await requestSchoolExplain();
        return;
    }
    if (!state.currentTownDetail) {
        return;
    }
    elements.qaStatus.textContent = "生成中";
    await invokeQaApi("/qa/town/explain", {
        regionId: state.currentTownDetail.regionId,
        regionName: state.currentTownDetail.regionName,
        markers: state.currentTownDetail.markers || [],
        heroIds: (state.currentTownDetail.heroes || []).map((item) => item.heroId),
        eventIds: (state.currentTownDetail.events || []).map((item) => item.eventId),
        storyIds: (state.currentTownDetail.stories || []).map((item) => item.storyId)
    }, "当前问答服务不可用，已展示本地讲解兜底文案。");
}

async function askTownQuestion() {
    if (state.currentSchoolDetail) {
        await askSchoolQuestion();
        return;
    }
    const question = elements.qaQuestionInput.value.trim();
    if (!question || !state.currentTownDetail) {
        return;
    }

    elements.qaStatus.textContent = "提问中";
    await invokeQaApi("/qa/town/ask", {
        regionId: state.currentTownDetail.regionId,
        regionName: state.currentTownDetail.regionName,
        question,
        markers: state.currentTownDetail.markers || [],
        heroIds: (state.currentTownDetail.heroes || []).map((item) => item.heroId),
        eventIds: (state.currentTownDetail.events || []).map((item) => item.eventId),
        storyIds: (state.currentTownDetail.stories || []).map((item) => item.storyId)
    }, "当前问答服务不可用，已展示本地问答兜底文案。");
}

async function requestSchoolExplain() {
    if (!state.currentSchoolDetail) {
        return;
    }
    elements.qaStatus.textContent = "生成中";
    await invokeQaApi("/qa/school/explain", buildSchoolQaPayload(), "当前问答服务不可用，已展示本地学校讲解兜底文案。");
}

async function askSchoolQuestion() {
    const question = elements.qaQuestionInput.value.trim();
    if (!question || !state.currentSchoolDetail) {
        return;
    }

    elements.qaStatus.textContent = "提问中";
    await invokeQaApi("/qa/school/ask", {
        ...buildSchoolQaPayload(),
        question
    }, "当前问答服务不可用，已展示本地学校问答兜底文案。");
}

function buildSchoolQaPayload() {
    const detail = state.currentSchoolDetail || {};
    return {
        school: detail.school || {},
        resources: (detail.resources || []).map((item) => ({
            relationType: item.relationType,
            distanceMeters: item.distanceMeters,
            recommendedTravelMode: item.recommendedTravelMode,
            reachabilityLevel: item.reachabilityLevel,
            educationThemeSummary: item.educationThemeSummary,
            resource: item.resource
        })),
        activityPlans: detail.activityPlans || []
    };
}

async function invokeQaApi(path, payload, fallbackStatus) {
    const qaBase = state.appConfig.qaServiceBaseUrl;
    if (!qaBase) {
        renderFallbackQa(payload.question);
        elements.qaStatus.textContent = "本地兜底";
        return;
    }

    try {
        const response = await fetch(`${qaBase}${path}`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json"
            },
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            throw new Error("qa request failed");
        }
        const json = await response.json();
        renderQaResponse(json);
        elements.qaStatus.textContent = "已完成";
    } catch (error) {
        renderFallbackQa(payload.question);
        elements.qaStatus.textContent = fallbackStatus;
    }
}

function renderQaResponse(response) {
    const answer = response?.answer || "问答服务未返回答案。";
    const relatedResources = Array.isArray(response?.relatedResources) ? response.relatedResources : [];
    const followUpQuestions = Array.isArray(response?.followUpQuestions) ? response.followUpQuestions : [];
    const citations = Array.isArray(response?.citations) ? response.citations : [];

    elements.qaAnswer.innerHTML = `
        <p>${escapeHtml(answer).replaceAll("\n", "<br>")}</p>
        ${relatedResources.length ? `<p><strong>关联资源：</strong>${escapeHtml(relatedResources.join("、"))}</p>` : ""}
    `;

    elements.qaCitations.innerHTML = citations.length
        ? citations.map((citation) => `<article><p>${escapeHtml(citation)}</p></article>`).join("")
        : "";

    if (followUpQuestions.length) {
        renderQuestionList(followUpQuestions);
        elements.questionCount.textContent = String(followUpQuestions.length);
    }
}

function renderFallbackQa(question) {
    if (state.currentSchoolDetail) {
        renderSchoolFallbackQa(question);
        return;
    }
    const townName = state.currentTownDetail?.regionName || "当前地区";
    const markers = state.currentTownDetail?.markers?.length || 0;
    const heroes = state.currentTownDetail?.heroes?.length || 0;
    const stories = state.currentTownDetail?.stories?.length || 0;

    const answer = question
        ? `${townName}当前可读取到 ${markers} 个地图资源点、${heroes} 位相关人物和 ${stories} 条故事。由于独立问答服务暂未连通，建议先围绕遗址、事件和人物关系做讲解。`
        : `${townName}是当前地图聚焦地区。你可以点击地图切换地区，并继续围绕事件、遗址和人物做讲解。`;

    elements.qaAnswer.innerHTML = `<p>${escapeHtml(answer)}</p>`;
    elements.qaCitations.innerHTML = `<article><p>当前结果来自本地前端兜底，不代表最终 LLM 输出。</p></article>`;
}

function renderSchoolFallbackQa(question) {
    const detail = state.currentSchoolDetail || {};
    const schoolName = detail.school?.schoolName || "当前学校";
    const resources = detail.resources?.length || 0;
    const plans = detail.activityPlans?.length || 0;

    const answer = question
        ? `围绕“${question}”，${schoolName}当前可读取到 ${resources} 个周边思政资源和 ${plans} 条教学活动建议。建议先选近距离、可达性高的资源，再把资源转化为课堂导入、现场观察和实践反思三个环节。`
        : `${schoolName}当前已形成学校周边资源视图，可以围绕本地文化、公益实践、劳动教育和红色记忆组织讲解。`;

    elements.qaAnswer.innerHTML = `<p>${escapeHtml(answer)}</p>`;
    elements.qaCitations.innerHTML = `<article><p>当前结果来自本地前端兜底，不代表最终 LLM 输出。</p></article>`;
}

function renderCollection(container, items, renderer) {
    if (!container) {
        return;
    }
    if (!Array.isArray(items) || items.length === 0) {
        container.innerHTML = "";
        container.appendChild(elements.emptyStateTemplate.content.cloneNode(true));
        return;
    }
    container.innerHTML = items.map(renderer).join("");
}

function renderEventCard(event) {
    return `
        <article>
            <h4>${escapeHtml(event.eventName || "未命名事件")}</h4>
            <p>${escapeHtml(event.summary || "暂无事件摘要")}</p>
            ${event.eventTimeText ? `<div class="resource-meta"><span>${escapeHtml(event.eventTimeText)}</span></div>` : ""}
        </article>
    `;
}

function renderMarkerCard(marker) {
    const markerKey = `${marker.type}-${marker.id}`;
    return `
        <article class="resource-card resource-card--${escapeHtml(markerTypeTheme(marker.type).key)}" data-marker-key="${escapeHtml(markerKey)}">
            <h3><span class="resource-card__dot"></span>${escapeHtml(marker.name || "未命名资源")}</h3>
            <p>${escapeHtml(marker.summary || "暂无简介")}</p>
            <div class="resource-meta">
                <span>${escapeHtml(typeLabel(marker.type))}</span>
                ${marker.address ? `<span>${escapeHtml(marker.address)}</span>` : ""}
                ${marker.relationHint ? `<span>${escapeHtml(marker.relationHint)}</span>` : ""}
            </div>
            <button class="ghost-button full-width marker-focus-button" type="button" data-marker-type="${escapeHtml(marker.type)}" data-marker-id="${escapeHtml(marker.id)}">在地图中定位</button>
        </article>
    `;
}

function renderSchoolCard(school) {
    const markerKey = `school-${school.schoolId}`;
    return `
        <article class="resource-card resource-card--school" data-marker-key="${escapeHtml(markerKey)}">
            <h3><span class="resource-card__dot"></span>${escapeHtml(school.schoolName || "未命名学校")}</h3>
            <p>${escapeHtml(school.address || "暂无学校地址")}</p>
            <div class="resource-meta">
                ${school.schoolLevel ? `<span>${escapeHtml(schoolLevelLabel(school.schoolLevel))}</span>` : ""}
                ${school.ruralSchool ? "<span>乡村学校</span>" : ""}
                ${school.distanceKm != null ? `<span>距你约 ${escapeHtml(school.distanceKm)} 公里</span>` : ""}
            </div>
            <button class="ghost-button full-width school-detail-button" type="button" data-school-id="${escapeHtml(school.schoolId)}">查看学校资源</button>
        </article>
    `;
}

function renderResourceCard(item) {
    const resource = item.resource || {};
    const markerType = `resource_${resource.resourceCategory || "other"}`;
    const markerKey = `${markerType}-${resource.resourceId}`;
    return `
        <article class="resource-card resource-card--${escapeHtml(markerTypeTheme(markerType).key)}" data-marker-key="${escapeHtml(markerKey)}">
            <h3><span class="resource-card__dot"></span>${escapeHtml(resource.resourceName || "未命名资源")}</h3>
            <p>${escapeHtml(resource.intro || resource.educationValue || "暂无资源简介")}</p>
            <div class="resource-meta">
                <span>${escapeHtml(categoryLabel(resource.resourceCategory))}</span>
                ${item.educationThemeSummary ? `<span>${escapeHtml(item.educationThemeSummary)}</span>` : ""}
                ${distanceText(item.distanceMeters) ? `<span>${escapeHtml(distanceText(item.distanceMeters))}</span>` : ""}
                ${item.recommendedTravelMode ? `<span>${escapeHtml(travelModeLabel(item.recommendedTravelMode))}</span>` : ""}
            </div>
            <button class="ghost-button full-width marker-focus-button" type="button" data-marker-type="${escapeHtml(markerType)}" data-marker-id="${escapeHtml(resource.resourceId)}">在地图中定位</button>
        </article>
    `;
}

function renderActivityPlanCard(plan) {
    return `
        <article>
            <h3>${escapeHtml(plan.theme || "未命名教学活动")}</h3>
            <p>${escapeHtml(plan.objectiveText || plan.activityContent || "暂无活动说明")}</p>
            <div class="resource-meta">
                ${plan.activityType ? `<span>${escapeHtml(activityTypeLabel(plan.activityType))}</span>` : ""}
                ${plan.suitableGrade ? `<span>${escapeHtml(plan.suitableGrade)}</span>` : ""}
                ${plan.durationMinutes ? `<span>${escapeHtml(plan.durationMinutes)} 分钟</span>` : ""}
            </div>
        </article>
    `;
}

function renderThemeCard(theme) {
    return `
        <article>
            <h3>${escapeHtml(theme.title || "资源主题")}</h3>
            <p>${escapeHtml(theme.summary || "可结合学校周边真实资源开展教学。")}</p>
            ${(theme.meta || []).length ? `<div class="resource-meta">${theme.meta.map((item) => `<span>${escapeHtml(item)}</span>`).join("")}</div>` : ""}
        </article>
    `;
}

function renderHeroCard(hero) {
    return `
        <article>
            <h3>${escapeHtml(hero.heroName || "未命名人物")}</h3>
            <p>${escapeHtml(hero.profileSummary || hero.mainDeeds || "暂无人物摘要")}</p>
            <div class="resource-meta">
                ${hero.nativePlaceText ? `<span>${escapeHtml(hero.nativePlaceText)}</span>` : ""}
                ${(hero.relatedResourceNames || []).length ? `<span>${escapeHtml(hero.relatedResourceNames.join("、"))}</span>` : ""}
            </div>
        </article>
    `;
}

function renderStoryCard(story) {
    return `
        <article>
            <h3>${escapeHtml(story.storyTitle || "未命名故事")}</h3>
            <p>${escapeHtml(story.summary || "暂无故事摘要")}</p>
            <div class="resource-meta">
                ${story.ageGroup ? `<span>${escapeHtml(story.ageGroup)}</span>` : ""}
                ${(story.relatedEntityNames || []).length ? `<span>${escapeHtml(story.relatedEntityNames.join("、"))}</span>` : ""}
            </div>
        </article>
    `;
}

function renderQuestionList(questions) {
    if (!elements.questionList) {
        return;
    }
    if (!Array.isArray(questions) || questions.length === 0) {
        elements.questionList.innerHTML = "";
        elements.questionList.appendChild(elements.emptyStateTemplate.content.cloneNode(true));
        return;
    }

    elements.questionList.innerHTML = questions.map((question) => `
        <button class="question-chip" type="button" data-question="${escapeHtml(question)}">${escapeHtml(question)}</button>
    `).join("");

    elements.questionList.querySelectorAll(".question-chip").forEach((button) => {
        button.addEventListener("click", () => {
            elements.qaQuestionInput.value = button.dataset.question || "";
        });
    });
}

function countResourceCategories(resources) {
    const categories = new Set();
    resources.forEach((item) => {
        if (item?.resource?.resourceCategory) {
            categories.add(item.resource.resourceCategory);
        }
    });
    return categories.size;
}

function distanceText(distanceMeters) {
    if (!Number.isFinite(Number(distanceMeters))) {
        return "";
    }
    const distance = Number(distanceMeters);
    if (distance >= 1000) {
        return `约 ${(distance / 1000).toFixed(1)} 公里`;
    }
    return `约 ${Math.round(distance)} 米`;
}

function categoryLabel(category) {
    switch (String(category || "")) {
        case "red_culture":
            return "红色文化";
        case "intangible_culture":
            return "非遗文化";
        case "traditional_culture":
            return "传统文化";
        case "local_history":
            return "地方历史";
        case "public_culture":
            return "公共文化";
        case "labor_education":
            return "劳动教育";
        case "public_welfare":
            return "公益实践";
        case "ecological_civilization":
            return "生态文明";
        case "patriotism_base":
            return "爱国主义基地";
        case "social_practice":
            return "社会实践";
        default:
            return "思政资源";
    }
}

function schoolLevelLabel(level) {
    switch (String(level || "")) {
        case "kindergarten":
            return "幼儿园";
        case "primary":
            return "小学";
        case "junior":
            return "初中";
        case "senior":
            return "高中";
        case "nine_year":
            return "九年一贯制";
        case "twelve_year":
            return "十二年一贯制";
        case "vocational":
            return "职业学校";
        case "special":
            return "特殊教育";
        default:
            return "学校";
    }
}

function travelModeLabel(mode) {
    switch (String(mode || "")) {
        case "walk":
            return "步行";
        case "bike":
            return "骑行";
        case "bus":
            return "公交";
        case "drive":
            return "驾车";
        case "mixed":
            return "组合出行";
        default:
            return "出行方式待核验";
    }
}

function activityTypeLabel(type) {
    switch (String(type || "")) {
        case "classroom":
            return "课堂教学";
        case "field_trip":
            return "实地研学";
        case "volunteer_service":
            return "志愿服务";
        case "research_study":
            return "研究性学习";
        case "labor_practice":
            return "劳动实践";
        case "club_activity":
            return "社团活动";
        case "school_based_course":
            return "校本课程";
        default:
            return "教学活动";
    }
}

function setDrawerOpen(open) {
    state.drawerOpen = open;
    elements.mapStage?.classList.toggle("drawer-open", open);
    elements.regionDrawer?.setAttribute("aria-hidden", open ? "false" : "true");
}

function typeLabel(type) {
    switch (type) {
        case "school":
            return "乡村学校";
        case "site":
            return "革命遗址";
        case "memorial":
            return "纪念馆";
        case "event":
            return "历史事件";
        case "user":
            return "我的位置";
        default:
            if (String(type || "").startsWith("resource_")) {
                return categoryLabel(String(type).replace(/^resource_/, ""));
            }
            return "资源";
    }
}

function markerTypeTheme(type) {
    if (String(type || "").startsWith("resource_")) {
        return resourceMarkerTheme(String(type).replace(/^resource_/, ""));
    }

    switch (type) {
        case "school":
            return { key: "school", glyph: "校" };
        case "site":
            return { key: "site", glyph: "址" };
        case "memorial":
            return { key: "memorial", glyph: "馆" };
        case "event":
            return { key: "event", glyph: "事" };
        case "user":
            return { key: "user", glyph: "我" };
        default:
            return { key: "resource", glyph: "源" };
    }
}

function resourceMarkerTheme(category) {
    switch (category) {
        case "red_culture":
            return { key: "red", glyph: "红" };
        case "intangible_culture":
        case "traditional_culture":
            return { key: "culture", glyph: "文" };
        case "public_welfare":
            return { key: "practice", glyph: "善" };
        case "labor_education":
            return { key: "labor", glyph: "劳" };
        case "patriotism_base":
            return { key: "patriotism", glyph: "国" };
        case "ecological_civilization":
            return { key: "eco", glyph: "绿" };
        case "social_practice":
            return { key: "practice", glyph: "行" };
        default:
            return { key: "resource", glyph: "源" };
    }
}

function geoJsonToPathList(geoJson) {
    if (!geoJson || !geoJson.type) {
        return [];
    }

    if (geoJson.type === "Polygon") {
        return [geoJson.coordinates[0].map(([lng, lat]) => [lng, lat])];
    }

    if (geoJson.type === "MultiPolygon") {
        return geoJson.coordinates.map((polygon) => polygon[0].map(([lng, lat]) => [lng, lat]));
    }

    return [];
}

async function requestJson(url, options = {}) {
    const response = await fetch(url, {
        headers: {
            Accept: "application/json",
            ...(options.method === "POST" ? { "Content-Type": "application/json" } : {})
        },
        ...options
    });

    if (!response.ok) {
        throw new Error(`request failed: ${response.status}`);
    }

    const json = await response.json();
    if (json?.code !== 200) {
        throw new Error(json?.message || "api error");
    }
    return json.data;
}

function updateMapStatus(message) {
    if (elements.mapStatus) {
        elements.mapStatus.textContent = message;
    }
}

function syncLayerToggleButtons() {
    elements.toggleBasemapDetail?.classList.toggle("is-active", state.basemapDetailed);
    if (elements.toggleBasemapDetail) {
        elements.toggleBasemapDetail.dataset.mode = state.basemapDetailed ? "detail-on" : "detail-off";
        const profile = getBasemapProfile();
        elements.toggleBasemapDetail.textContent = state.basemapDetailed
            ? `精细底图 ${profile.label}`
            : "精细底图";
    }
    elements.toggleProvinceLayer?.classList.toggle("is-active", state.layerVisibility.province);
    elements.toggleCityLayer?.classList.toggle("is-active", state.layerVisibility.city);
    elements.toggleTownshipLayer?.classList.toggle("is-active", state.layerVisibility.township);
}

function applyBasemapDetailMode() {
    if (!mapInstance) {
        return;
    }

    const profile = getBasemapProfile();

    if (typeof mapInstance.setFeatures === "function") {
        mapInstance.setFeatures(profile.features);
    }

    if (typeof mapInstance.setLabelRejectMask === "function") {
        mapInstance.setLabelRejectMask(!profile.showLabel);
    }

    if (typeof mapInstance.setStatus === "function") {
        mapInstance.setStatus({ showLabel: profile.showLabel });
    }
}

function refreshOverlayVisualState() {
    if (state.currentTownId != null) {
        highlightActiveRegion(state.currentTownId);
    }

    const profile = getBasemapProfile();
    state.boundaryPolygons.forEach((polygon) => {
        polygon.setOptions({
            strokeWeight: profile.activeTownStrokeWeight + 0.3,
            fillOpacity: Math.min(profile.activeTownFillOpacity + 0.005, 0.08)
        });
    });

    syncRegionPolygonVisibility();
}

function createAdministrativeLayerStyles() {
    const profile = getBasemapProfile();
    return {
        fill: () => state.layerVisibility.province ? `rgba(77, 162, 255, ${profile.adminFillOpacity})` : "rgba(0,0,0,0)",
        "province-stroke": state.layerVisibility.province ? `rgba(56, 132, 255, ${profile.provinceStrokeOpacity})` : "rgba(0,0,0,0)",
        "city-stroke": state.layerVisibility.city ? `rgba(67, 129, 201, ${profile.cityStrokeOpacity})` : "rgba(0,0,0,0)",
        "county-stroke": state.layerVisibility.township ? `rgba(103, 137, 173, ${profile.countyStrokeOpacity})` : "rgba(0,0,0,0)"
    };
}

function createTownshipPolygonStyle(isActive) {
    const profile = getBasemapProfile();
    if (isActive) {
        return {
            strokeColor: "#2d86ff",
            strokeWeight: profile.activeTownStrokeWeight,
            fillColor: "#2d86ff",
            fillOpacity: profile.activeTownFillOpacity
        };
    }

    return {
        strokeColor: `rgba(111, 154, 201, ${profile.inactiveTownStrokeOpacity})`,
        strokeWeight: profile.inactiveTownStrokeWeight,
        fillColor: "#9bc4ff",
        fillOpacity: profile.inactiveTownFillOpacity
    };
}

function syncAdministrativeLayerStyle() {
    if (!state.hebeiAdministrativeLayer) {
        return;
    }
    state.hebeiAdministrativeLayer.setStyles(createAdministrativeLayerStyles());
    syncRegionLabelVisibility();
}

function normalizeRegionLevel(regionLevel) {
    const normalized = String(regionLevel || "").toLowerCase();
    if (normalized === "province" || normalized === "city" || normalized === "township") {
        return normalized;
    }
    return "";
}

function isRegionLevelVisible(regionLevel) {
    if (regionLevel === "province") {
        return state.layerVisibility.province;
    }
    if (regionLevel === "city") {
        return state.layerVisibility.city;
    }
    if (regionLevel === "township") {
        return state.layerVisibility.township;
    }
    return false;
}

function regionLabelZooms(regionLevel) {
    const profile = getBasemapProfile();
    if (regionLevel === "province") {
        return [profile.provinceLabelMinZoom, 20];
    }
    if (regionLevel === "city") {
        return [profile.cityLabelMinZoom, 20];
    }
    return [profile.townshipLabelMinZoom, 20];
}

function createRegionLabelStyle(regionLevel, isActive) {
    const profile = getBasemapProfile();
    const baseStyle = {
        padding: "2px 8px",
        border: isActive ? "1px solid rgba(45, 134, 255, 0.45)" : "1px solid transparent",
        "border-radius": "999px",
        "background-color": isActive ? `rgba(255, 255, 255, ${profile.activeLabelBackgroundOpacity})` : `rgba(255, 255, 255, ${profile.labelBackgroundOpacity})`,
        color: isActive ? "#176dff" : "#35506f",
        "font-family": "\"Microsoft YaHei\", \"PingFang SC\", sans-serif",
        "white-space": "nowrap",
        "box-shadow": isActive ? "0 8px 18px rgba(45, 134, 255, 0.14)" : "0 4px 12px rgba(48, 79, 118, 0.06)"
    };

    if (regionLevel === "province") {
        return {
            ...baseStyle,
            padding: "4px 10px",
            "font-size": "18px",
            "font-weight": "700",
            color: "#25476d",
            "background-color": `rgba(255, 255, 255, ${profile.provinceLabelBackgroundOpacity})`
        };
    }

    if (regionLevel === "city") {
        return {
            ...baseStyle,
            "font-size": "13px",
            "font-weight": isActive ? "700" : "600"
        };
    }

    return {
        ...baseStyle,
        padding: "1px 6px",
        "font-size": "11px",
        "font-weight": isActive ? "700" : "500",
        "background-color": isActive ? `rgba(255, 255, 255, ${profile.activeTownshipLabelBackgroundOpacity})` : `rgba(255, 255, 255, ${profile.townshipLabelBackgroundOpacity})`
    };
}

function readCurrentZoom() {
    const zoom = Number(mapInstance?.getZoom?.());
    return Number.isFinite(zoom) ? zoom : 8.9;
}

function getBasemapProfile() {
    const zoom = state.currentZoom;
    if (!state.basemapDetailed) {
        return {
            label: "",
            features: ["bg"],
            showLabel: false,
            adminFillOpacity: 0.045,
            provinceStrokeOpacity: 0.62,
            cityStrokeOpacity: 0.34,
            countyStrokeOpacity: 0.18,
            activeTownStrokeWeight: 2.2,
            activeTownFillOpacity: 0.08,
            inactiveTownStrokeOpacity: 0.32,
            inactiveTownStrokeWeight: 0.9,
            inactiveTownFillOpacity: 0.015,
            provinceLabelMinZoom: 6,
            cityLabelMinZoom: 7.2,
            townshipLabelMinZoom: 10.8,
            labelBackgroundOpacity: 0.74,
            activeLabelBackgroundOpacity: 0.94,
            provinceLabelBackgroundOpacity: 0.86,
            townshipLabelBackgroundOpacity: 0.6,
            activeTownshipLabelBackgroundOpacity: 0.96
        };
    }

    if (zoom < 9.2) {
        return {
            label: "概览",
            features: ["bg", "road"],
            showLabel: true,
            adminFillOpacity: 0.03,
            provinceStrokeOpacity: 0.48,
            cityStrokeOpacity: 0.24,
            countyStrokeOpacity: 0.1,
            activeTownStrokeWeight: 1.5,
            activeTownFillOpacity: 0.018,
            inactiveTownStrokeOpacity: 0.14,
            inactiveTownStrokeWeight: 0.6,
            inactiveTownFillOpacity: 0.002,
            provinceLabelMinZoom: 6,
            cityLabelMinZoom: 7.6,
            townshipLabelMinZoom: 11.8,
            labelBackgroundOpacity: 0.66,
            activeLabelBackgroundOpacity: 0.88,
            provinceLabelBackgroundOpacity: 0.8,
            townshipLabelBackgroundOpacity: 0.48,
            activeTownshipLabelBackgroundOpacity: 0.9
        };
    }

    if (zoom < 11.6) {
        return {
            label: "道路",
            features: ["bg", "road", "building"],
            showLabel: true,
            adminFillOpacity: 0.022,
            provinceStrokeOpacity: 0.42,
            cityStrokeOpacity: 0.2,
            countyStrokeOpacity: 0.08,
            activeTownStrokeWeight: 1.7,
            activeTownFillOpacity: 0.024,
            inactiveTownStrokeOpacity: 0.18,
            inactiveTownStrokeWeight: 0.7,
            inactiveTownFillOpacity: 0.004,
            provinceLabelMinZoom: 6,
            cityLabelMinZoom: 7.8,
            townshipLabelMinZoom: 10.9,
            labelBackgroundOpacity: 0.68,
            activeLabelBackgroundOpacity: 0.9,
            provinceLabelBackgroundOpacity: 0.82,
            townshipLabelBackgroundOpacity: 0.52,
            activeTownshipLabelBackgroundOpacity: 0.92
        };
    }

    return {
        label: "POI",
        features: ["bg", "road", "building", "point"],
        showLabel: true,
        adminFillOpacity: 0.014,
        provinceStrokeOpacity: 0.34,
        cityStrokeOpacity: 0.16,
        countyStrokeOpacity: 0.06,
        activeTownStrokeWeight: 1.9,
        activeTownFillOpacity: 0.03,
        inactiveTownStrokeOpacity: 0.22,
        inactiveTownStrokeWeight: 0.8,
        inactiveTownFillOpacity: 0.008,
        provinceLabelMinZoom: 6,
        cityLabelMinZoom: 8,
        townshipLabelMinZoom: 10.3,
        labelBackgroundOpacity: 0.74,
        activeLabelBackgroundOpacity: 0.94,
        provinceLabelBackgroundOpacity: 0.86,
        townshipLabelBackgroundOpacity: 0.6,
        activeTownshipLabelBackgroundOpacity: 0.96
    };
}

function isRegionLevelAllowedAtZoom(regionLevel) {
    const profile = getBasemapProfile();
    const zoom = state.currentZoom;
    if (regionLevel === "province") {
        return zoom >= profile.provinceLabelMinZoom;
    }
    if (regionLevel === "city") {
        return zoom >= profile.cityLabelMinZoom;
    }
    if (regionLevel === "township") {
        return zoom >= profile.townshipLabelMinZoom;
    }
    return false;
}

function loadScript(src) {
    return new Promise((resolve, reject) => {
        const existing = document.querySelector(`script[data-src="${src}"]`);
        if (existing) {
            resolve();
            return;
        }
        const script = document.createElement("script");
        script.src = src;
        script.async = true;
        script.dataset.src = src;
        script.onload = () => resolve();
        script.onerror = () => reject(new Error("script load error"));
        document.head.appendChild(script);
    });
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

document.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) {
        return;
    }
    if (target.classList.contains("marker-focus-button")) {
        const markerType = target.dataset.markerType;
        const markerId = Number(target.dataset.markerId);
        if (markerType && Number.isFinite(markerId)) {
            focusMarkerOnMap(markerType, markerId);
        }
    }
    if (target.classList.contains("school-detail-button")) {
        const schoolId = Number(target.dataset.schoolId);
        if (Number.isFinite(schoolId)) {
            void loadSchoolDetail(schoolId, true, false);
        }
    }
});
