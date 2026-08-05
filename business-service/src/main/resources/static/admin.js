const adminState = {
    activeTab: "schools",
    registrations: [],
    schools: [],
    resources: [],
    schoolProvinceRegions: [],
    schoolCityRegions: [],
    schoolCountyRegions: [],
    schoolTownshipRegions: [],
    schoolRegions: [],
    appConfig: {
        amapKey: "",
        amapSecurityJsCode: ""
    },
    amapSdkLoading: null,
    amapGeocoder: null,
    discoveryCandidates: [],
    selectedSchoolIdForRelations: null,
    selectedSchoolIdForPlans: null,
    agentSummary: null,
    agentTraces: [],
    agentToolTraces: [],
    agentPromptVersions: [],
    agentPromptMetrics: []
};

const adminElements = {
    globalStatusText: document.querySelector("#globalStatusText"),
    globalStatusHint: document.querySelector("#globalStatusHint"),
    schoolTotalMetric: document.querySelector("#schoolTotalMetric"),
    resourceTotalMetric: document.querySelector("#resourceTotalMetric"),
    planTotalMetric: document.querySelector("#planTotalMetric"),
    refreshDashboardButton: document.querySelector("#refreshDashboardButton"),
    logoutButton: document.querySelector("#logoutButton"),
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
    schoolNameInput: document.querySelector("#schoolNameInput"),
    schoolTypeInput: document.querySelector("#schoolTypeInput"),
    schoolProvinceRegionIdInput: document.querySelector("#schoolProvinceRegionIdInput"),
    schoolCityRegionIdInput: document.querySelector("#schoolCityRegionIdInput"),
    schoolCountyRegionIdInput: document.querySelector("#schoolCountyRegionIdInput"),
    schoolTownshipRegionIdInput: document.querySelector("#schoolTownshipRegionIdInput"),
    schoolLongitudeInput: document.querySelector("#schoolLongitudeInput"),
    schoolLatitudeInput: document.querySelector("#schoolLatitudeInput"),
    schoolAddressInput: document.querySelector("#schoolAddressInput"),
    schoolGeocodeButton: document.querySelector("#schoolGeocodeButton"),
    schoolIntroInput: document.querySelector("#schoolIntroInput"),
    schoolKeywordInput: document.querySelector("#schoolKeywordInput"),
    schoolSearchButton: document.querySelector("#schoolSearchButton"),
    schoolRefreshButton: document.querySelector("#schoolRefreshButton"),
    schoolAddButton: document.querySelector("#schoolAddButton"),
    schoolResetButton: document.querySelector("#schoolResetButton"),
    schoolModal: document.querySelector("#schoolModal"),
    schoolModalTitle: document.querySelector("#schoolModalTitle"),
    schoolModalCloseButton: document.querySelector("#schoolModalCloseButton"),
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

    discoveryFilterSchoolSelect: document.querySelector("#discoveryFilterSchoolSelect"),
    discoveryAnalysisFilter: document.querySelector("#discoveryAnalysisFilter"),
    discoveryDecisionFilter: document.querySelector("#discoveryDecisionFilter"),
    discoverySearchButton: document.querySelector("#discoverySearchButton"),
    discoveryRefreshSchoolSelect: document.querySelector("#discoveryRefreshSchoolSelect"),
    discoveryRadiusInput: document.querySelector("#discoveryRadiusInput"),
    discoveryForceRefreshButton: document.querySelector("#discoveryForceRefreshButton"),
    discoveryRunStatus: document.querySelector("#discoveryRunStatus"),
    discoveryReviewForm: document.querySelector("#discoveryReviewForm"),
    discoveryCandidateIdInput: document.querySelector("#discoveryCandidateIdInput"),
    discoverySchoolNameInput: document.querySelector("#discoverySchoolNameInput"),
    discoveryProviderIdInput: document.querySelector("#discoveryProviderIdInput"),
    discoveryResourceNameInput: document.querySelector("#discoveryResourceNameInput"),
    discoveryCategoryInput: document.querySelector("#discoveryCategoryInput"),
    discoverySubcategoryInput: document.querySelector("#discoverySubcategoryInput"),
    discoveryAddressInput: document.querySelector("#discoveryAddressInput"),
    discoveryEducationValueInput: document.querySelector("#discoveryEducationValueInput"),
    discoveryTargetGradeInput: document.querySelector("#discoveryTargetGradeInput"),
    discoveryActivitySuggestionInput: document.querySelector("#discoveryActivitySuggestionInput"),
    discoveryVerificationNotesInput: document.querySelector("#discoveryVerificationNotesInput"),
    discoveryReviewerInput: document.querySelector("#discoveryReviewerInput"),
    discoveryReviewRemarkInput: document.querySelector("#discoveryReviewRemarkInput"),
    discoveryApproveButton: document.querySelector("#discoveryApproveButton"),
    discoveryRejectButton: document.querySelector("#discoveryRejectButton"),
    discoveryReopenButton: document.querySelector("#discoveryReopenButton"),
    discoveryVerificationText: document.querySelector("#discoveryVerificationText"),
    discoveryTableBody: document.querySelector("#discoveryTableBody"),
    discoveryListCount: document.querySelector("#discoveryListCount"),

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
    planListCount: document.querySelector("#planListCount"),

    agentTraceStatusFilter: document.querySelector("#agentTraceStatusFilter"),
    agentTraceFeatureFilter: document.querySelector("#agentTraceFeatureFilter"),
    agentToolNameFilter: document.querySelector("#agentToolNameFilter"),
    agentOpsRefreshButton: document.querySelector("#agentOpsRefreshButton"),
    agentCallsMetric: document.querySelector("#agentCallsMetric"),
    agentSuccessMetric: document.querySelector("#agentSuccessMetric"),
    agentAverageLatencyMetric: document.querySelector("#agentAverageLatencyMetric"),
    agentPercentileMetric: document.querySelector("#agentPercentileMetric"),
    agentTotalTokensMetric: document.querySelector("#agentTotalTokensMetric"),
    agentTokenBreakdownMetric: document.querySelector("#agentTokenBreakdownMetric"),
    agentFallbackMetric: document.querySelector("#agentFallbackMetric"),
    agentFallbackHint: document.querySelector("#agentFallbackHint"),
    agentFallbackReasons: document.querySelector("#agentFallbackReasons"),
    agentFallbackReasonCount: document.querySelector("#agentFallbackReasonCount"),
    agentPromptKeySelect: document.querySelector("#agentPromptKeySelect"),
    agentPromptListCount: document.querySelector("#agentPromptListCount"),
    agentPromptTableBody: document.querySelector("#agentPromptTableBody"),
    agentTimelineSessionCount: document.querySelector("#agentTimelineSessionCount"),
    agentTimelineEventCount: document.querySelector("#agentTimelineEventCount"),
    agentTimelineList: document.querySelector("#agentTimelineList")
};

document.addEventListener("DOMContentLoaded", () => {
    bindAdminEvents();
    void bootstrapAdmin();
});

function bindAdminEvents() {
    adminElements.refreshDashboardButton?.addEventListener("click", () => {
        void bootstrapAdmin();
    });
    adminElements.logoutButton?.addEventListener("click", () => void logoutAdmin());

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
    adminElements.schoolAddButton?.addEventListener("click", () => void openCreateSchoolModal());
    adminElements.schoolModalCloseButton?.addEventListener("click", closeSchoolModal);
    adminElements.schoolModal?.querySelector("[data-school-modal-close]")?.addEventListener("click", closeSchoolModal);
    adminElements.schoolProvinceRegionIdInput?.addEventListener("change", () => {
        void loadSchoolCityOptions(adminElements.schoolProvinceRegionIdInput.value);
    });
    adminElements.schoolCityRegionIdInput?.addEventListener("change", () => {
        void loadSchoolCountyOptions(adminElements.schoolCityRegionIdInput.value);
    });
    adminElements.schoolCountyRegionIdInput?.addEventListener("change", () => {
        void loadSchoolTownshipOptions(adminElements.schoolCountyRegionIdInput.value);
    });
    adminElements.schoolGeocodeButton?.addEventListener("click", () => void geocodeSchoolAddress());

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

    adminElements.agentOpsRefreshButton?.addEventListener("click", () => void loadAgentOps());
    adminElements.agentTraceStatusFilter?.addEventListener("change", () => void loadAgentOps());
    adminElements.agentTraceFeatureFilter?.addEventListener("change", () => void loadAgentOps());
    adminElements.agentToolNameFilter?.addEventListener("change", () => void loadAgentOps());
    adminElements.agentPromptKeySelect?.addEventListener("change", () => void loadAgentPrompts());
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && !adminElements.schoolModal?.hidden) {
            closeSchoolModal();
        }
    });
}

async function bootstrapAdmin() {
    setGlobalStatus("加载中", "正在同步学校、资源、关联和活动方案数据。");
    try {
        const currentUser = await requestJson("/api/auth/me");
        if (!currentUser?.accountId) {
            window.location.href = "/login";
            return;
        }
        if (currentUser.roleCode !== "platform_admin") {
            throw new Error("当前账号无后台管理权限，请使用管理员账号登录。");
        }
        await Promise.all([
            loadClientMapConfig(),
            loadSchoolRegionNames(),
            loadSchoolProvinceOptions(),
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

async function loadAgentOps() {
    if (!adminElements.agentTimelineList) return;
    const openKeys = getOpenAgentTimelineKeys();
    const traceParams = new URLSearchParams({ limit: "50" });
    const status = adminElements.agentTraceStatusFilter?.value || "";
    const feature = adminElements.agentTraceFeatureFilter?.value?.trim() || "";
    if (status) traceParams.set("status", status);
    if (feature) traceParams.set("feature", feature);

    const toolParams = new URLSearchParams({ limit: "50" });
    const toolName = adminElements.agentToolNameFilter?.value?.trim() || "";
    if (status) toolParams.set("status", status);
    if (toolName) toolParams.set("toolName", toolName);

    try {
        const [summary, traces, toolTraces] = await Promise.all([
            requestJson(`/api/admin/agent/observability/summary?${traceParams.toString()}`),
            requestJson(`/api/admin/agent/observability/traces?${traceParams.toString()}`),
            requestJson(`/api/admin/agent/observability/tool-traces?${toolParams.toString()}`)
        ]);
        adminState.agentSummary = summary || {};
        adminState.agentTraces = Array.isArray(traces) ? traces : [];
        adminState.agentToolTraces = Array.isArray(toolTraces) ? toolTraces : [];
        renderAgentSummary(adminState.agentSummary);
        renderAgentTimeline(adminState.agentTraces, adminState.agentToolTraces, openKeys);
        await loadAgentPrompts();
    } catch (error) {
        setGlobalStatus("Agent 运维异常", error.message || "Agent 运维接口请求失败。");
        renderAgentTimelineError(error.message || "Agent 运维数据暂不可用。");
    }
}

async function loadAgentPrompts() {
    if (!adminElements.agentPromptTableBody) return;
    const promptKey = adminElements.agentPromptKeySelect?.value || "agent";
    try {
        const [versions, metrics] = await Promise.all([
            requestJson(`/api/admin/agent/prompts/${encodeURIComponent(promptKey)}/versions`),
            requestJson(`/api/admin/agent/prompts/${encodeURIComponent(promptKey)}/metrics`)
        ]);
        adminState.agentPromptVersions = Array.isArray(versions) ? versions : [];
        adminState.agentPromptMetrics = Array.isArray(metrics) ? metrics : [];
        renderAgentPrompts(promptKey, adminState.agentPromptVersions, adminState.agentPromptMetrics);
    } catch (error) {
        renderAgentEmptyState(error.message || "Prompt 数据暂不可用。", "agentPromptTableBody", 5);
    }
}

function renderAgentSummary(summary) {
    const latency = summary.latencyMs || {};
    const tokens = summary.tokens || {};
    const fallbackReasons = summary.fallbackReasons || {};
    const fallbackCount = Number(summary.fallbackCalls || 0);
    const reasonCount = Object.keys(fallbackReasons).length;
    adminElements.agentCallsMetric.textContent = formatAgentNumber(summary.calls);
    const totalCalls = Number(summary.calls || 0);
    const successfulCalls = Number(summary.successfulCalls || 0);
    const failureRate = totalCalls > 0 ? (totalCalls - successfulCalls) / totalCalls : null;
    adminElements.agentSuccessMetric.textContent = `成功率 ${formatAgentPercent(summary.successRate)} / 失败率 ${formatAgentPercent(failureRate)}`;
    adminElements.agentAverageLatencyMetric.textContent = `${formatAgentNumber(latency.average)} ms`;
    adminElements.agentPercentileMetric.textContent = `P50 ${formatAgentNumber(latency.p50)} / P95 ${formatAgentNumber(latency.p95)} / P99 ${formatAgentNumber(latency.p99)}`;
    adminElements.agentTotalTokensMetric.textContent = formatAgentNumber(tokens.total);
    adminElements.agentTokenBreakdownMetric.textContent = `输入 ${formatAgentNumber(tokens.input)} / 输出 ${formatAgentNumber(tokens.output)}`;
    adminElements.agentFallbackMetric.textContent = formatAgentNumber(fallbackCount);
    adminElements.agentFallbackHint.textContent = `原因统计 ${reasonCount} 类`;
    adminElements.agentFallbackReasonCount.textContent = `${reasonCount} 类`;

    const reasonEntries = Object.entries(fallbackReasons);
    adminElements.agentFallbackReasons.innerHTML = reasonEntries.length
        ? reasonEntries.map(([reason, count]) => `
            <div class="agent-reason-item">
                <span>${escapeHtml(reason)}</span>
                <strong>${escapeHtml(String(count))}</strong>
            </div>
        `).join("")
        : `<div class="agent-empty-note">暂无 fallback 原因记录。</div>`;
}

function renderAgentPrompts(promptKey, versions, metrics) {
    const metricMap = new Map(metrics.map(item => [String(item.version || ""), item]));
    adminElements.agentPromptListCount.textContent = `${versions.length} 个版本`;
    adminElements.agentPromptTableBody.innerHTML = "";
    if (!versions.length) {
        renderAgentEmptyState(`暂无 ${promptKey} Prompt 版本。`, "agentPromptTableBody", 5);
        return;
    }
    versions.forEach(version => {
        const key = String(version.version || "");
        const metric = metricMap.get(key) || {};
        const active = Number(version.active) === 1 || version.active === true;
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td><strong>${escapeHtml(key || "-")}</strong></td>
            <td>${active ? '<span class="status-pill status-approved">当前激活</span>' : '<span class="status-pill status-draft">未激活</span>'}</td>
            <td>${escapeHtml(formatAgentDate(version.created_at || version.createdAt))}</td>
            <td>${escapeHtml(metric.runs == null ? "暂无运行" : `${metric.runs} 次 / 成功率 ${formatAgentPercent(metric.success_rate ?? metric.successRate)}`)}</td>
            <td><button class="action-button" data-prompt-version="${escapeHtml(key)}" ${active ? "disabled" : ""}>${active ? "已激活" : "激活"}</button></td>
        `;
        const button = tr.querySelector("[data-prompt-version]");
        button?.addEventListener("click", () => void activateAgentPrompt(promptKey, key));
        adminElements.agentPromptTableBody.appendChild(tr);
    });
}

async function activateAgentPrompt(promptKey, version) {
    if (!window.confirm(`确认激活 ${promptKey} / ${version}？`)) return;
    try {
        await requestJson(`/api/admin/agent/prompts/${encodeURIComponent(promptKey)}/versions/${encodeURIComponent(version)}/activate`, {
            method: "POST",
            body: {}
        });
        setGlobalStatus("Prompt 已激活", `${promptKey} 当前使用版本 ${version}。`);
        await loadAgentPrompts();
    } catch (error) {
        setGlobalStatus("Prompt 激活失败", error.message || "Prompt 激活失败。");
    }
}

function getOpenAgentTimelineKeys() {
    if (!adminElements.agentTimelineList) return new Set();
    return new Set(Array.from(
        adminElements.agentTimelineList.querySelectorAll("details.agent-session-group[open]")
    ).map(element => element.dataset.sessionKey).filter(Boolean));
}

function buildAgentSessionTimelines(modelRecords, toolRecords) {
    const groups = new Map();

    function ensureGroup(key, id, kind) {
        if (!groups.has(key)) {
            groups.set(key, {
                key,
                id,
                kind,
                modelRecords: [],
                toolRecords: [],
                retrievalRecords: [],
                retrievalKeys: new Set(),
                events: []
            });
        }
        return groups.get(key);
    }

    modelRecords.forEach(record => {
        const id = String(record.sessionId || "").trim();
        const group = ensureGroup(
            id ? `session:${id}` : "model:unlinked",
            id || "未关联会话",
            id ? "session" : "unlinked-model"
        );
        group.modelRecords.push(record);
        group.events.push({
            type: "model",
            timestamp: record.startedAt || record.completedAt,
            record
        });
        const retrievalTrace = record.metadata?.retrievalTrace;
        if (retrievalTrace && typeof retrievalTrace === "object") {
            const retrievalKey = JSON.stringify(retrievalTrace);
            if (!group.retrievalKeys.has(retrievalKey)) {
                group.retrievalKeys.add(retrievalKey);
                const retrievalRecord = {
                    ...retrievalTrace,
                    status: retrievalTrace.retrievalStatus === "degraded" || retrievalTrace.graphStatus === "failed"
                        ? "degraded" : (retrievalTrace.retrievalStatus || "completed")
                };
                group.retrievalRecords.push(retrievalRecord);
                group.events.push({
                    type: "retrieval",
                    timestamp: record.startedAt || record.completedAt,
                    record: retrievalRecord
                });
            }
        }
    });

    toolRecords.forEach(record => {
        const id = String(record.threadId || "").trim();
        const group = ensureGroup(
            id ? `session:${id}` : "tool:unlinked",
            id || "未关联模型 Trace",
            id ? "session" : "unlinked-tool"
        );
        group.toolRecords.push(record);
        group.events.push({
            type: "tool",
            timestamp: record.createdAt,
            record
        });
    });

    return Array.from(groups.values())
        .map(group => {
            group.events.sort((left, right) => agentTimestampValue(right.timestamp) - agentTimestampValue(left.timestamp));
            group.latestAt = group.events[0]?.timestamp || "";
            group.status = summarizeAgentTimelineStatus(group.events);
            return group;
        })
        .sort((left, right) => agentTimestampValue(right.latestAt) - agentTimestampValue(left.latestAt));
}

function summarizeAgentTimelineStatus(events) {
    const statuses = new Set(events.map(event => normalizeAgentStatus(event.record?.status)));
    if (statuses.has("failed")) return "failed";
    if (statuses.has("degraded") || statuses.has("invalid_response")) return "degraded";
    if (statuses.has("completed")) return "completed";
    return Array.from(statuses)[0] || "started";
}

function renderAgentTimeline(modelRecords, toolRecords, openKeys = new Set()) {
    const groups = buildAgentSessionTimelines(modelRecords, toolRecords);
    const eventCount = groups.reduce((total, group) => total + group.events.length, 0);
    adminElements.agentTimelineSessionCount.textContent = `${groups.length} 个会话`;
    adminElements.agentTimelineEventCount.textContent = `${eventCount} 个事件`;
    adminElements.agentTimelineList.innerHTML = "";

    if (!groups.length) {
        adminElements.agentTimelineList.innerHTML = '<div class="agent-empty-note">暂无符合条件的 Agent Trace。</div>';
        return;
    }

    const hasPreservedGroup = groups.some(group => openKeys.has(group.key));
    adminElements.agentTimelineList.innerHTML = groups.map((group, index) => {
        const shouldOpen = openKeys.has(group.key) || (!hasPreservedGroup && index === 0);
        return renderAgentSessionGroup(group, shouldOpen);
    }).join("");
}

function renderAgentSessionGroup(group, open) {
    const title = group.kind === "unlinked-tool" ? "未关联模型 Trace" : shortAgentId(group.id);
    const subtitle = group.kind === "session"
        ? `sessionId / threadId：${shortAgentId(group.id)}`
        : "接口返回中没有可用于关联的会话标识";
    const openAttribute = open ? " open" : "";
    return `
        <details class="agent-session-group" data-session-key="${escapeHtml(group.key)}"${openAttribute}>
            <summary class="agent-session-summary">
                <span class="agent-session-main">
                    <span class="agent-session-kind">会话时间线</span>
                    <strong class="agent-session-id" title="${escapeHtml(group.id)}">${escapeHtml(title)}</strong>
                    <span class="agent-session-time">最近 ${escapeHtml(formatAgentDate(group.latestAt))}</span>
                    <span class="agent-session-subtitle">${escapeHtml(subtitle)}</span>
                </span>
                <span class="agent-session-stats">
                    <span>模型 ${group.modelRecords.length}</span>
                    <span>检索 ${group.retrievalRecords.length}</span>
                    <span>工具 ${group.toolRecords.length}</span>
                </span>
                ${renderAgentStatus(group.status)}
            </summary>
            <div class="agent-session-events">
                <div class="agent-timeline-events">
                    ${group.events.map(renderAgentTimelineEvent).join("")}
                </div>
            </div>
        </details>
    `;
}

function renderAgentTimelineEvent(event) {
    const record = event.record || {};
    const isModel = event.type === "model";
    const isRetrieval = event.type === "retrieval";
    const metadata = record.metadata || {};
    const status = normalizeAgentStatus(record.status);
    const title = isModel ? (record.feature || "模型调用")
        : isRetrieval ? "可信知识检索" : (record.toolName || "工具调用");
    const subtitle = isModel
        ? ([record.provider, record.model].filter(Boolean).join(" / ") || "模型信息不可用")
        : isRetrieval ? `${record.intent || "UNKNOWN"} / Graph ${record.graphStatus || "skipped"}`
        : "受控业务工具";
    const details = isModel
        ? [
            ["耗时", record.latencyMs == null ? "-" : `${record.latencyMs} ms`],
            ["Token", record.totalTokens == null ? "-" : String(record.totalTokens)],
            ["Fallback", metadata.fallbackLevel == null ? "-" : String(metadata.fallbackLevel)],
            ["Trace ID", shortAgentId(record.traceId)]
        ]
        : isRetrieval ? [
            ["Dense", String(record.denseCandidateCount ?? 0)],
            ["Lexical", String(record.lexicalCandidateCount ?? 0)],
            ["Graph", String(record.graphCandidateCount ?? 0)],
            ["重排", String(record.rerankedCandidateCount ?? 0)]
        ] : [
            ["耗时", record.durationMs == null ? "-" : `${record.durationMs} ms`],
            ["线程", shortAgentId(record.threadId)],
            ["工具状态", status],
            ["创建时间", formatAgentDate(record.createdAt)]
        ];
    const preview = isModel
        ? (record.errorType || record.errorMessage || "模型调用完成，无错误摘要")
        : isRetrieval ? `检索方式：${(record.retrievalMethods || []).join(" / ") || "-"}`
        : (record.resultPreview || "工具未返回结果摘要");
    const argumentsText = !isModel && !isRetrieval && record.arguments && Object.keys(record.arguments).length
        ? formatAgentJson(record.arguments)
        : "";

    return `
        <article class="agent-timeline-event agent-timeline-event--${isModel ? "model" : isRetrieval ? "retrieval" : "tool"}">
            <span class="agent-event-marker" aria-hidden="true"></span>
            <div class="agent-event-card">
                <div class="agent-event-topline">
                    <div>
                        <span class="agent-event-kind">${isModel ? "模型" : isRetrieval ? "检索" : "工具"}</span>
                        <strong class="agent-event-title">${escapeHtml(title)}</strong>
                        <span class="agent-event-subtitle">${escapeHtml(subtitle)}</span>
                    </div>
                    <div class="agent-event-status">
                        ${renderAgentStatus(status)}
                        <time>${escapeHtml(formatAgentDate(event.timestamp))}</time>
                    </div>
                </div>
                <div class="agent-event-meta">
                    ${details.map(([label, value]) => `
                        <span class="agent-event-meta-item">
                            <span>${escapeHtml(label)}</span>
                            <strong>${escapeHtml(value)}</strong>
                        </span>
                    `).join("")}
                </div>
                <div class="agent-event-preview" title="${escapeHtml(preview)}">
                    <span>${isModel ? "错误 / 说明" : isRetrieval ? "检索摘要" : "结果摘要"}</span>
                    <p>${escapeHtml(truncateAgentText(preview, 260))}</p>
                </div>
                ${argumentsText ? `
                    <details class="agent-event-details">
                        <summary>查看工具参数</summary>
                        <pre>${escapeHtml(argumentsText)}</pre>
                    </details>
                ` : ""}
            </div>
        </article>
    `;
}

function renderAgentTimelineError(message) {
    adminElements.agentTimelineSessionCount.textContent = "0 个会话";
    adminElements.agentTimelineEventCount.textContent = "0 个事件";
    adminElements.agentTimelineList.innerHTML = `<div class="agent-empty-note agent-empty-note--error">${escapeHtml(message)}</div>`;
}

function renderAgentStatus(value) {
    const key = normalizeAgentStatus(value);
    return `<span class="status-pill status-${escapeHtml(key)}">${escapeHtml(key)}</span>`;
}

function normalizeAgentStatus(value) {
    const key = String(value || "started").toLowerCase();
    return key === "ok" ? "completed" : key;
}

function agentTimestampValue(value) {
    const timestamp = Date.parse(value || "");
    return Number.isNaN(timestamp) ? 0 : timestamp;
}

function formatAgentJson(value) {
    try {
        return truncateAgentText(JSON.stringify(value, null, 2), 1600);
    } catch (error) {
        return String(value || "-");
    }
}

function renderAgentEmptyState(message, elementId, colspan) {
    const element = document.querySelector(`#${elementId}`);
    if (element) element.innerHTML = `<tr><td colspan="${colspan}">${escapeHtml(message)}</td></tr>`;
}

function formatAgentNumber(value) {
    if (value === null || value === undefined || value === "" || Number.isNaN(Number(value))) return "-";
    return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 1 });
}

function formatAgentPercent(value) {
    if (value === null || value === undefined || value === "" || Number.isNaN(Number(value))) return "-";
    return `${(Number(value) * 100).toFixed(1)}%`;
}

function formatAgentDate(value) {
    if (!value) return "-";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN", { hour12: false });
}

function shortAgentId(value) {
    const text = String(value || "-");
    return text.length > 14 ? `${text.slice(0, 8)}…${text.slice(-4)}` : text;
}

function truncateAgentText(value, maxLength) {
    const text = String(value || "-");
    return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

async function logoutAdmin() {
    const button = adminElements.logoutButton;
    const originalLabel = button?.textContent || "退出登录";
    if (button) {
        button.disabled = true;
        button.textContent = "退出中...";
    }

    try {
        await requestJson("/api/auth/logout", { method: "POST", credentials: "same-origin" });
        window.location.replace("/login");
    } catch (error) {
        setGlobalStatus("退出失败", error.message || "退出登录失败，请稍后重试。");
        if (button) {
            button.disabled = false;
            button.textContent = originalLabel;
        }
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
    if (tabName === "agent-ops") {
        void loadAgentOps();
    }
}

function setGlobalStatus(title, hint) {
    if (adminElements.globalStatusText) {
        adminElements.globalStatusText.textContent = title;
    }
    if (adminElements.globalStatusHint) {
        adminElements.globalStatusHint.textContent = hint;
    }
}

function readCsrfToken() {
    const item = document.cookie.split(";").map(value => value.trim()).find(value => value.startsWith("XSRF-TOKEN="));
    if (!item) return "";
    try { return decodeURIComponent(item.slice("XSRF-TOKEN=".length)); } catch { return item.slice("XSRF-TOKEN=".length); }
}

function waitForRetry(delayMs) {
    return new Promise(resolve => window.setTimeout(resolve, delayMs));
}

async function refreshAuthCookies() {
    try {
        const response = await fetch("/api/auth/refresh", {
            method: "POST",
            credentials: "include",
            headers: { Accept: "application/json" }
        });
        const data = await response.json();
        return response.ok && data?.code === 200;
    } catch {
        return false;
    }
}

async function requestJson(url, options = {}, state = { refreshAttempted: false, retries: 0 }) {
    const config = { ...options, credentials: "include" };
    const method = String(config.method || "GET").toUpperCase();
    const headers = new Headers(config.headers || {});
    headers.set("Accept", headers.get("Accept") || "application/json");
    if (config.body !== undefined) {
        headers.set("Content-Type", headers.get("Content-Type") || "application/json");
        if (typeof config.body !== "string") config.body = JSON.stringify(config.body);
    }
    if (["POST", "PUT", "PATCH", "DELETE"].includes(method)) {
        const csrfToken = readCsrfToken();
        if (csrfToken) headers.set("X-CSRF-TOKEN", csrfToken);
    }
    config.headers = headers;

    let response;
    try {
        response = await fetch(url, config);
    } catch (error) {
        if (method === "GET" && state.retries < 2) {
            await waitForRetry(200 * 2 ** state.retries);
            return requestJson(url, options, { ...state, retries: state.retries + 1 });
        }
        throw new Error("后台服务不可达，请从 http://localhost:8080/admin.html 打开页面，并确认业务服务已启动。");
    }

    if (response.status === 401 && !state.refreshAttempted && url !== "/api/auth/refresh") {
        if (await refreshAuthCookies()) {
            return requestJson(url, options, { ...state, refreshAttempted: true });
        }
        window.dispatchEvent(new CustomEvent("portal:unauthorized"));
    }

    let data;
    try {
        data = await response.json();
    } catch (error) {
        throw new Error(`后台服务返回了无法解析的响应（HTTP ${response.status}）。`);
    }

    if (method === "GET" && state.retries < 2 && ([408, 429].includes(response.status) || response.status >= 500)) {
        await waitForRetry(200 * 2 ** state.retries);
        return requestJson(url, options, { ...state, retries: state.retries + 1 });
    }

    if (!response.ok || data.code !== 200) {
        if (response.status === 403) {
            throw new Error(data.message || "当前账号无权限访问后台。");
        }
        throw new Error(data.message || `请求失败（HTTP ${response.status}）。`);
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

async function openCreateSchoolModal() {
    resetSchoolForm();
    if (adminElements.schoolModalTitle) {
        adminElements.schoolModalTitle.textContent = "新增学校";
    }
    openSchoolModal();
    await loadSchoolProvinceOptions();
}

function openEditSchoolModal(record) {
    fillSchoolForm(record);
    if (adminElements.schoolModalTitle) {
        adminElements.schoolModalTitle.textContent = "修改学校";
    }
    openSchoolModal();
}

function openSchoolModal() {
    if (!adminElements.schoolModal) return;
    adminElements.schoolModal.hidden = false;
    document.body.classList.add("modal-open");
    setTimeout(() => adminElements.schoolNameInput?.focus(), 0);
}

function closeSchoolModal() {
    if (!adminElements.schoolModal) return;
    adminElements.schoolModal.hidden = true;
    document.body.classList.remove("modal-open");
}

async function loadClientMapConfig() {
    try {
        const config = await requestJson("/api/map/client-config");
        adminState.appConfig = {
            amapKey: String(config?.amapKey || "").trim(),
            amapSecurityJsCode: String(config?.amapSecurityJsCode || "").trim()
        };
    } catch (error) {
        setGlobalStatus("地图配置异常", error.message || "高德地图配置读取失败。");
    }
}

async function loadSchoolRegionNames() {
    const regions = await requestJson("/api/regions");
    adminState.schoolRegions = Array.isArray(regions) ? regions : [];
}

async function loadSchoolProvinceOptions(selectedProvinceId = null,
                                         selectedCityId = null,
                                         selectedCountyId = null,
                                         selectedTownshipId = null) {
    const provinces = await requestJson("/api/regions?regionLevel=PROVINCE");
    adminState.schoolProvinceRegions = Array.isArray(provinces) ? provinces : [];
    fillOptionSelect(adminElements.schoolProvinceRegionIdInput, adminState.schoolProvinceRegions, "regionId", "regionName", "请选择省份");
    if (selectedProvinceId != null && String(selectedProvinceId) !== "") {
        adminElements.schoolProvinceRegionIdInput.value = String(selectedProvinceId);
    }
    await loadSchoolCityOptions(adminElements.schoolProvinceRegionIdInput?.value, selectedCityId, selectedCountyId, selectedTownshipId);
}

async function loadSchoolCityOptions(provinceRegionId = null,
                                     selectedCityId = null,
                                     selectedCountyId = null,
                                     selectedTownshipId = null) {
    const citySelect = adminElements.schoolCityRegionIdInput;
    resetRegionSelect(citySelect, provinceRegionId ? "请选择城市" : "请先选择省份", !provinceRegionId);
    resetRegionSelect(adminElements.schoolCountyRegionIdInput, "请先选择城市", true);
    resetRegionSelect(adminElements.schoolTownshipRegionIdInput, "请先选择区县", true);
    adminState.schoolCityRegions = [];
    adminState.schoolCountyRegions = [];
    adminState.schoolTownshipRegions = [];
    if (!provinceRegionId) return;

    const cities = await requestJson(`/api/regions?parentRegionId=${encodeURIComponent(provinceRegionId)}&regionLevel=CITY`);
    adminState.schoolCityRegions = Array.isArray(cities) ? cities : [];
    fillOptionSelect(citySelect, adminState.schoolCityRegions, "regionId", "regionName", "请选择城市");
    citySelect.disabled = false;
    if (selectedCityId != null && String(selectedCityId) !== "") {
        citySelect.value = String(selectedCityId);
    }
    await loadSchoolCountyOptions(citySelect.value, selectedCountyId, selectedTownshipId);
}

async function loadSchoolCountyOptions(cityRegionId = null,
                                       selectedCountyId = null,
                                       selectedTownshipId = null) {
    const countySelect = adminElements.schoolCountyRegionIdInput;
    resetRegionSelect(countySelect, cityRegionId ? "请选择区县" : "请先选择城市", !cityRegionId);
    resetRegionSelect(adminElements.schoolTownshipRegionIdInput, "请先选择区县", true);
    adminState.schoolCountyRegions = [];
    adminState.schoolTownshipRegions = [];
    if (!cityRegionId) return;

    const counties = await requestJson(`/api/regions?parentRegionId=${encodeURIComponent(cityRegionId)}&regionLevel=COUNTY`);
    adminState.schoolCountyRegions = Array.isArray(counties) ? counties : [];
    fillOptionSelect(countySelect, adminState.schoolCountyRegions, "regionId", "regionName", "请选择区县");
    countySelect.disabled = false;
    if (selectedCountyId != null && String(selectedCountyId) !== "") {
        countySelect.value = String(selectedCountyId);
    }
    await loadSchoolTownshipOptions(countySelect.value, selectedTownshipId);
}

async function loadSchoolTownshipOptions(countyRegionId = null, selectedTownshipId = null) {
    const townshipSelect = adminElements.schoolTownshipRegionIdInput;
    resetRegionSelect(townshipSelect, countyRegionId ? "请选择乡镇" : "请先选择区县", !countyRegionId);
    adminState.schoolTownshipRegions = [];
    if (!countyRegionId) return;

    const townships = await requestJson(`/api/regions?parentRegionId=${encodeURIComponent(countyRegionId)}&regionLevel=TOWNSHIP`);
    adminState.schoolTownshipRegions = Array.isArray(townships) ? townships : [];
    fillOptionSelect(townshipSelect, adminState.schoolTownshipRegions, "regionId", "regionName", "请选择乡镇");
    townshipSelect.disabled = false;
    if (selectedTownshipId != null && String(selectedTownshipId) !== "") {
        townshipSelect.value = String(selectedTownshipId);
    }
}

function resetRegionSelect(select, label, disabled) {
    if (!select) return;
    select.innerHTML = "";
    const option = document.createElement("option");
    option.value = "";
    option.textContent = label;
    select.appendChild(option);
    select.disabled = Boolean(disabled);
}

async function geocodeSchoolAddress() {
    const query = buildSchoolAddressText();
    if (!query) {
        setGlobalStatus("地址不完整", "请先填写学校名称或详细地址。");
        return;
    }
    const button = adminElements.schoolGeocodeButton;
    const originalLabel = button?.textContent || "按地址定位坐标";
    if (button) {
        button.disabled = true;
        button.textContent = "定位中...";
    }
    try {
        console.info("School geocode query:", query);
        const result = await geocodeAddressWithAmap(query);
        if (!result) {
            setGlobalStatus("定位失败", "高德地图没有返回可用坐标，请补充更详细地址后重试。");
            return;
        }
        adminElements.schoolLongitudeInput.value = result.longitude.toFixed(7);
        adminElements.schoolLatitudeInput.value = result.latitude.toFixed(7);
        setGlobalStatus("定位成功", `已回填坐标：${result.longitude.toFixed(7)}, ${result.latitude.toFixed(7)}`);
    } catch (error) {
        setGlobalStatus("定位失败", error.message || "高德地图定位失败。");
    } finally {
        if (button) {
            button.disabled = false;
            button.textContent = originalLabel;
        }
    }
}

function buildSchoolAddressText() {
    return [
        selectedOptionText(adminElements.schoolProvinceRegionIdInput),
        selectedOptionText(adminElements.schoolCityRegionIdInput),
        selectedOptionText(adminElements.schoolCountyRegionIdInput),
        selectedOptionText(adminElements.schoolTownshipRegionIdInput),
        adminElements.schoolAddressInput?.value,
        adminElements.schoolNameInput?.value
    ]
            .map(value => String(value || "").trim())
            .filter(Boolean)
            .join("");
}

function selectedOptionText(select) {
    if (!select || !select.value) return "";
    return select.selectedOptions?.[0]?.textContent || "";
}

async function geocodeAddressWithAmap(address) {
    const geocoder = await ensureAmapGeocoder();
    return withTimeout(new Promise((resolve, reject) => {
        geocoder.getLocation(address, (status, result) => {
            if (status !== "complete") {
                reject(new Error(result?.info || "高德地图定位请求失败。"));
                return;
            }
            const location = result?.geocodes?.[0]?.location;
            if (!location) {
                resolve(null);
                return;
            }
            const longitude = Number(location.lng ?? location.getLng?.());
            const latitude = Number(location.lat ?? location.getLat?.());
            if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
                resolve(null);
                return;
            }
            resolve({ longitude, latitude });
        });
    }), 12000, "高德地图地址解析超时，请检查 Key、网络或域名白名单配置。");
}

async function ensureAmapGeocoder() {
    await ensureAmapSdk();
    if (adminState.amapGeocoder) {
        return adminState.amapGeocoder;
    }
    return withTimeout(new Promise((resolve, reject) => {
        window.AMap.plugin("AMap.Geocoder", () => {
            try {
                if (!window.AMap?.Geocoder) {
                    reject(new Error("高德地图 Geocoder 插件不可用。"));
                    return;
                }
                adminState.amapGeocoder = new window.AMap.Geocoder();
                resolve(adminState.amapGeocoder);
            } catch (error) {
                reject(error);
            }
        });
    }), 12000, "高德地图 Geocoder 插件加载超时，请检查 Key、网络或域名白名单配置。");
}

async function ensureAmapSdk() {
    if (window.AMap) return;
    const key = adminState.appConfig?.amapKey;
    if (!key) {
        throw new Error("未配置高德地图 Key。");
    }
    if (adminState.appConfig?.amapSecurityJsCode) {
        window._AMapSecurityConfig = { securityJsCode: adminState.appConfig.amapSecurityJsCode };
    }
    if (!adminState.amapSdkLoading) {
        adminState.amapSdkLoading = new Promise((resolve, reject) => {
            const script = document.createElement("script");
            script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
            script.async = true;
            script.onload = () => resolve();
            script.onerror = () => reject(new Error("高德地图 SDK 加载失败。"));
            document.head.appendChild(script);
        });
    }
    await adminState.amapSdkLoading;
    if (!window.AMap?.plugin) {
        adminState.amapSdkLoading = null;
        throw new Error("高德地图 SDK 加载异常，请检查 Key 是否可用。");
    }
}

function withTimeout(promise, timeoutMs, message) {
    let timer = null;
    const timeout = new Promise((_, reject) => {
        timer = window.setTimeout(() => reject(new Error(message)), timeoutMs);
    });
    return Promise.race([promise, timeout]).finally(() => {
        if (timer) {
            window.clearTimeout(timer);
        }
    });
}

function regionPathText(record) {
    const names = [
        regionNameById(adminState.schoolProvinceRegions, record.provinceRegionId),
        regionNameById(adminState.schoolCityRegions, record.cityRegionId),
        regionNameById(adminState.schoolCountyRegions, record.countyRegionId),
        regionNameById(adminState.schoolTownshipRegions, record.townshipRegionId)
    ].filter(Boolean);
    return names.length ? names.join(" / ") : "未选择行政区划";
}

function regionNameById(regions, regionId) {
    if (regionId == null) return "";
    const match = [...regions, ...adminState.schoolRegions]
            .find(region => String(region.regionId) === String(regionId));
    return match?.regionName || `ID ${regionId}`;
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
            <td>
                <strong>${escapeHtml(record.schoolName || "-")}</strong>
                <div class="status-box">${escapeHtml(regionPathText(record))}</div>
            </td>
            <td>${escapeHtml(record.schoolType || "-")}</td>
            <td>${escapeHtml(record.address || "未填写详细地址")}</td>
            <td>${record.active === false ? '<span class="status-pill status-draft">未启用</span>' : '<span class="status-pill status-approved">已启用</span>'}</td>
            <td>
                <div class="table-actions">
                    <button class="action-button" data-action="edit">编辑</button>
                    <button class="action-button" data-action="delete">删除</button>
                </div>
            </td>
        `;
        tr.querySelector('[data-action="edit"]').addEventListener("click", () => openEditSchoolModal(record));
        tr.querySelector('[data-action="delete"]').addEventListener("click", () => void deleteSchool(record));
        adminElements.schoolTableBody.appendChild(tr);
    });
}

async function deleteSchool(record) {
    if (!record?.schoolId) {
        return;
    }
    const confirmed = window.confirm(`确认删除学校“${record.schoolName || "未命名学校"}”吗？`);
    if (!confirmed) {
        return;
    }
    await requestJson(`/api/admin/schools/${record.schoolId}`, { method: "DELETE" });
    setGlobalStatus("已删除", "学校已删除。");
    await loadSchools();
}

async function submitSchoolForm() {
    const schoolId = parseNullableNumber(adminElements.schoolIdInput.value);
    const body = {
        schoolName: adminElements.schoolNameInput.value.trim(),
        provinceRegionId: parseNullableNumber(adminElements.schoolProvinceRegionIdInput.value),
        cityRegionId: parseNullableNumber(adminElements.schoolCityRegionIdInput.value),
        countyRegionId: parseNullableNumber(adminElements.schoolCountyRegionIdInput.value),
        townshipRegionId: parseNullableNumber(adminElements.schoolTownshipRegionIdInput.value),
        schoolType: optionalText(adminElements.schoolTypeInput.value),
        address: optionalText(adminElements.schoolAddressInput.value),
        longitude: parseNullableNumber(adminElements.schoolLongitudeInput.value),
        latitude: parseNullableNumber(adminElements.schoolLatitudeInput.value),
        intro: optionalText(adminElements.schoolIntroInput.value),
        active: true
    };

    if (!body.schoolName) {
        setGlobalStatus("校验失败", "学校名称不能为空。");
        return;
    }

    if (schoolId) {
        await requestJson(`/api/admin/schools/${schoolId}`, { method: "PUT", body });
        setGlobalStatus("已更新", "学校信息已更新。");
    } else {
        await requestJson("/api/admin/schools", { method: "POST", body });
        setGlobalStatus("已创建", "学校信息已创建。");
    }

    resetSchoolForm();
    closeSchoolModal();
    await loadSchools();
}

function fillSchoolForm(record) {
    adminElements.schoolIdInput.value = record.schoolId || "";
    adminElements.schoolNameInput.value = record.schoolName || "";
    adminElements.schoolTypeInput.value = record.schoolType || "";
    adminElements.schoolLongitudeInput.value = record.longitude || "";
    adminElements.schoolLatitudeInput.value = record.latitude || "";
    adminElements.schoolAddressInput.value = record.address || "";
    adminElements.schoolIntroInput.value = record.intro || "";
    void loadSchoolProvinceOptions(
            record.provinceRegionId,
            record.cityRegionId,
            record.countyRegionId,
            record.townshipRegionId
    );
}

function resetSchoolForm() {
    adminElements.schoolForm.reset();
    adminElements.schoolIdInput.value = "";
    void loadSchoolProvinceOptions();
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

async function loadDiscoveryCandidates() {
    const params = new URLSearchParams({ pageNum: "1", pageSize: "100" });
    const schoolId = parseNullableNumber(adminElements.discoveryFilterSchoolSelect?.value);
    const analysisStatus = adminElements.discoveryAnalysisFilter?.value || "";
    const decisionStatus = adminElements.discoveryDecisionFilter?.value || "";
    if (schoolId) params.set("schoolId", String(schoolId));
    if (analysisStatus) params.set("analysisStatus", analysisStatus);
    if (decisionStatus) params.set("decisionStatus", decisionStatus);
    const result = await requestJson(`/api/admin/resource-discovery-candidates?${params}`);
    adminState.discoveryCandidates = result.records || [];
    renderDiscoveryCandidates(adminState.discoveryCandidates);
}

function renderDiscoveryCandidates(records) {
    if (!adminElements.discoveryTableBody) return;
    adminElements.discoveryListCount.textContent = `${records.length} 条`;
    adminElements.discoveryTableBody.innerHTML = "";
    if (!records.length) {
        adminElements.discoveryTableBody.innerHTML = `<tr><td colspan="5">暂无 AI 候选资源。</td></tr>`;
        return;
    }
    records.forEach(record => {
        const tr = document.createElement("tr");
        const confidence = record.aiConfidence == null ? "待分析" : `${Math.round(Number(record.aiConfidence) * 100)}%`;
        tr.innerHTML = `
            <td><strong>${escapeHtml(record.placeName || "-")}</strong><div class="status-box">${escapeHtml(distanceText(record.distanceMeters))}</div></td>
            <td>${escapeHtml(record.providerTypeName || "-")}</td>
            <td>${escapeHtml(record.aiCategory || "待分析")}<div class="status-box">${escapeHtml(confidence)}</div></td>
            <td>${renderStatus(record.decisionStatus)}</td>
            <td><button class="action-button" data-action="view">审核</button></td>`;
        tr.querySelector('[data-action="view"]').addEventListener("click", () => fillDiscoveryReviewForm(record));
        adminElements.discoveryTableBody.appendChild(tr);
    });
}

function fillDiscoveryReviewForm(record) {
    const school = adminState.schools.find(item => item.schoolId === record.schoolId);
    adminElements.discoveryCandidateIdInput.value = record.candidateId || "";
    adminElements.discoverySchoolNameInput.value = school?.schoolName || `学校 ${record.schoolId}`;
    adminElements.discoveryProviderIdInput.value = record.providerPlaceId || "";
    adminElements.discoveryResourceNameInput.value = record.placeName || "";
    adminElements.discoveryCategoryInput.value = record.aiCategory || "other";
    adminElements.discoverySubcategoryInput.value = record.aiSubcategory || record.providerTypeName || "";
    adminElements.discoveryAddressInput.value = `${record.address || "地址待核实"} · ${distanceText(record.distanceMeters)}`;
    adminElements.discoveryEducationValueInput.value = record.aiRationale || "";
    adminElements.discoveryTargetGradeInput.value = record.targetGrades || "";
    adminElements.discoveryActivitySuggestionInput.value = record.activitySuggestion || "";
    adminElements.discoveryVerificationNotesInput.value = record.verificationNotes || "请核实地点真实性、开放时间、联系方式和接待条件。";
    adminElements.discoveryReviewerInput.value = record.reviewedBy || "admin";
    adminElements.discoveryReviewRemarkInput.value = record.reviewRemark || "";
    adminElements.discoveryVerificationText.textContent = `${record.analysisStatus || "unanalyzed"} / ${record.decisionStatus || "pending"}`;
    setActiveTab("discovery");
}

async function runDiscoveryReview(action) {
    const candidateId = parseNullableNumber(adminElements.discoveryCandidateIdInput?.value);
    if (!candidateId) {
        setGlobalStatus("请选择候选", "请先从候选列表选择一个场所。");
        return;
    }
    const body = {
        resourceName: optionalText(adminElements.discoveryResourceNameInput.value),
        resourceCategory: adminElements.discoveryCategoryInput.value,
        resourceSubcategory: optionalText(adminElements.discoverySubcategoryInput.value),
        educationValue: optionalText(adminElements.discoveryEducationValueInput.value),
        targetGrade: optionalText(adminElements.discoveryTargetGradeInput.value),
        activitySuggestion: optionalText(adminElements.discoveryActivitySuggestionInput.value),
        reviewerName: optionalText(adminElements.discoveryReviewerInput.value),
        reviewRemark: optionalText(adminElements.discoveryReviewRemarkInput.value)
    };
    await requestJson(`/api/admin/resource-discovery-candidates/${candidateId}/${action}`, { method: "POST", body });
    setGlobalStatus("操作成功", action === "approve" ? "候选已转为正式资源。" : action === "reject" ? "候选已驳回。" : "候选已重新打开。" );
    adminElements.discoveryReviewForm?.reset();
    await Promise.all([loadDiscoveryCandidates(), loadResources()]);
    syncSelectOptions();
}

async function forceDiscoveryRefresh() {
    const schoolId = parseNullableNumber(adminElements.discoveryRefreshSchoolSelect?.value);
    const radiusKm = parseNullableNumber(adminElements.discoveryRadiusInput?.value) || 5;
    if (!schoolId) {
        setGlobalStatus("请选择学校", "强制刷新前需要指定学校。");
        return;
    }
    adminElements.discoveryRunStatus.textContent = "正在调用高德与 LLM...";
    let run = await requestJson(`/api/admin/schools/${schoolId}/discovery-runs`, { method: "POST", body: { radiusKm } });
    for (let attempt = 0; run && ["pending", "running"].includes(run.status) && attempt < 15; attempt += 1) {
        await new Promise(resolve => setTimeout(resolve, 2000));
        run = await requestJson(`/api/school-map/schools/${schoolId}/discovery-runs/${run.runId}`);
    }
    adminElements.discoveryRunStatus.textContent = run?.status === "completed"
        ? `完成：${run.candidateCount || 0} 个候选，${run.analysisCount || 0} 个已分析。`
        : run?.errorMessage || "任务仍在后台运行。";
    await loadDiscoveryCandidates();
}

function distanceText(meters) {
    if (meters == null) return "距离待计算";
    return meters >= 1000 ? `${(meters / 1000).toFixed(1)} 公里` : `${meters} 米`;
}

function syncSelectOptions() {
    fillOptionSelect(adminElements.relationSchoolSelect, adminState.schools, "schoolId", "schoolName");
    fillOptionSelect(adminElements.relationFilterSchoolSelect, adminState.schools, "schoolId", "schoolName", "请选择学校查看关联");
    fillOptionSelect(adminElements.planSchoolSelect, adminState.schools, "schoolId", "schoolName");
    fillOptionSelect(adminElements.planFilterSchoolSelect, adminState.schools, "schoolId", "schoolName", "请选择学校查看方案");
    fillOptionSelect(adminElements.discoveryFilterSchoolSelect, adminState.schools, "schoolId", "schoolName", "全部学校");
    fillOptionSelect(adminElements.discoveryRefreshSchoolSelect, adminState.schools, "schoolId", "schoolName", "选择学校");
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
