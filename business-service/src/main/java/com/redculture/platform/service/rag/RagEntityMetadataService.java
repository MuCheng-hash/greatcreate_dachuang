package com.redculture.platform.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.HeroPerson;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.entity.RedStory;
import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.AdministrativeRegionMapper;
import com.redculture.platform.mapper.HeroPersonMapper;
import com.redculture.platform.mapper.HistoricalEventMapper;
import com.redculture.platform.mapper.LocalEduResourceMapper;
import com.redculture.platform.mapper.MemorialHallMapper;
import com.redculture.platform.mapper.RedSiteMapper;
import com.redculture.platform.mapper.RedStoryMapper;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.mapper.TeachingActivityPlanMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RagEntityMetadataService {

    private static final List<String> THEME_KEYWORDS = List.of(
            "理想信念", "革命传统", "爱国主义", "红色文化", "党史", "国防教育",
            "志愿服务", "敬老", "劳动教育", "社会责任", "乡土文化", "廉洁教育",
            "生态文明", "法治教育", "生命安全", "团结协作"
    );

    private final SchoolMapper schoolMapper;
    private final LocalEduResourceMapper resourceMapper;
    private final TeachingActivityPlanMapper activityPlanMapper;
    private final RedSiteMapper siteMapper;
    private final HeroPersonMapper heroMapper;
    private final HistoricalEventMapper eventMapper;
    private final MemorialHallMapper memorialMapper;
    private final RedStoryMapper storyMapper;
    private final AdministrativeRegionMapper regionMapper;
    private final SchoolResourceRelMapper schoolResourceRelMapper;

    public RagEntityMetadataService(SchoolMapper schoolMapper,
                                    LocalEduResourceMapper resourceMapper,
                                    TeachingActivityPlanMapper activityPlanMapper,
                                    RedSiteMapper siteMapper,
                                    HeroPersonMapper heroMapper,
                                    HistoricalEventMapper eventMapper,
                                    MemorialHallMapper memorialMapper,
                                    RedStoryMapper storyMapper,
                                    AdministrativeRegionMapper regionMapper,
                                    SchoolResourceRelMapper schoolResourceRelMapper) {
        this.schoolMapper = schoolMapper;
        this.resourceMapper = resourceMapper;
        this.activityPlanMapper = activityPlanMapper;
        this.siteMapper = siteMapper;
        this.heroMapper = heroMapper;
        this.eventMapper = eventMapper;
        this.memorialMapper = memorialMapper;
        this.storyMapper = storyMapper;
        this.regionMapper = regionMapper;
        this.schoolResourceRelMapper = schoolResourceRelMapper;
    }

    public Map<String, RagEntityMetadata> loadApproved(Map<EntityType, ? extends Collection<Long>> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, School> schools = approvedById(
                selectSchools(ids(requestedIds, EntityType.SCHOOL)), School::getSchoolId,
                School::getReviewStatus, School::getActive);
        Map<Long, LocalEduResource> resources = approvedById(
                selectResources(ids(requestedIds, EntityType.RESOURCE)), LocalEduResource::getResourceId,
                LocalEduResource::getReviewStatus, LocalEduResource::getActive);
        Map<Long, TeachingActivityPlan> plans = approvedById(
                selectPlans(ids(requestedIds, EntityType.ACTIVITY_PLAN)), TeachingActivityPlan::getPlanId,
                TeachingActivityPlan::getReviewStatus, TeachingActivityPlan::getActive);
        Map<Long, RedSite> sites = approvedById(
                selectSites(ids(requestedIds, EntityType.SITE)), RedSite::getSiteId,
                RedSite::getReviewStatus, RedSite::getActive);
        Map<Long, HeroPerson> heroes = approvedById(
                selectHeroes(ids(requestedIds, EntityType.HERO)), HeroPerson::getHeroId,
                HeroPerson::getReviewStatus, HeroPerson::getActive);
        Map<Long, HistoricalEvent> events = approvedById(
                selectEvents(ids(requestedIds, EntityType.EVENT)), HistoricalEvent::getEventId,
                HistoricalEvent::getReviewStatus, HistoricalEvent::getActive);
        Map<Long, MemorialHall> memorials = approvedById(
                selectMemorials(ids(requestedIds, EntityType.MEMORIAL)), MemorialHall::getMemorialId,
                MemorialHall::getReviewStatus, MemorialHall::getActive);
        Map<Long, RedStory> stories = approvedById(
                selectStories(ids(requestedIds, EntityType.STORY)), RedStory::getStoryId,
                RedStory::getReviewStatus, RedStory::getActive);

        List<SchoolResourceRel> approvedRelations = loadApprovedRelations(schools.keySet(), resources.keySet());
        Set<Long> relatedSchoolIds = approvedRelations.stream().map(SchoolResourceRel::getSchoolId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> relatedResourceIds = approvedRelations.stream().map(SchoolResourceRel::getResourceId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        plans.values().stream().map(TeachingActivityPlan::getSchoolId).filter(Objects::nonNull)
                .forEach(relatedSchoolIds::add);
        plans.values().stream().map(TeachingActivityPlan::getResourceId).filter(Objects::nonNull)
                .forEach(relatedResourceIds::add);
        loadMissingApprovedSchools(schools, relatedSchoolIds);
        loadMissingApprovedResources(resources, relatedResourceIds);

        Map<Long, String> regions = loadRegionNames(collectRegionIds(
                schools.values(), resources.values(), sites.values(), heroes.values(),
                events.values(), memorials.values(), stories.values()));
        Map<Long, List<String>> schoolRelations = relationNamesBySchool(approvedRelations, resources);
        Map<Long, List<String>> resourceRelations = relationNamesByResource(approvedRelations, schools);

        Map<String, RagEntityMetadata> result = new LinkedHashMap<>();
        requestedValues(schools, requestedIds, EntityType.SCHOOL).forEach(school -> put(result,
                schoolMetadata(school, regions, schoolRelations.get(school.getSchoolId()))));
        requestedValues(resources, requestedIds, EntityType.RESOURCE).forEach(resource -> put(result,
                resourceMetadata(resource, regions, resourceRelations.get(resource.getResourceId()))));
        requestedValues(plans, requestedIds, EntityType.ACTIVITY_PLAN).forEach(plan -> put(result,
                activityMetadata(plan, schools, resources)));
        requestedValues(sites, requestedIds, EntityType.SITE).forEach(site -> put(result,
                siteMetadata(site, regions)));
        requestedValues(heroes, requestedIds, EntityType.HERO).forEach(hero -> put(result,
                heroMetadata(hero, regions)));
        requestedValues(events, requestedIds, EntityType.EVENT).forEach(event -> put(result,
                eventMetadata(event, regions)));
        requestedValues(memorials, requestedIds, EntityType.MEMORIAL).forEach(memorial -> put(result,
                memorialMetadata(memorial, regions)));
        requestedValues(stories, requestedIds, EntityType.STORY).forEach(story -> put(result,
                storyMetadata(story, regions)));
        return Collections.unmodifiableMap(result);
    }

    private RagEntityMetadata schoolMetadata(School school,
                                             Map<Long, String> regions,
                                             List<String> relatedNames) {
        String region = firstRegion(regions, school.getVillageRegionId(), school.getTownshipRegionId(),
                school.getCountyRegionId(), school.getRegionId());
        String category = joinValues(school.getSchoolLevel(), school.getSchoolType(), school.getSchoolNature());
        return metadata(EntityType.SCHOOL, school.getSchoolId(), school.getSchoolName(),
                aliases(school.getSchoolAlias(), school.getSchoolCode()), region, category,
                null, themes(school.getIntro()), school.getSourceId(), joinValues(relatedNames));
    }

    private RagEntityMetadata resourceMetadata(LocalEduResource resource,
                                               Map<Long, String> regions,
                                               List<String> relatedNames) {
        String region = firstRegion(regions, resource.getTownshipRegionId(), resource.getCountyRegionId(),
                resource.getRegionId());
        String category = joinValues(resource.getResourceCategory(), resource.getResourceSubcategory());
        String theme = themes(resource.getResourceName(), category, resource.getEducationValue(),
                resource.getActivitySuggestion());
        return metadata(EntityType.RESOURCE, resource.getResourceId(), resource.getResourceName(),
                aliases(resource.getResourceAlias(), resource.getResourceCode()), region, category,
                resource.getTargetGrade(), theme, resource.getSourceId(), joinValues(relatedNames));
    }

    private RagEntityMetadata activityMetadata(TeachingActivityPlan plan,
                                               Map<Long, School> schools,
                                               Map<Long, LocalEduResource> resources) {
        List<String> related = new ArrayList<>();
        School school = schools.get(plan.getSchoolId());
        LocalEduResource resource = resources.get(plan.getResourceId());
        if (school != null) {
            related.add(school.getSchoolName());
        }
        if (resource != null) {
            related.add(resource.getResourceName());
        }
        String name = StringUtils.hasText(plan.getTheme()) ? plan.getTheme() : "教学活动方案 " + plan.getPlanId();
        return metadata(EntityType.ACTIVITY_PLAN, plan.getPlanId(), name, aliases(plan.getPlanCode()), null,
                joinValues(plan.getActivityType()), plan.getSuitableGrade(),
                themes(plan.getTheme(), plan.getObjectiveText(), plan.getActivityContent()),
                plan.getSourceId(), joinValues(related));
    }

    private RagEntityMetadata siteMetadata(RedSite site, Map<Long, String> regions) {
        return metadata(EntityType.SITE, site.getSiteId(), site.getSiteName(),
                aliases(site.getSiteAlias(), site.getSiteCode()), regions.get(site.getRegionId()),
                joinValues(site.getSiteLevel(), site.getProtectionLevel()), null,
                themes(site.getHistoricalBackground(), site.getIntro()), null, null);
    }

    private RagEntityMetadata heroMetadata(HeroPerson hero, Map<Long, String> regions) {
        String region = firstNonBlank(regions.get(hero.getNativePlaceRegionId()), hero.getNativePlaceText());
        return metadata(EntityType.HERO, hero.getHeroId(), hero.getHeroName(),
                aliases(hero.getHeroAlias(), hero.getHeroCode()), region, "英雄人物", null,
                themes(hero.getProfileSummary(), hero.getMainDeeds()), null, null);
    }

    private RagEntityMetadata eventMetadata(HistoricalEvent event, Map<Long, String> regions) {
        return metadata(EntityType.EVENT, event.getEventId(), event.getEventName(),
                aliases(event.getEventAlias(), event.getEventCode()), regions.get(event.getPrimaryRegionId()),
                "历史事件", null, themes(event.getHistoricalSignificance(), event.getEventProcess()), null, null);
    }

    private RagEntityMetadata memorialMetadata(MemorialHall memorial, Map<Long, String> regions) {
        return metadata(EntityType.MEMORIAL, memorial.getMemorialId(), memorial.getMemorialName(),
                aliases(memorial.getMemorialCode()), regions.get(memorial.getRegionId()), "纪念设施", null,
                themes(memorial.getExhibitionContent(), memorial.getIntro()), null, null);
    }

    private RagEntityMetadata storyMetadata(RedStory story, Map<Long, String> regions) {
        return metadata(EntityType.STORY, story.getStoryId(), story.getStoryTitle(),
                aliases(story.getStoryCode()), regions.get(story.getRelatedRegionId()), "红色故事",
                joinValues(story.getAgeGroup()), themes(story.getSummary(), story.getStoryContent()),
                story.getSourceId(), null);
    }

    private RagEntityMetadata metadata(EntityType type,
                                       Long id,
                                       String name,
                                       List<String> aliases,
                                       String region,
                                       String category,
                                       String grade,
                                       String theme,
                                       Long sourceId,
                                       String relatedEntities) {
        List<String> lines = new ArrayList<>();
        addLine(lines, "实体名称", name);
        addLine(lines, "别名", joinValues(aliases));
        addLine(lines, "行政区", region);
        addLine(lines, "实体类型", type.getValue());
        addLine(lines, "资源类型", category);
        addLine(lines, "适用年级", grade);
        addLine(lines, "教育主题", theme);
        addLine(lines, "关联学校或资源", relatedEntities);
        return new RagEntityMetadata(type, id, clean(name), List.copyOf(aliases), clean(region), clean(category),
                clean(grade), clean(theme), sourceId, clean(relatedEntities), String.join("\n", lines));
    }

    private List<SchoolResourceRel> loadApprovedRelations(Set<Long> schoolIds, Set<Long> resourceIds) {
        if (schoolIds.isEmpty() && resourceIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<SchoolResourceRel> wrapper = new LambdaQueryWrapper<SchoolResourceRel>()
                .eq(SchoolResourceRel::getReviewStatus, ReviewStatus.APPROVED)
                .and(condition -> {
                    boolean hasSchool = !schoolIds.isEmpty();
                    if (hasSchool) {
                        condition.in(SchoolResourceRel::getSchoolId, schoolIds);
                    }
                    if (!resourceIds.isEmpty()) {
                        if (hasSchool) {
                            condition.or();
                        }
                        condition.in(SchoolResourceRel::getResourceId, resourceIds);
                    }
                });
        List<SchoolResourceRel> values = schoolResourceRelMapper.selectList(wrapper);
        return values == null ? Collections.emptyList() : values;
    }

    private void loadMissingApprovedSchools(Map<Long, School> schools, Set<Long> ids) {
        Set<Long> missing = new LinkedHashSet<>(ids);
        missing.removeAll(schools.keySet());
        approvedById(selectSchools(missing), School::getSchoolId, School::getReviewStatus, School::getActive)
                .forEach(schools::putIfAbsent);
    }

    private void loadMissingApprovedResources(Map<Long, LocalEduResource> resources, Set<Long> ids) {
        Set<Long> missing = new LinkedHashSet<>(ids);
        missing.removeAll(resources.keySet());
        approvedById(selectResources(missing), LocalEduResource::getResourceId,
                LocalEduResource::getReviewStatus, LocalEduResource::getActive)
                .forEach(resources::putIfAbsent);
    }

    private Map<Long, List<String>> relationNamesBySchool(List<SchoolResourceRel> relations,
                                                         Map<Long, LocalEduResource> resources) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (SchoolResourceRel relation : relations) {
            LocalEduResource resource = resources.get(relation.getResourceId());
            if (resource != null && StringUtils.hasText(resource.getResourceName())) {
                result.computeIfAbsent(relation.getSchoolId(), ignored -> new ArrayList<>())
                        .add(resource.getResourceName());
            }
        }
        return result;
    }

    private Map<Long, List<String>> relationNamesByResource(List<SchoolResourceRel> relations,
                                                           Map<Long, School> schools) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (SchoolResourceRel relation : relations) {
            School school = schools.get(relation.getSchoolId());
            if (school != null && StringUtils.hasText(school.getSchoolName())) {
                result.computeIfAbsent(relation.getResourceId(), ignored -> new ArrayList<>())
                        .add(school.getSchoolName());
            }
        }
        return result;
    }

    private Set<Long> collectRegionIds(Collection<School> schools,
                                       Collection<LocalEduResource> resources,
                                       Collection<RedSite> sites,
                                       Collection<HeroPerson> heroes,
                                       Collection<HistoricalEvent> events,
                                       Collection<MemorialHall> memorials,
                                       Collection<RedStory> stories) {
        Set<Long> ids = new LinkedHashSet<>();
        schools.forEach(item -> addIds(ids, item.getRegionId(), item.getCountyRegionId(),
                item.getTownshipRegionId(), item.getVillageRegionId()));
        resources.forEach(item -> addIds(ids, item.getRegionId(), item.getCountyRegionId(),
                item.getTownshipRegionId()));
        sites.forEach(item -> addIds(ids, item.getRegionId()));
        heroes.forEach(item -> addIds(ids, item.getNativePlaceRegionId()));
        events.forEach(item -> addIds(ids, item.getPrimaryRegionId()));
        memorials.forEach(item -> addIds(ids, item.getRegionId()));
        stories.forEach(item -> addIds(ids, item.getRelatedRegionId()));
        return ids;
    }

    private Map<Long, String> loadRegionNames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AdministrativeRegion> regions = regionMapper.selectBatchIds(ids);
        if (regions == null) {
            return Collections.emptyMap();
        }
        return regions.stream().filter(Objects::nonNull)
                .filter(region -> region.getRegionId() != null && StringUtils.hasText(region.getRegionName()))
                .collect(Collectors.toMap(AdministrativeRegion::getRegionId, AdministrativeRegion::getRegionName,
                        (first, second) -> first, LinkedHashMap::new));
    }

    private <T> Map<Long, T> approvedById(List<T> values,
                                         Function<T, Long> idGetter,
                                         Function<T, ReviewStatus> reviewGetter,
                                         Function<T, Boolean> activeGetter) {
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return values.stream().filter(Objects::nonNull)
                .filter(value -> idGetter.apply(value) != null)
                .filter(value -> reviewGetter.apply(value) == ReviewStatus.APPROVED)
                .filter(value -> Boolean.TRUE.equals(activeGetter.apply(value)))
                .collect(Collectors.toMap(idGetter, Function.identity(), (first, second) -> first,
                        LinkedHashMap::new));
    }

    private <T> Collection<T> requestedValues(Map<Long, T> values,
                                              Map<EntityType, ? extends Collection<Long>> requestedIds,
                                              EntityType type) {
        Set<Long> requested = ids(requestedIds, type);
        return requested.stream().map(values::get).filter(Objects::nonNull).toList();
    }

    private Set<Long> ids(Map<EntityType, ? extends Collection<Long>> requestedIds, EntityType type) {
        Collection<Long> values = requestedIds.get(type);
        if (values == null) {
            return Collections.emptySet();
        }
        return values.stream().filter(Objects::nonNull).filter(id -> id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<School> selectSchools(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : schoolMapper.selectBatchIds(ids);
    }

    private List<LocalEduResource> selectResources(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : resourceMapper.selectBatchIds(ids);
    }

    private List<TeachingActivityPlan> selectPlans(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : activityPlanMapper.selectBatchIds(ids);
    }

    private List<RedSite> selectSites(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : siteMapper.selectBatchIds(ids);
    }

    private List<HeroPerson> selectHeroes(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : heroMapper.selectBatchIds(ids);
    }

    private List<HistoricalEvent> selectEvents(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : eventMapper.selectBatchIds(ids);
    }

    private List<MemorialHall> selectMemorials(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : memorialMapper.selectBatchIds(ids);
    }

    private List<RedStory> selectStories(Collection<Long> ids) {
        return ids.isEmpty() ? Collections.emptyList() : storyMapper.selectBatchIds(ids);
    }

    private List<String> aliases(String... values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            for (String alias : value.split("[，,；;、|/]+")) {
                if (StringUtils.hasText(alias)) {
                    result.add(alias.trim());
                }
            }
        }
        return List.copyOf(result);
    }

    private String themes(String... values) {
        String haystack = joinValues((Object[]) values).toLowerCase(Locale.ROOT);
        return THEME_KEYWORDS.stream().filter(haystack::contains).collect(Collectors.joining("、"));
    }

    private String firstRegion(Map<Long, String> regions, Long... ids) {
        for (Long id : ids) {
            if (id != null && StringUtils.hasText(regions.get(id))) {
                return regions.get(id);
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void addIds(Set<Long> ids, Long... values) {
        for (Long value : values) {
            if (value != null && value > 0) {
                ids.add(value);
            }
        }
    }

    private void put(Map<String, RagEntityMetadata> result, RagEntityMetadata metadata) {
        if (metadata != null && metadata.entityId() != null) {
            result.put(metadata.entityKey(), metadata);
        }
    }

    private void addLine(List<String> lines, String label, String value) {
        if (StringUtils.hasText(value)) {
            lines.add("[" + label + "] " + value.trim().replaceAll("\\s+", " "));
        }
    }

    private String joinValues(Object... values) {
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Collection<?> collection) {
                collection.stream().filter(Objects::nonNull).map(String::valueOf)
                        .filter(StringUtils::hasText).map(String::trim).forEach(result::add);
            } else if (value != null && StringUtils.hasText(String.valueOf(value))) {
                result.add(String.valueOf(value).trim());
            }
        }
        return String.join("、", result);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
