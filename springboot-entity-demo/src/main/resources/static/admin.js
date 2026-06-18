const adminState = {
    activeTab: "schools",
    registrations: [],
    schools: [],
    resources: [],
    selectedSchoolIdForRelations: null,
    selectedSchoolIdForPlans: null
};

const adminElements = {
    globalStatusText: document.querySelector("#globalStatusText"),
    globalStatusHint: document.querySelector("#globalStatusHint"),
    schoolTotalMetric: document.querySelector("#schoolTotalMetric"),
    resourceTotalMetric: document.querySelector("#resourceTotalMetric"),
    planTotalMetric: document.querySelector("#planTotalMetric"),
    refreshDashboardButton: document.querySelector("#refreshDashboardButton"),
    tabButtons: Array.from(document.querySelectorAll(".tab-chip")),
    panels: Array.from(document.querySelectorAll(".workspace-panel")),

    registrationKeywordInput: document.querySelector("#registrationKeywordInput"),
    registrationSearchButton: document.querySelector("#registrationSearchButton"),
    registrationRefreshButton: document.querySelector("#registrationRefreshButton"),
    registrationResetButton: document.querySelector("#registrationResetButton"),
    registrationReviewForm: document.querySelector("#registrationReviewForm"),
    registrationIdInput: document.querySelector("#registrationIdInput"),
    registrationAccountInput: document.querySelector("#registrationAccountInput"),
    registrationSchoolNameInput: document.querySelector("#registrationSchoolNameInput"),
    registrationContactInput: document.querySelector("#registrationContactInput"),
    registrationAddressInput: document.querySelector("#registrationAddressInput"),
    registrationIntroInput: document.querySelector("#registrationIntroInput"),
    registrationReviewerInput: document.querySelector("#registrationReviewerInput"),
    registrationRemarkInput: document.querySelector("#registrationRemarkInput"),
    registrationApproveButton: document.querySelector("#registrationApproveButton"),
    registrationRejectButton: document.querySelector("#registrationRejectButton"),
    registrationTableBody: document.querySelector("#registrationTableBody"),
    registrationListCount: document.querySelector("#registrationListCount"),

    schoolForm: document.querySelector("#schoolForm"),
    schoolIdInput: document.querySelector("#schoolIdInput"),
    schoolCodeInput: document.querySelector("#schoolCodeInput"),
    schoolNameInput: document.querySelector("#schoolNameInput"),
    schoolAliasInput: document.querySelector("#schoolAliasInput"),
    schoolTypeInput: document.querySelector("#schoolTypeInput"),
    schoolLevelInput: document.querySelector("#schoolLevelInput"),
    schoolNatureInput: document.querySelector("#schoolNatureInput"),
    schoolCountyRegionIdInput: document.querySelector("#schoolCountyRegionIdInput"),
    schoolTownshipRegionIdInput: document.querySelector("#schoolTownshipRegionIdInput"),
    schoolLongitudeInput: document.querySelector("#schoolLongitudeInput"),
    schoolLatitudeInput: document.querySelector("#schoolLatitudeInput"),
    schoolGeoSourceInput: document.querySelector("#schoolGeoSourceInput"),
    schoolGeoConfidenceInput: document.querySelector("#schoolGeoConfidenceInput"),
    schoolAddressInput: document.querySelector("#schoolAddressInput"),
    schoolIntroInput: document.querySelector("#schoolIntroInput"),
    schoolRuralInput: document.querySelector("#schoolRuralInput"),
    schoolTeachingPointInput: document.querySelector("#schoolTeachingPointInput"),
    schoolGeoVerifiedInput: document.querySelector("#schoolGeoVerifiedInput"),
    schoolKeywordInput: document.querySelector("#schoolKeywordInput"),
    schoolSearchButton: document.querySelector("#schoolSearchButton"),
    schoolRefreshButton: document.querySelector("#schoolRefreshButton"),
    schoolResetButton: document.querySelector("#schoolResetButton"),
    schoolTableBody: document.querySelector("#schoolTableBody"),
    schoolListCount: document.querySelector("#schoolListCount"),

    resourceForm: document.querySelector("#resourceForm"),
    resourceIdInput: document.querySelector("#resourceIdInput"),
    resourceCodeInput: document.querySelector("#resourceCodeInput"),
    resourceNameInput: document.querySelector("#resourceNameInput"),
    resourceAliasInput: document.querySelector("#resourceAliasInput"),
    resourceCategoryInput: document.querySelector("#resourceCategoryInput"),
    resourceSubcategoryInput: document.querySelector("#resourceSubcategoryInput"),
    resourceOrgInput: document.querySelector("#resourceOrgInput"),
    resourceLongitudeInput: document.querySelector("#resourceLongitudeInput"),
    resourceLatitudeInput: document.querySelector("#resourceLatitudeInput"),
    resourceCountyRegionIdInput: document.querySelector("#resourceCountyRegionIdInput"),
    resourceTownshipRegionIdInput: document.querySelector("#resourceTownshipRegionIdInput"),
    resourceContactPhoneInput: document.querySelector("#resourceContactPhoneInput"),
    resourceVisitMinutesInput: document.querySelector("#resourceVisitMinutesInput"),
    resourceAddressInput: document.querySelector("#resourceAddressInput"),
    resourceIntroInput: document.querySelector("#resourceIntroInput"),
    resourceEducationValueInput: document.querySelector("#resourceEducationValueInput"),
    resourceActivitySuggestionInput: document.querySelector("#resourceActivitySuggestionInput"),
    resourceReservationRequiredInput: document.querySelector("#resourceReservationRequiredInput"),
    resourceKeywordInput: document.querySelector("#resourceKeywordInput"),
    resourceSearchButton: document.querySelector("#resourceSearchButton"),
    resourceRefreshButton: document.querySelector("#resourceRefreshButton"),
    resourceResetButton: document.querySelector("#resourceResetButton"),
    resourceTableBody: document.querySelector("#resourceTableBody"),
    resourceListCount: document.querySelector("#resourceListCount"),

    relationForm: document.querySelector("#relationForm"),
    relationIdInput: document.querySelector("#relationIdInput"),
    relationSchoolSelect: document.querySelector("#relationSchoolSelect"),
    relationResourceSelect: document.querySelector("#relationResourceSelect"),
    relationTypeInput: document.querySelector("#relationTypeInput"),
    relationTravelModeInput: document.querySelector("#relationTravelModeInput"),
    relationDistanceInput: document.querySelector("#relationDistanceInput"),
    relationDurationInput: document.querySelector("#relationDurationInput"),
    relationReachabilityInput: document.querySelector("#relationReachabilityInput"),
    relationPriorityInput: document.querySelector("#relationPriorityInput"),
    relationThemeSummaryInput: document.querySelector("#relationThemeSummaryInput"),
    relationFilterSchoolSelect: document.querySelector("#relationFilterSchoolSelect"),
    relationRefreshButton: document.querySelector("#relationRefreshButton"),
    relationResetButton: document.querySelector("#relationResetButton"),
    relationTableBody: document.querySelector("#relationTableBody"),
    relationListCount: document.querySelector("#relationListCount"),

    planForm: document.querySelector("#planForm"),
    planIdInput: document.querySelector("#planIdInput"),
    planCodeInput: document.querySelector("#planCodeInput"),
    planSchoolSelect: document.querySelector("#planSchoolSelect"),
    planResourceSelect: document.querySelector("#planResourceSelect"),
    planActivityTypeInput: document.querySelector("#planActivityTypeInput"),
    planThemeInput: document.querySelector("#planThemeInput"),
    planSuitableGradeInput: document.querySelector("#planSuitableGradeInput"),
    planDurationInput: document.querySelector("#planDurationInput"),
    planObjectiveInput: document.querySelector("#planObjectiveInput"),
    planContentInput: document.querySelector("#planContentInput"),
    planPreparationInput: document.querySelector("#planPreparationInput"),
    planSafetyInput: document.querySelector("#planSafetyInput"),
    planOutcomeInput: document.querySelector("#planOutcomeInput"),
    planFilterSchoolSelect: document.querySelector("#planFilterSchoolSelect"),
    planRefreshButton: document.querySelector("#planRefreshButton"),
    planResetButton: document.querySelector("#planResetButton"),
    planTableBody: document.querySelector("#planTableBody"),
    planListCount: document.querySelector("#planListCount")
};

document.addEventListener("DOMContentLoaded", () => {
    bindAdminEvents();
    void bootstrapAdmin();
});

function bindAdminEvents() {
    adminElements.refreshDashboardButton?.addEventListener("click", () => {
        void bootstrapAdmin();
    });

    adminElements.tabButtons.forEach(button => {
        button.addEventListener("click", () => setActiveTab(button.dataset.tab || "schools"));
    });

    adminElements.registrationSearchButton?.addEventListener("click", () => void loadRegistrations());
    adminElements.registrationRefreshButton?.addEventListener("click", () => void loadRegistrations());
    adminElements.registrationResetButton?.addEventListener("click", resetRegistrationReviewForm);
    adminElements.registrationApproveButton?.addEventListener("click", () => void runRegistrationReview("approve"));
    adminElements.registrationRejectButton?.addEventListener("click", () => void runRegistrationReview("reject"));

    adminElements.schoolForm?.addEventListener("submit", async event => {
        event.preventDefault();
        await submitSchoolForm();
    });
    adminElements.schoolSearchButton?.addEventListener("click", () => void loadSchools());
    adminElements.schoolRefreshButton?.addEventListener("click", () => void loadSchools());
    adminElements.schoolResetButton?.addEventListener("click", resetSchoolForm);

    adminElements.resourceForm?.addEventListener("submit", async event => {
        event.preventDefault();
        await submitResourceForm();
    });
    adminElements.resourceSearchButton?.addEventListener("click", () => void loadResources());
    adminElements.resourceRefreshButton?.addEventListener("click", () => void loadResources());
    adminElements.resourceResetButton?.addEventListener("click", resetResourceForm);

    adminElements.relationForm?.addEventListener("submit", async event => {
        event.preventDefault();
        await submitRelationForm();
    });
    adminElements.relationRefreshButton?.addEventListener("click", () => void loadRelations());
    adminElements.relationResetButton?.addEventListener("click", resetRelationForm);
    adminElements.relationFilterSchoolSelect?.addEventListener("change", () => {
        adminState.selectedSchoolIdForRelations = parseNullableNumber(adminElements.relationFilterSchoolSelect.value);
        void loadRelations();
    });

    adminElements.planForm?.addEventListener("submit", async event => {
        event.preventDefault();
        await submitPlanForm();
    });
    adminElements.planRefreshButton?.addEventListener("click", () => void loadPlans());
    adminElements.planResetButton?.addEventListener("click", resetPlanForm);
    adminElements.planFilterSchoolSelect?.addEventListener("change", () => {
        adminState.selectedSchoolIdForPlans = parseNullableNumber(adminElements.planFilterSchoolSelect.value);
        void loadPlans();
    });
}

async function bootstrapAdmin() {
    setGlobalStatus("加载中", "正在同步学校、资源、关联和活动方案数据。");
    try {
        await Promise.all([
            loadRegistrations(),
            loadSchools(),
            loadResources(),
            loadPlans()
        ]);
        syncSelectOptions();
        await loadRelations();
        setGlobalStatus("在线", "后台接口联通正常，可以开始录入和维护数据。");
    } catch (error) {
        setGlobalStatus("异常", error.message || "后台接口请求失败。");
    }
}

async function loadRegistrations() {
    const keyword = adminElements.registrationKeywordInput?.value?.trim() || "";
    const result = await requestJson(`/api/admin/registrations?pageNum=1&pageSize=50${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ""}`);
    adminState.registrations = result.records || [];
    renderRegistrationTable(adminState.registrations);
}

function renderRegistrationTable(records) {
    adminElements.registrationListCount.textContent = `${records.length} 条`;
    adminElements.registrationTableBody.innerHTML = "";
    if (!records.length) {
        adminElements.registrationTableBody.innerHTML = `<tr><td colspan="5">暂无注册申请数据。</td></tr>`;
        return;
    }
    records.forEach(record => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${escapeHtml(record.applyAccount || "-")}</td>
            <td>
                <strong>${escapeHtml(record.schoolName || "-")}</strong>
                <div class="status-box">${escapeHtml(record.address || "未填写地址")}</div>
            </td>
            <td>${escapeHtml(record.contactName || record.contactPhone || "-")}</td>
            <td>${renderStatus(record.reviewStatus)}</td>
            <td>
                <div class="table-actions">
                    <button class="action-button" data-action="view">查看</button>
                </div>
            </td>
        `;
        tr.querySelector('[data-action="view"]').addEventListener("click", () => fillRegistrationReviewForm(record));
        adminElements.registrationTableBody.appendChild(tr);
    });
}

function fillRegistrationReviewForm(record) {
    adminElements.registrationIdInput.value = record.registrationId || "";
    adminElements.registrationAccountInput.value = record.applyAccount || "";
    adminElements.registrationSchoolNameInput.value = record.schoolName || "";
    adminElements.registrationContactInput.value = [record.contactName, record.contactPhone].filter(Boolean).join(" / ");
    adminElements.registrationAddressInput.value = record.address || "";
    adminElements.registrationIntroInput.value = record.intro || "";
    adminElements.registrationRemarkInput.value = record.reviewRemark || "";
}

function resetRegistrationReviewForm() {
    adminElements.registrationReviewForm?.reset();
    if (adminElements.registrationIdInput) {
        adminElements.registrationIdInput.value = "";
    }
}

async function runRegistrationReview(action) {
    const registrationId = parseNullableNumber(adminElements.registrationIdInput?.value);
    if (!registrationId) {
        setGlobalStatus("操作失败", "请先选择一条注册申请。");
        return;
    }
    const body = {
        reviewerName: optionalText(adminElements.registrationReviewerInput?.value),
        reviewRemark: optionalText(adminElements.registrationRemarkInput?.value)
    };
    await requestJson(`/api/admin/registrations/${registrationId}/${action}`, {
        method: "POST",
        body
    });
    setGlobalStatus("操作成功", `注册申请已${action === "approve" ? "审核通过" : "驳回"}。`);
    resetRegistrationReviewForm();
    await Promise.all([loadRegistrations(), loadSchools()]);
}

function setActiveTab(tabName) {
    adminState.activeTab = tabName;
    adminElements.tabButtons.forEach(button => {
        button.classList.toggle("is-active", button.dataset.tab === tabName);
    });
    adminElements.panels.forEach(panel => {
        panel.classList.toggle("is-active", panel.dataset.panel === tabName);
    });
}

function setGlobalStatus(title, hint) {
    if (adminElements.globalStatusText) {
        adminElements.globalStatusText.textContent = title;
    }
    if (adminElements.globalStatusHint) {
        adminElements.globalStatusHint.textContent = hint;
    }
}

async function requestJson(url, options = {}) {
    const config = { ...options };
    const headers = { ...(config.headers || {}) };
    if (config.body !== undefined) {
        headers["Content-Type"] = "application/json";
        config.body = JSON.stringify(config.body);
    }
    config.headers = headers;

    const response = await fetch(url, config);
    const data = await response.json();
    if (!response.ok || data.code !== 200) {
        throw new Error(data.message || "请求失败");
    }
    return data.data;
}

async function loadSchools() {
    const keyword = adminElements.schoolKeywordInput?.value?.trim() || "";
    const result = await requestJson(`/api/admin/schools?pageNum=1&pageSize=50${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ""}`);
    adminState.schools = result.records || [];
    renderSchoolTable(adminState.schools);
    if (adminElements.schoolTotalMetric) {
        adminElements.schoolTotalMetric.textContent = String(result.total || 0);
    }
    syncSelectOptions();
}

function renderSchoolTable(records) {
    adminElements.schoolListCount.textContent = `${records.length} 条`;
    adminElements.schoolTableBody.innerHTML = "";
    if (!records.length) {
        adminElements.schoolTableBody.innerHTML = `<tr><td colspan="5">暂无学校数据。</td></tr>`;
        return;
    }

    records.forEach(record => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${escapeHtml(record.schoolCode || "-")}</td>
            <td>
                <strong>${escapeHtml(record.schoolName || "-")}</strong>
                <div class="status-box">${escapeHtml(record.address || "未填写地址")}</div>
            </td>
            <td>${escapeHtml(record.schoolLevel || "-")}</td>
            <td>${renderStatus(record.reviewStatus)}</td>
            <td>
                <div class="table-actions">
                    <button class="action-button" data-action="edit">编辑</button>
                    <button class="action-button" data-action="submit">提交审核</button>
                    <button class="action-button" data-action="approve">通过</button>
                    <button class="action-button" data-action="reject">驳回</button>
                </div>
            </td>
        `;
        tr.querySelector('[data-action="edit"]').addEventListener("click", () => fillSchoolForm(record));
        tr.querySelector('[data-action="submit"]').addEventListener("click", () => void runSchoolAction(record.schoolId, "submit-review"));
        tr.querySelector('[data-action="approve"]').addEventListener("click", () => void runSchoolAction(record.schoolId, "approve"));
        tr.querySelector('[data-action="reject"]').addEventListener("click", () => void runSchoolAction(record.schoolId, "reject"));
        adminElements.schoolTableBody.appendChild(tr);
    });
}

async function submitSchoolForm() {
    const schoolId = parseNullableNumber(adminElements.schoolIdInput.value);
    const body = {
        schoolCode: adminElements.schoolCodeInput.value.trim(),
        schoolName: adminElements.schoolNameInput.value.trim(),
        schoolAlias: optionalText(adminElements.schoolAliasInput.value),
        schoolType: optionalText(adminElements.schoolTypeInput.value),
        schoolLevel: adminElements.schoolLevelInput.value,
        schoolNature: adminElements.schoolNatureInput.value,
        countyRegionId: parseNullableNumber(adminElements.schoolCountyRegionIdInput.value),
        townshipRegionId: parseNullableNumber(adminElements.schoolTownshipRegionIdInput.value),
        longitude: parseNullableNumber(adminElements.schoolLongitudeInput.value),
        latitude: parseNullableNumber(adminElements.schoolLatitudeInput.value),
        geoSourceType: adminElements.schoolGeoSourceInput.value,
        geoConfidence: adminElements.schoolGeoConfidenceInput.value,
        address: optionalText(adminElements.schoolAddressInput.value),
        intro: optionalText(adminElements.schoolIntroInput.value),
        ruralSchool: adminElements.schoolRuralInput.checked,
        teachingPoint: adminElements.schoolTeachingPointInput.checked,
        geoVerified: adminElements.schoolGeoVerifiedInput.checked
    };

    if (!body.schoolCode || !body.schoolName) {
        setGlobalStatus("校验失败", "学校编码和学校名称不能为空。");
        return;
    }

    if (schoolId) {
        delete body.schoolCode;
        await requestJson(`/api/admin/schools/${schoolId}`, { method: "PUT", body });
        setGlobalStatus("已更新", "学校信息已更新。");
    } else {
        await requestJson("/api/admin/schools", { method: "POST", body });
        setGlobalStatus("已创建", "学校信息已创建。");
    }

    resetSchoolForm();
    await loadSchools();
}

function fillSchoolForm(record) {
    adminElements.schoolIdInput.value = record.schoolId || "";
    adminElements.schoolCodeInput.value = record.schoolCode || "";
    adminElements.schoolCodeInput.disabled = true;
    adminElements.schoolNameInput.value = record.schoolName || "";
    adminElements.schoolAliasInput.value = record.schoolAlias || "";
    adminElements.schoolTypeInput.value = record.schoolType || "";
    adminElements.schoolLevelInput.value = record.schoolLevel || "primary";
    adminElements.schoolNatureInput.value = record.schoolNature || "public";
    adminElements.schoolCountyRegionIdInput.value = record.countyRegionId || "";
    adminElements.schoolTownshipRegionIdInput.value = record.townshipRegionId || "";
    adminElements.schoolLongitudeInput.value = record.longitude || "";
    adminElements.schoolLatitudeInput.value = record.latitude || "";
    adminElements.schoolGeoSourceInput.value = record.geoSourceType || "government_doc";
    adminElements.schoolGeoConfidenceInput.value = record.geoConfidence || "unknown";
    adminElements.schoolAddressInput.value = record.address || "";
    adminElements.schoolIntroInput.value = record.intro || "";
    adminElements.schoolRuralInput.checked = Boolean(record.ruralSchool);
    adminElements.schoolTeachingPointInput.checked = Boolean(record.teachingPoint);
    adminElements.schoolGeoVerifiedInput.checked = Boolean(record.geoVerified);
}

function resetSchoolForm() {
    adminElements.schoolForm.reset();
    adminElements.schoolIdInput.value = "";
    adminElements.schoolCodeInput.disabled = false;
    adminElements.schoolRuralInput.checked = true;
}

async function runSchoolAction(schoolId, action) {
    await requestJson(`/api/admin/schools/${schoolId}/${action}`, { method: "POST", body: {} });
    setGlobalStatus("操作成功", "学校审核状态已更新。");
    await loadSchools();
}

async function loadResources() {
    const keyword = adminElements.resourceKeywordInput?.value?.trim() || "";
    const result = await requestJson(`/api/admin/resources?pageNum=1&pageSize=50${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ""}`);
    adminState.resources = result.records || [];
    renderResourceTable(adminState.resources);
    if (adminElements.resourceTotalMetric) {
        adminElements.resourceTotalMetric.textContent = String(result.total || 0);
    }
    syncSelectOptions();
}

function renderResourceTable(records) {
    adminElements.resourceListCount.textContent = `${records.length} 条`;
    adminElements.resourceTableBody.innerHTML = "";
    if (!records.length) {
        adminElements.resourceTableBody.innerHTML = `<tr><td colspan="5">暂无资源数据。</td></tr>`;
        return;
    }

    records.forEach(record => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${escapeHtml(record.resourceCode || "-")}</td>
            <td>
                <strong>${escapeHtml(record.resourceName || "-")}</strong>
                <div class="status-box">${escapeHtml(record.organizationName || "未填写机构")}</div>
            </td>
            <td>${escapeHtml(record.resourceCategory || "-")}</td>
            <td>${renderStatus(record.reviewStatus)}</td>
            <td>
                <div class="table-actions">
                    <button class="action-button" data-action="edit">编辑</button>
                    <button class="action-button" data-action="submit">提交审核</button>
                    <button class="action-button" data-action="approve">通过</button>
                    <button class="action-button" data-action="reject">驳回</button>
                </div>
            </td>
        `;
        tr.querySelector('[data-action="edit"]').addEventListener("click", () => fillResourceForm(record));
        tr.querySelector('[data-action="submit"]').addEventListener("click", () => void runResourceAction(record.resourceId, "submit-review"));
        tr.querySelector('[data-action="approve"]').addEventListener("click", () => void runResourceAction(record.resourceId, "approve"));
        tr.querySelector('[data-action="reject"]').addEventListener("click", () => void runResourceAction(record.resourceId, "reject"));
        adminElements.resourceTableBody.appendChild(tr);
    });
}

async function submitResourceForm() {
    const resourceId = parseNullableNumber(adminElements.resourceIdInput.value);
    const body = {
        resourceCode: adminElements.resourceCodeInput.value.trim(),
        resourceName: adminElements.resourceNameInput.value.trim(),
        resourceAlias: optionalText(adminElements.resourceAliasInput.value),
        resourceCategory: adminElements.resourceCategoryInput.value,
        resourceSubcategory: optionalText(adminElements.resourceSubcategoryInput.value),
        countyRegionId: parseNullableNumber(adminElements.resourceCountyRegionIdInput.value),
        townshipRegionId: parseNullableNumber(adminElements.resourceTownshipRegionIdInput.value),
        longitude: parseNullableNumber(adminElements.resourceLongitudeInput.value),
        latitude: parseNullableNumber(adminElements.resourceLatitudeInput.value),
        organizationName: optionalText(adminElements.resourceOrgInput.value),
        contactPhone: optionalText(adminElements.resourceContactPhoneInput.value),
        recommendedVisitMinutes: parseNullableNumber(adminElements.resourceVisitMinutesInput.value),
        address: optionalText(adminElements.resourceAddressInput.value),
        intro: optionalText(adminElements.resourceIntroInput.value),
        educationValue: optionalText(adminElements.resourceEducationValueInput.value),
        activitySuggestion: optionalText(adminElements.resourceActivitySuggestionInput.value),
        reservationRequired: adminElements.resourceReservationRequiredInput.checked
    };

    if (!body.resourceCode || !body.resourceName) {
        setGlobalStatus("校验失败", "资源编码和资源名称不能为空。");
        return;
    }

    if (resourceId) {
        delete body.resourceCode;
        await requestJson(`/api/admin/resources/${resourceId}`, { method: "PUT", body });
        setGlobalStatus("已更新", "资源信息已更新。");
    } else {
        await requestJson("/api/admin/resources", { method: "POST", body });
        setGlobalStatus("已创建", "资源信息已创建。");
    }

    resetResourceForm();
    await loadResources();
}

function fillResourceForm(record) {
    adminElements.resourceIdInput.value = record.resourceId || "";
    adminElements.resourceCodeInput.value = record.resourceCode || "";
    adminElements.resourceCodeInput.disabled = true;
    adminElements.resourceNameInput.value = record.resourceName || "";
    adminElements.resourceAliasInput.value = record.resourceAlias || "";
    adminElements.resourceCategoryInput.value = record.resourceCategory || "other";
    adminElements.resourceSubcategoryInput.value = record.resourceSubcategory || "";
    adminElements.resourceOrgInput.value = record.organizationName || "";
    adminElements.resourceLongitudeInput.value = record.longitude || "";
    adminElements.resourceLatitudeInput.value = record.latitude || "";
    adminElements.resourceCountyRegionIdInput.value = record.countyRegionId || "";
    adminElements.resourceTownshipRegionIdInput.value = record.townshipRegionId || "";
    adminElements.resourceContactPhoneInput.value = record.contactPhone || "";
    adminElements.resourceVisitMinutesInput.value = record.recommendedVisitMinutes || "";
    adminElements.resourceAddressInput.value = record.address || "";
    adminElements.resourceIntroInput.value = record.intro || "";
    adminElements.resourceEducationValueInput.value = record.educationValue || "";
    adminElements.resourceActivitySuggestionInput.value = record.activitySuggestion || "";
    adminElements.resourceReservationRequiredInput.checked = Boolean(record.reservationRequired);
}

function resetResourceForm() {
    adminElements.resourceForm.reset();
    adminElements.resourceIdInput.value = "";
    adminElements.resourceCodeInput.disabled = false;
}

async function runResourceAction(resourceId, action) {
    await requestJson(`/api/admin/resources/${resourceId}/${action}`, { method: "POST", body: {} });
    setGlobalStatus("操作成功", "资源审核状态已更新。");
    await loadResources();
}

function syncSelectOptions() {
    fillOptionSelect(adminElements.relationSchoolSelect, adminState.schools, "schoolId", "schoolName");
    fillOptionSelect(adminElements.relationFilterSchoolSelect, adminState.schools, "schoolId", "schoolName", "请选择学校查看关联");
    fillOptionSelect(adminElements.planSchoolSelect, adminState.schools, "schoolId", "schoolName");
    fillOptionSelect(adminElements.planFilterSchoolSelect, adminState.schools, "schoolId", "schoolName", "请选择学校查看方案");
    fillOptionSelect(adminElements.relationResourceSelect, adminState.resources, "resourceId", "resourceName");
    fillOptionSelect(adminElements.planResourceSelect, adminState.resources, "resourceId", "resourceName", "可不关联具体资源");

    if (!adminState.selectedSchoolIdForRelations && adminState.schools[0]) {
        adminState.selectedSchoolIdForRelations = adminState.schools[0].schoolId;
    }
    if (!adminState.selectedSchoolIdForPlans && adminState.schools[0]) {
        adminState.selectedSchoolIdForPlans = adminState.schools[0].schoolId;
    }

    if (adminElements.relationFilterSchoolSelect && adminState.selectedSchoolIdForRelations) {
        adminElements.relationFilterSchoolSelect.value = String(adminState.selectedSchoolIdForRelations);
    }
    if (adminElements.planFilterSchoolSelect && adminState.selectedSchoolIdForPlans) {
        adminElements.planFilterSchoolSelect.value = String(adminState.selectedSchoolIdForPlans);
    }
}

async function loadRelations() {
    syncSelectOptions();
    if (!adminState.selectedSchoolIdForRelations) {
        adminElements.relationTableBody.innerHTML = `<tr><td colspan="5">请先创建学校后再维护关联。</td></tr>`;
        adminElements.relationListCount.textContent = "0 条";
        return;
    }
    const result = await requestJson(`/api/admin/schools/${adminState.selectedSchoolIdForRelations}/resources?pageNum=1&pageSize=50`);
    const records = result.records || [];
    adminElements.relationListCount.textContent = `${records.length} 条`;
    adminElements.relationTableBody.innerHTML = "";
    if (!records.length) {
        adminElements.relationTableBody.innerHTML = `<tr><td colspan="5">该学校暂无周边关联。</td></tr>`;
        return;
    }
    records.forEach(record => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${escapeHtml(record.schoolName || "-")}</td>
            <td>${escapeHtml(record.resourceName || "-")}</td>
            <td>${escapeHtml(record.relationType || "-")}</td>
            <td>${record.distanceMeters ?? "-"} m</td>
            <td>
                <div class="table-actions">
                    <button class="action-button" data-action="edit">编辑</button>
                    <button class="action-button" data-action="delete">删除</button>
                </div>
            </td>
        `;
        tr.querySelector('[data-action="edit"]').addEventListener("click", () => fillRelationForm(record));
        tr.querySelector('[data-action="delete"]').addEventListener("click", () => void deleteRelation(record.relId));
        adminElements.relationTableBody.appendChild(tr);
    });
}

async function submitRelationForm() {
    const relId = parseNullableNumber(adminElements.relationIdInput.value);
    const body = {
        schoolId: parseNullableNumber(adminElements.relationSchoolSelect.value),
        resourceId: parseNullableNumber(adminElements.relationResourceSelect.value),
        relationType: adminElements.relationTypeInput.value,
        recommendedTravelMode: adminElements.relationTravelModeInput.value,
        distanceMeters: parseNullableNumber(adminElements.relationDistanceInput.value),
        estimatedDurationMinutes: parseNullableNumber(adminElements.relationDurationInput.value),
        reachabilityLevel: adminElements.relationReachabilityInput.value,
        priorityLevel: parseNullableNumber(adminElements.relationPriorityInput.value),
        educationThemeSummary: optionalText(adminElements.relationThemeSummaryInput.value)
    };

    if (!body.schoolId || !body.resourceId) {
        setGlobalStatus("校验失败", "学校和资源都需要选择。");
        return;
    }

    if (relId) {
        delete body.schoolId;
        delete body.resourceId;
        await requestJson(`/api/admin/school-resource-rel/${relId}`, { method: "PUT", body });
        setGlobalStatus("已更新", "学校与资源关联已更新。");
    } else {
        await requestJson("/api/admin/school-resource-rel", { method: "POST", body });
        setGlobalStatus("已创建", "学校与资源关联已创建。");
    }

    adminState.selectedSchoolIdForRelations = body.schoolId || adminState.selectedSchoolIdForRelations;
    resetRelationForm();
    await loadRelations();
}

function fillRelationForm(record) {
    adminElements.relationIdInput.value = record.relId || "";
    adminElements.relationSchoolSelect.value = record.schoolId || "";
    adminElements.relationResourceSelect.value = record.resourceId || "";
    adminElements.relationTypeInput.value = record.relationType || "nearby";
    adminElements.relationTravelModeInput.value = record.recommendedTravelMode || "unknown";
    adminElements.relationDistanceInput.value = record.distanceMeters || "";
    adminElements.relationDurationInput.value = record.estimatedDurationMinutes || "";
    adminElements.relationReachabilityInput.value = record.reachabilityLevel || "unknown";
    adminElements.relationPriorityInput.value = record.priorityLevel || "";
    adminElements.relationThemeSummaryInput.value = record.educationThemeSummary || "";
}

function resetRelationForm() {
    adminElements.relationForm.reset();
    adminElements.relationIdInput.value = "";
}

async function deleteRelation(relId) {
    if (!window.confirm("确认删除这条学校-资源关联吗？")) {
        return;
    }
    await requestJson(`/api/admin/school-resource-rel/${relId}`, { method: "DELETE" });
    setGlobalStatus("已删除", "学校与资源关联已删除。");
    await loadRelations();
}

async function loadPlans() {
    syncSelectOptions();
    const schoolId = adminState.selectedSchoolIdForPlans;
    const url = schoolId
        ? `/api/admin/schools/${schoolId}/activity-plans?pageNum=1&pageSize=50`
        : `/api/admin/activity-plans?pageNum=1&pageSize=50`;
    const result = await requestJson(url);
    const records = result.records || [];
    adminElements.planListCount.textContent = `${records.length} 条`;
    adminElements.planTableBody.innerHTML = "";
    if (adminElements.planTotalMetric) {
        adminElements.planTotalMetric.textContent = String(result.total || 0);
    }
    if (!records.length) {
        adminElements.planTableBody.innerHTML = `<tr><td colspan="5">暂无活动方案数据。</td></tr>`;
        return;
    }
    records.forEach(record => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${escapeHtml(record.planCode || "-")}</td>
            <td>
                <strong>${escapeHtml(record.theme || "-")}</strong>
                <div class="status-box">${escapeHtml(record.suitableGrade || "未填写适用年级")}</div>
            </td>
            <td>${escapeHtml(record.schoolName || "-")}</td>
            <td>${escapeHtml(record.activityType || "-")}</td>
            <td>
                <div class="table-actions">
                    <button class="action-button" data-action="edit">编辑</button>
                </div>
            </td>
        `;
        tr.querySelector('[data-action="edit"]').addEventListener("click", () => fillPlanForm(record));
        adminElements.planTableBody.appendChild(tr);
    });
}

async function submitPlanForm() {
    const planId = parseNullableNumber(adminElements.planIdInput.value);
    const body = {
        planCode: adminElements.planCodeInput.value.trim(),
        schoolId: parseNullableNumber(adminElements.planSchoolSelect.value),
        resourceId: parseNullableNumber(adminElements.planResourceSelect.value),
        theme: adminElements.planThemeInput.value.trim(),
        activityType: adminElements.planActivityTypeInput.value,
        suitableGrade: optionalText(adminElements.planSuitableGradeInput.value),
        objectiveText: optionalText(adminElements.planObjectiveInput.value),
        activityContent: optionalText(adminElements.planContentInput.value),
        preparationText: optionalText(adminElements.planPreparationInput.value),
        safetyText: optionalText(adminElements.planSafetyInput.value),
        expectedOutcome: optionalText(adminElements.planOutcomeInput.value),
        durationMinutes: parseNullableNumber(adminElements.planDurationInput.value)
    };

    if (!body.planCode || !body.schoolId || !body.theme || !body.activityContent) {
        setGlobalStatus("校验失败", "方案编码、学校、主题、活动内容不能为空。");
        return;
    }

    if (planId) {
        delete body.planCode;
        delete body.schoolId;
        await requestJson(`/api/admin/activity-plans/${planId}`, { method: "PUT", body });
        setGlobalStatus("已更新", "活动方案已更新。");
    } else {
        await requestJson("/api/admin/activity-plans", { method: "POST", body });
        setGlobalStatus("已创建", "活动方案已创建。");
    }

    adminState.selectedSchoolIdForPlans = body.schoolId || adminState.selectedSchoolIdForPlans;
    resetPlanForm();
    await loadPlans();
}

function fillPlanForm(record) {
    adminElements.planIdInput.value = record.planId || "";
    adminElements.planCodeInput.value = record.planCode || "";
    adminElements.planCodeInput.disabled = true;
    adminElements.planSchoolSelect.value = record.schoolId || "";
    adminElements.planResourceSelect.value = record.resourceId || "";
    adminElements.planActivityTypeInput.value = record.activityType || "classroom";
    adminElements.planThemeInput.value = record.theme || "";
    adminElements.planSuitableGradeInput.value = record.suitableGrade || "";
    adminElements.planDurationInput.value = record.durationMinutes || "";
    adminElements.planObjectiveInput.value = record.objectiveText || "";
    adminElements.planContentInput.value = record.activityContent || "";
    adminElements.planPreparationInput.value = record.preparationText || "";
    adminElements.planSafetyInput.value = record.safetyText || "";
    adminElements.planOutcomeInput.value = record.expectedOutcome || "";
}

function resetPlanForm() {
    adminElements.planForm.reset();
    adminElements.planIdInput.value = "";
    adminElements.planCodeInput.disabled = false;
}

function fillOptionSelect(element, records, valueKey, labelKey, emptyLabel = "请选择") {
    if (!element) {
        return;
    }
    const currentValue = element.value;
    element.innerHTML = "";
    const emptyOption = document.createElement("option");
    emptyOption.value = "";
    emptyOption.textContent = emptyLabel;
    element.appendChild(emptyOption);
    records.forEach(record => {
        const option = document.createElement("option");
        option.value = record[valueKey];
        option.textContent = record[labelKey] || `${valueKey}-${record[valueKey]}`;
        element.appendChild(option);
    });
    if (currentValue && Array.from(element.options).some(option => option.value === currentValue)) {
        element.value = currentValue;
    }
}

function renderStatus(value) {
    const key = String(value || "draft").toLowerCase();
    return `<span class="status-pill status-${escapeHtml(key)}">${escapeHtml(key)}</span>`;
}

function parseNullableNumber(value) {
    if (value === null || value === undefined || value === "") {
        return null;
    }
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
}

function optionalText(value) {
    const text = String(value || "").trim();
    return text ? text : null;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}
