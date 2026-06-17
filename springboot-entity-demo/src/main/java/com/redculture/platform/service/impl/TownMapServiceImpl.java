package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.EventHeroRel;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.entity.HeroPerson;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.entity.RedStory;
import com.redculture.platform.entity.SiteEventRel;
import com.redculture.platform.entity.SiteHeroRel;
import com.redculture.platform.entity.StoryEntityRel;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.RegionLevel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.EventHeroRelMapper;
import com.redculture.platform.mapper.SiteEventRelMapper;
import com.redculture.platform.mapper.SiteHeroRelMapper;
import com.redculture.platform.mapper.StoryEntityRelMapper;
import com.redculture.platform.service.AdministrativeRegionService;
import com.redculture.platform.service.HistoricalEventService;
import com.redculture.platform.service.HeroPersonService;
import com.redculture.platform.service.MemorialHallService;
import com.redculture.platform.service.RedSiteService;
import com.redculture.platform.service.RedStoryService;
import com.redculture.platform.service.TownGraphQueryService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.vo.EventSummaryVO;
import com.redculture.platform.vo.HeroSummaryVO;
import com.redculture.platform.vo.MapResourceMarkerVO;
import com.redculture.platform.vo.RegionCenterVO;
import com.redculture.platform.vo.StorySummaryVO;
import com.redculture.platform.vo.TownBoundaryVO;
import com.redculture.platform.vo.TownLocateResponse;
import com.redculture.platform.vo.TownMapDetailVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TownMapServiceImpl implements TownMapService {

    private final AdministrativeRegionService administrativeRegionService;
    private final RedSiteService redSiteService;
    private final MemorialHallService memorialHallService;
    private final HistoricalEventService historicalEventService;
    private final RedStoryService redStoryService;
    private final HeroPersonService heroPersonService;
    private final TownGraphQueryService townGraphQueryService;
    private final TownBoundaryGeometryService townBoundaryGeometryService;
    private final AppMapProperties appMapProperties;
    private final SiteEventRelMapper siteEventRelMapper;
    private final SiteHeroRelMapper siteHeroRelMapper;
    private final EventHeroRelMapper eventHeroRelMapper;
    private final StoryEntityRelMapper storyEntityRelMapper;

    public TownMapServiceImpl(AdministrativeRegionService administrativeRegionService,
                              RedSiteService redSiteService,
                              MemorialHallService memorialHallService,
                              HistoricalEventService historicalEventService,
                              RedStoryService redStoryService,
                              HeroPersonService heroPersonService,
                              TownGraphQueryService townGraphQueryService,
                              TownBoundaryGeometryService townBoundaryGeometryService,
                              AppMapProperties appMapProperties,
                              SiteEventRelMapper siteEventRelMapper,
                              SiteHeroRelMapper siteHeroRelMapper,
                              EventHeroRelMapper eventHeroRelMapper,
                              StoryEntityRelMapper storyEntityRelMapper) {
        this.administrativeRegionService = administrativeRegionService;
        this.redSiteService = redSiteService;
        this.memorialHallService = memorialHallService;
        this.historicalEventService = historicalEventService;
        this.redStoryService = redStoryService;
        this.heroPersonService = heroPersonService;
        this.townGraphQueryService = townGraphQueryService;
        this.townBoundaryGeometryService = townBoundaryGeometryService;
        this.appMapProperties = appMapProperties;
        this.siteEventRelMapper = siteEventRelMapper;
        this.siteHeroRelMapper = siteHeroRelMapper;
        this.eventHeroRelMapper = eventHeroRelMapper;
        this.storyEntityRelMapper = storyEntityRelMapper;
    }

    @Override
    public TownLocateResponse locateTown(BigDecimal longitude, BigDecimal latitude) {
        List<AdministrativeRegion> allRegions = loadAllRegions();
        List<AdministrativeRegion> townships = allRegions.stream()
                .filter(region -> region.getRegionLevel() == RegionLevel.TOWNSHIP)
                .collect(Collectors.toList());

        AdministrativeRegion matchedTown = matchTownship(longitude, latitude, allRegions, townships);
        String matchMode = matchedTown == null ? "unmatched" : "boundary_match";
        String message = matchedTown == null
                ? "no township boundary matched current location"
                : "matched by township boundary";
        TownBoundaryGeometryService.TownBoundaryProjection projection = matchedTown == null
                ? null
                : townBoundaryGeometryService.project(matchedTown);

        TownLocateResponse response = new TownLocateResponse();
        response.setLongitude(longitude);
        response.setLatitude(latitude);
        response.setMatched(matchedTown != null);
        response.setMatchMode(matchMode);
        response.setMessage(message);
        response.setTown(projection == null ? null : projection.boundaryVO());
        return response;
    }

    @Override
    public TownMapDetailVO getTownMapDetail(Long regionId) {
        AdministrativeRegion region = administrativeRegionService.getById(regionId);
        if (region == null) {
            return null;
        }

        TownMapDetailVO detailVO = new TownMapDetailVO();
        detailVO.setRegionId(region.getRegionId());
        detailVO.setRegionName(region.getRegionName());
        detailVO.setRegionLevel(region.getRegionLevel() == null ? null : region.getRegionLevel().name().toLowerCase());
        detailVO.setIntro(region.getIntro());
        detailVO.setCenter(toCenter(region));
        detailVO.setSuggestedQuestions(buildSuggestedQuestions(region.getRegionName()));

        try {
            TownBoundaryGeometryService.TownBoundaryProjection boundaryProjection = townBoundaryGeometryService.project(region);
            detailVO.setBoundaryGeoJson(boundaryProjection == null ? null : boundaryProjection.boundaryGeoJson());
            detailVO.setBoundaryStatus(boundaryProjection == null ? "missing" : boundaryProjection.boundaryStatus());
        } catch (Exception exception) {
            detailVO.setBoundaryGeoJson(null);
            detailVO.setBoundaryStatus("missing");
        }

        try {
            TownGraphQueryService.TownGraphSnapshot graphSnapshot = townGraphQueryService.loadTownGraph(regionId);
            if (graphSnapshot.isAvailable()) {
                detailVO.setGraphAvailable(true);
                detailVO.setGraphStatusMessage(graphSnapshot.getMessage());
                detailVO.setMarkers(graphSnapshot.getMarkers());
                detailVO.setHeroes(graphSnapshot.getHeroes());
                detailVO.setStories(graphSnapshot.getStories());
                detailVO.setEvents(graphSnapshot.getEvents());
                return detailVO;
            }

            detailVO.setGraphAvailable(false);
            try {
                MysqlTownSnapshot mysqlSnapshot = loadMysqlSnapshot(regionId);
                detailVO.setGraphStatusMessage(graphSnapshot.getMessage());
                detailVO.setMarkers(mysqlSnapshot.markers());
                detailVO.setHeroes(mysqlSnapshot.heroes());
                detailVO.setStories(mysqlSnapshot.stories());
                detailVO.setEvents(mysqlSnapshot.events());
            } catch (Exception exception) {
                detailVO.setGraphStatusMessage("mysql relation aggregation failed, using basic resource fallback");
                fillBasicFallback(detailVO, regionId);
            }
        } catch (Exception exception) {
            detailVO.setGraphAvailable(false);
            detailVO.setGraphStatusMessage("town detail fallback activated");
            fillBasicFallback(detailVO, regionId);
        }

        return detailVO;
    }

    @Override
    public TownBoundaryVO getTownBoundary(Long regionId) {
        AdministrativeRegion region = administrativeRegionService.getById(regionId);
        if (region == null) {
            return null;
        }
        TownBoundaryGeometryService.TownBoundaryProjection projection = townBoundaryGeometryService.project(region);
        return projection == null ? null : projection.boundaryVO();
    }

    @Override
    public List<TownBoundaryVO> listTownBoundaries() {
        return listRegionBoundaries("township", null, appMapProperties.getFocusProvinceRegionId());
    }

    @Override
    public List<TownBoundaryVO> listRegionBoundaries(String regionLevel, Long parentRegionId) {
        return listRegionBoundaries(regionLevel, parentRegionId, null);
    }

    @Override
    public List<TownBoundaryVO> listRegionBoundaries(String regionLevel, Long parentRegionId, Long ancestorRegionId) {
        List<AdministrativeRegion> allRegions = administrativeRegionService.list(new LambdaQueryWrapper<AdministrativeRegion>()
                .orderByAsc(AdministrativeRegion::getRegionLevel)
                .orderByAsc(AdministrativeRegion::getRegionName));
        LambdaQueryWrapper<AdministrativeRegion> queryWrapper = new LambdaQueryWrapper<>();

        RegionLevel normalizedLevel = parseRegionLevel(regionLevel);
        if (normalizedLevel != null) {
            queryWrapper.eq(AdministrativeRegion::getRegionLevel, normalizedLevel);
        }
        if (parentRegionId != null) {
            queryWrapper.eq(AdministrativeRegion::getParentRegionId, parentRegionId);
        }

        queryWrapper.orderByAsc(AdministrativeRegion::getRegionLevel)
                .orderByAsc(AdministrativeRegion::getRegionName);

        List<AdministrativeRegion> candidates = administrativeRegionService.list(queryWrapper);
        if (ancestorRegionId != null) {
            Map<Long, AdministrativeRegion> regionMap = allRegions.stream()
                    .collect(Collectors.toMap(AdministrativeRegion::getRegionId, region -> region, (left, right) -> left, LinkedHashMap::new));
            candidates = candidates.stream()
                    .filter(region -> ancestorRegionId.equals(region.getRegionId()) || hasAncestor(region, ancestorRegionId, regionMap))
                    .collect(Collectors.toList());
        }

        return candidates.stream()
                .map(townBoundaryGeometryService::project)
                .filter(projection -> projection != null)
                .map(TownBoundaryGeometryService.TownBoundaryProjection::boundaryVO)
                .collect(Collectors.toList());
    }

    private AdministrativeRegion matchTownship(BigDecimal longitude,
                                               BigDecimal latitude,
                                               List<AdministrativeRegion> allRegions,
                                               List<AdministrativeRegion> townships) {
        if (longitude == null || latitude == null || townships.isEmpty()) {
            return null;
        }

        AdministrativeRegion nearestAnchor = findNearestAnchor(longitude, latitude, allRegions);
        List<AdministrativeRegion> candidates = nearestAnchor == null
                ? townships
                : filterTownshipsUnderAnchor(nearestAnchor.getRegionId(), allRegions, townships);

        if (candidates.isEmpty()) {
            candidates = townships;
        }

        for (AdministrativeRegion candidate : candidates) {
            TownBoundaryGeometryService.TownBoundaryProjection projection = townBoundaryGeometryService.project(candidate);
            if (townBoundaryGeometryService.contains(projection, longitude, latitude)) {
                return candidate;
            }
        }

        for (AdministrativeRegion township : townships) {
            TownBoundaryGeometryService.TownBoundaryProjection projection = townBoundaryGeometryService.project(township);
            if (townBoundaryGeometryService.contains(projection, longitude, latitude)) {
                return township;
            }
        }
        return null;
    }

    private AdministrativeRegion findNearestAnchor(BigDecimal longitude,
                                                   BigDecimal latitude,
                                                   List<AdministrativeRegion> allRegions) {
        return allRegions.stream()
                .filter(region -> region.getRegionLevel() == RegionLevel.COUNTY || region.getRegionLevel() == RegionLevel.CITY)
                .filter(region -> region.getCenterLongitude() != null && region.getCenterLatitude() != null)
                .min(Comparator.comparing(region -> distanceScore(longitude, latitude, region)))
                .orElse(null);
    }

    private List<AdministrativeRegion> filterTownshipsUnderAnchor(Long anchorId,
                                                                  List<AdministrativeRegion> allRegions,
                                                                  List<AdministrativeRegion> townships) {
        Map<Long, AdministrativeRegion> regionMap = allRegions.stream()
                .collect(Collectors.toMap(AdministrativeRegion::getRegionId, region -> region));
        return townships.stream()
                .filter(township -> hasAncestor(township, anchorId, regionMap))
                .collect(Collectors.toList());
    }

    private boolean hasAncestor(AdministrativeRegion region,
                                Long ancestorId,
                                Map<Long, AdministrativeRegion> regionMap) {
        AdministrativeRegion current = region;
        while (current != null && current.getParentRegionId() != null) {
            if (ancestorId.equals(current.getParentRegionId())) {
                return true;
            }
            current = regionMap.get(current.getParentRegionId());
        }
        return false;
    }

    private double distanceScore(BigDecimal longitude,
                                 BigDecimal latitude,
                                 AdministrativeRegion region) {
        double dx = longitude.doubleValue() - region.getCenterLongitude().doubleValue();
        double dy = latitude.doubleValue() - region.getCenterLatitude().doubleValue();
        return dx * dx + dy * dy;
    }

    private RegionCenterVO toCenter(AdministrativeRegion region) {
        RegionCenterVO centerVO = new RegionCenterVO();
        centerVO.setLongitude(region.getCenterLongitude());
        centerVO.setLatitude(region.getCenterLatitude());
        return centerVO;
    }

    private MysqlTownSnapshot loadMysqlSnapshot(Long regionId) {
        List<RedSite> sites = loadDirectSites(regionId);
        List<MemorialHall> memorials = loadDirectMemorials(regionId);
        List<HistoricalEvent> directEvents = loadDirectEvents(regionId);

        Map<Long, RedSite> siteMap = toSiteMap(sites);
        Map<Long, MemorialHall> memorialMap = toMemorialMap(memorials);
        Map<Long, HistoricalEvent> eventMap = new LinkedHashMap<>();
        directEvents.forEach(event -> eventMap.put(event.getEventId(), event));

        List<SiteEventRel> siteEventRels = loadSiteEventRelations(siteMap.keySet());
        Set<Long> relatedEventIds = siteEventRels.stream()
                .map(SiteEventRel::getEventId)
                .filter(id -> id != null && !eventMap.containsKey(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!relatedEventIds.isEmpty()) {
            loadEventsByIds(relatedEventIds).forEach(event -> eventMap.put(event.getEventId(), event));
        }

        List<SiteHeroRel> siteHeroRels = loadSiteHeroRelations(siteMap.keySet());
        List<EventHeroRel> eventHeroRels = loadEventHeroRelations(eventMap.keySet());

        Set<Long> heroIds = new LinkedHashSet<>();
        heroIds.addAll(loadDirectHeroes(regionId).stream()
                .map(HeroPerson::getHeroId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        siteHeroRels.stream().map(SiteHeroRel::getHeroId).filter(id -> id != null).forEach(heroIds::add);
        eventHeroRels.stream().map(EventHeroRel::getHeroId).filter(id -> id != null).forEach(heroIds::add);
        List<HeroPerson> heroes = loadHeroesByIds(heroIds);
        Map<Long, HeroPerson> heroMap = heroes.stream()
                .collect(Collectors.toMap(HeroPerson::getHeroId, hero -> hero, (left, right) -> left, LinkedHashMap::new));

        Set<Long> storyIds = new LinkedHashSet<>(loadDirectStories(regionId).stream()
                .map(RedStory::getStoryId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        List<StoryEntityRel> storyEntityRels = loadStoryEntityRelations(siteMap.keySet(), memorialMap.keySet(), eventMap.keySet(), heroMap.keySet());
        storyEntityRels.stream()
                .map(StoryEntityRel::getStoryId)
                .filter(id -> id != null)
                .forEach(storyIds::add);
        List<RedStory> stories = loadStoriesByIds(regionId, storyIds);

        List<MapResourceMarkerVO> markers = buildMysqlMarkers(sites, memorials, eventMap, siteEventRels, siteMap);
        List<HeroSummaryVO> heroSummaries = buildMysqlHeroes(heroMap, siteHeroRels, eventHeroRels, siteMap, eventMap);
        List<StorySummaryVO> storySummaries = buildMysqlStories(stories, storyEntityRels, siteMap, memorialMap, eventMap, heroMap);
        List<EventSummaryVO> eventSummaries = buildMysqlEvents(eventMap.values(), siteEventRels, siteMap);

        return new MysqlTownSnapshot(markers, heroSummaries, storySummaries, eventSummaries);
    }

    private List<RedSite> loadDirectSites(Long regionId) {
        return redSiteService.list(new LambdaQueryWrapper<RedSite>()
                .eq(RedSite::getRegionId, regionId)
                .eq(RedSite::getReviewStatus, ReviewStatus.APPROVED)
                .eq(RedSite::getActive, true)
                .orderByAsc(RedSite::getSiteName));
    }

    private List<MemorialHall> loadDirectMemorials(Long regionId) {
        return memorialHallService.list(new LambdaQueryWrapper<MemorialHall>()
                .eq(MemorialHall::getRegionId, regionId)
                .eq(MemorialHall::getReviewStatus, ReviewStatus.APPROVED)
                .eq(MemorialHall::getActive, true)
                .orderByAsc(MemorialHall::getMemorialName));
    }

    private List<HistoricalEvent> loadDirectEvents(Long regionId) {
        return historicalEventService.list(new LambdaQueryWrapper<HistoricalEvent>()
                .eq(HistoricalEvent::getPrimaryRegionId, regionId)
                .eq(HistoricalEvent::getReviewStatus, ReviewStatus.APPROVED)
                .eq(HistoricalEvent::getActive, true)
                .orderByAsc(HistoricalEvent::getEventName));
    }

    private List<HeroPerson> loadDirectHeroes(Long regionId) {
        return heroPersonService.list(new LambdaQueryWrapper<HeroPerson>()
                .eq(HeroPerson::getNativePlaceRegionId, regionId)
                .eq(HeroPerson::getReviewStatus, ReviewStatus.APPROVED)
                .eq(HeroPerson::getActive, true)
                .orderByAsc(HeroPerson::getHeroName));
    }

    private List<RedStory> loadDirectStories(Long regionId) {
        return redStoryService.list(new LambdaQueryWrapper<RedStory>()
                .eq(RedStory::getRelatedRegionId, regionId)
                .eq(RedStory::getReviewStatus, ReviewStatus.APPROVED)
                .eq(RedStory::getActive, true)
                .orderByAsc(RedStory::getStoryTitle));
    }

    private List<HistoricalEvent> loadEventsByIds(Set<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Collections.emptyList();
        }
        return historicalEventService.list(new LambdaQueryWrapper<HistoricalEvent>()
                .in(HistoricalEvent::getEventId, eventIds)
                .eq(HistoricalEvent::getReviewStatus, ReviewStatus.APPROVED)
                .eq(HistoricalEvent::getActive, true)
                .orderByAsc(HistoricalEvent::getEventName));
    }

    private List<HeroPerson> loadHeroesByIds(Set<Long> heroIds) {
        if (heroIds.isEmpty()) {
            return Collections.emptyList();
        }
        return heroPersonService.list(new LambdaQueryWrapper<HeroPerson>()
                .in(HeroPerson::getHeroId, heroIds)
                .eq(HeroPerson::getReviewStatus, ReviewStatus.APPROVED)
                .eq(HeroPerson::getActive, true)
                .orderByAsc(HeroPerson::getHeroName));
    }

    private List<RedStory> loadStoriesByIds(Long regionId, Set<Long> storyIds) {
        if (storyIds.isEmpty()) {
            return Collections.emptyList();
        }
        return redStoryService.list(new LambdaQueryWrapper<RedStory>()
                .and(wrapper -> wrapper
                        .eq(RedStory::getRelatedRegionId, regionId)
                        .or()
                        .in(RedStory::getStoryId, storyIds))
                .eq(RedStory::getReviewStatus, ReviewStatus.APPROVED)
                .eq(RedStory::getActive, true)
                .orderByAsc(RedStory::getStoryTitle));
    }

    private List<MapResourceMarkerVO> loadBasicMysqlMarkers(Long regionId) {
        List<MapResourceMarkerVO> markers = new ArrayList<>();

        loadDirectSites(regionId).forEach(site -> markers.add(toSiteMarker(site)));
        loadDirectMemorials(regionId).forEach(memorial -> markers.add(toMemorialMarker(memorial)));
        loadDirectEvents(regionId).forEach(event -> markers.add(toEventMarker(event, Collections.emptySet())));

        return markers;
    }

    private List<HeroSummaryVO> loadBasicMysqlHeroes(Long regionId) {
        return loadDirectHeroes(regionId).stream()
                .map(hero -> {
                    HeroSummaryVO vo = new HeroSummaryVO();
                    vo.setHeroId(hero.getHeroId());
                    vo.setHeroName(hero.getHeroName());
                    vo.setNativePlaceText(hero.getNativePlaceText());
                    vo.setProfileSummary(hero.getProfileSummary());
                    vo.setMainDeeds(hero.getMainDeeds());
                    vo.setRelatedResourceNames(Collections.emptyList());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private List<StorySummaryVO> loadBasicMysqlStories(Long regionId) {
        return loadDirectStories(regionId).stream()
                .map(story -> {
                    StorySummaryVO vo = new StorySummaryVO();
                    vo.setStoryId(story.getStoryId());
                    vo.setStoryTitle(story.getStoryTitle());
                    vo.setAgeGroup(story.getAgeGroup() == null ? null : story.getAgeGroup().name().toLowerCase());
                    vo.setSummary(story.getSummary());
                    vo.setRelatedEntityNames(Collections.emptyList());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private List<EventSummaryVO> loadBasicMysqlEvents(Long regionId) {
        return loadDirectEvents(regionId).stream()
                .map(event -> {
                    EventSummaryVO vo = new EventSummaryVO();
                    vo.setEventId(event.getEventId());
                    vo.setEventName(event.getEventName());
                    vo.setEventTimeText(event.getEventTimeText());
                    vo.setSummary(firstNonBlank(event.getResultImpact(), event.getHistoricalSignificance(), event.getEventProcess()));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private void fillBasicFallback(TownMapDetailVO detailVO, Long regionId) {
        detailVO.setMarkers(loadBasicMysqlMarkers(regionId));
        detailVO.setHeroes(loadBasicMysqlHeroes(regionId));
        detailVO.setStories(loadBasicMysqlStories(regionId));
        detailVO.setEvents(loadBasicMysqlEvents(regionId));
    }

    private List<SiteEventRel> loadSiteEventRelations(Set<Long> siteIds) {
        if (siteIds.isEmpty()) {
            return Collections.emptyList();
        }
        return siteEventRelMapper.selectList(new LambdaQueryWrapper<SiteEventRel>()
                .in(SiteEventRel::getSiteId, siteIds)
                .orderByDesc(SiteEventRel::getImportanceLevel)
                .orderByAsc(SiteEventRel::getRelId));
    }

    private List<SiteHeroRel> loadSiteHeroRelations(Set<Long> siteIds) {
        if (siteIds.isEmpty()) {
            return Collections.emptyList();
        }
        return siteHeroRelMapper.selectList(new LambdaQueryWrapper<SiteHeroRel>()
                .in(SiteHeroRel::getSiteId, siteIds)
                .orderByDesc(SiteHeroRel::getImportanceLevel)
                .orderByAsc(SiteHeroRel::getRelId));
    }

    private List<EventHeroRel> loadEventHeroRelations(Set<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Collections.emptyList();
        }
        return eventHeroRelMapper.selectList(new LambdaQueryWrapper<EventHeroRel>()
                .in(EventHeroRel::getEventId, eventIds)
                .orderByAsc(EventHeroRel::getRelId));
    }

    private List<StoryEntityRel> loadStoryEntityRelations(Set<Long> siteIds,
                                                          Set<Long> memorialIds,
                                                          Set<Long> eventIds,
                                                          Set<Long> heroIds) {
        List<StoryEntityRel> relations = new ArrayList<>();
        addStoryEntityRelations(relations, EntityType.SITE, siteIds);
        addStoryEntityRelations(relations, EntityType.MEMORIAL, memorialIds);
        addStoryEntityRelations(relations, EntityType.EVENT, eventIds);
        addStoryEntityRelations(relations, EntityType.HERO, heroIds);
        return relations;
    }

    private void addStoryEntityRelations(List<StoryEntityRel> target, EntityType entityType, Set<Long> entityIds) {
        if (entityIds.isEmpty()) {
            return;
        }
        target.addAll(storyEntityRelMapper.selectList(new LambdaQueryWrapper<StoryEntityRel>()
                .eq(StoryEntityRel::getEntityType, entityType)
                .in(StoryEntityRel::getEntityId, entityIds)
                .orderByAsc(StoryEntityRel::getRelId)));
    }

    private List<MapResourceMarkerVO> buildMysqlMarkers(List<RedSite> sites,
                                                        List<MemorialHall> memorials,
                                                        Map<Long, HistoricalEvent> eventMap,
                                                        List<SiteEventRel> siteEventRels,
                                                        Map<Long, RedSite> siteMap) {
        Map<Long, Set<String>> eventRelatedSiteNames = new LinkedHashMap<>();
        for (SiteEventRel relation : siteEventRels) {
            if (relation.getEventId() == null || relation.getSiteId() == null) {
                continue;
            }
            RedSite site = siteMap.get(relation.getSiteId());
            if (site == null || site.getSiteName() == null || site.getSiteName().isBlank()) {
                continue;
            }
            eventRelatedSiteNames.computeIfAbsent(relation.getEventId(), ignored -> new LinkedHashSet<>())
                    .add(site.getSiteName());
        }

        List<MapResourceMarkerVO> markers = new ArrayList<>();
        sites.forEach(site -> markers.add(toSiteMarker(site)));
        memorials.forEach(memorial -> markers.add(toMemorialMarker(memorial)));
        eventMap.values().forEach(event -> markers.add(toEventMarker(event, eventRelatedSiteNames.get(event.getEventId()))));
        return markers;
    }

    private List<HeroSummaryVO> buildMysqlHeroes(Map<Long, HeroPerson> heroMap,
                                                 List<SiteHeroRel> siteHeroRels,
                                                 List<EventHeroRel> eventHeroRels,
                                                 Map<Long, RedSite> siteMap,
                                                 Map<Long, HistoricalEvent> eventMap) {
        Map<Long, Set<String>> heroRelatedNames = new LinkedHashMap<>();

        for (SiteHeroRel relation : siteHeroRels) {
            if (relation.getHeroId() == null || relation.getSiteId() == null) {
                continue;
            }
            RedSite site = siteMap.get(relation.getSiteId());
            if (site == null || site.getSiteName() == null || site.getSiteName().isBlank()) {
                continue;
            }
            heroRelatedNames.computeIfAbsent(relation.getHeroId(), ignored -> new LinkedHashSet<>())
                    .add(site.getSiteName());
        }

        for (EventHeroRel relation : eventHeroRels) {
            if (relation.getHeroId() == null || relation.getEventId() == null) {
                continue;
            }
            HistoricalEvent event = eventMap.get(relation.getEventId());
            if (event == null || event.getEventName() == null || event.getEventName().isBlank()) {
                continue;
            }
            heroRelatedNames.computeIfAbsent(relation.getHeroId(), ignored -> new LinkedHashSet<>())
                    .add(event.getEventName());
        }

        return heroMap.values().stream()
                .sorted(Comparator.comparing(HeroPerson::getHeroName, Comparator.nullsLast(String::compareTo)))
                .map(hero -> {
                    HeroSummaryVO vo = new HeroSummaryVO();
                    vo.setHeroId(hero.getHeroId());
                    vo.setHeroName(hero.getHeroName());
                    vo.setNativePlaceText(hero.getNativePlaceText());
                    vo.setProfileSummary(hero.getProfileSummary());
                    vo.setMainDeeds(hero.getMainDeeds());
                    vo.setRelatedResourceNames(new ArrayList<>(heroRelatedNames.getOrDefault(hero.getHeroId(), Collections.emptySet())));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private List<StorySummaryVO> buildMysqlStories(List<RedStory> stories,
                                                   List<StoryEntityRel> storyEntityRels,
                                                   Map<Long, RedSite> siteMap,
                                                   Map<Long, MemorialHall> memorialMap,
                                                   Map<Long, HistoricalEvent> eventMap,
                                                   Map<Long, HeroPerson> heroMap) {
        Map<Long, Set<String>> storyRelatedNames = new LinkedHashMap<>();

        for (StoryEntityRel relation : storyEntityRels) {
            if (relation.getStoryId() == null || relation.getEntityType() == null || relation.getEntityId() == null) {
                continue;
            }
            String entityName = resolveEntityName(relation.getEntityType(), relation.getEntityId(), siteMap, memorialMap, eventMap, heroMap);
            if (entityName == null || entityName.isBlank()) {
                continue;
            }
            storyRelatedNames.computeIfAbsent(relation.getStoryId(), ignored -> new LinkedHashSet<>())
                    .add(entityName);
        }

        return stories.stream()
                .sorted(Comparator.comparing(RedStory::getStoryTitle, Comparator.nullsLast(String::compareTo)))
                .map(story -> {
                    StorySummaryVO vo = new StorySummaryVO();
                    vo.setStoryId(story.getStoryId());
                    vo.setStoryTitle(story.getStoryTitle());
                    vo.setAgeGroup(story.getAgeGroup() == null ? null : story.getAgeGroup().name().toLowerCase());
                    vo.setSummary(story.getSummary());
                    vo.setRelatedEntityNames(new ArrayList<>(storyRelatedNames.getOrDefault(story.getStoryId(), Collections.emptySet())));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private List<EventSummaryVO> buildMysqlEvents(Iterable<HistoricalEvent> events,
                                                  List<SiteEventRel> siteEventRels,
                                                  Map<Long, RedSite> siteMap) {
        Map<Long, Set<String>> eventRelatedSiteNames = new LinkedHashMap<>();
        for (SiteEventRel relation : siteEventRels) {
            if (relation.getEventId() == null || relation.getSiteId() == null) {
                continue;
            }
            RedSite site = siteMap.get(relation.getSiteId());
            if (site == null || site.getSiteName() == null || site.getSiteName().isBlank()) {
                continue;
            }
            eventRelatedSiteNames.computeIfAbsent(relation.getEventId(), ignored -> new LinkedHashSet<>())
                    .add(site.getSiteName());
        }

        List<EventSummaryVO> summaries = new ArrayList<>();
        for (HistoricalEvent event : events) {
            EventSummaryVO vo = new EventSummaryVO();
            vo.setEventId(event.getEventId());
            vo.setEventName(event.getEventName());
            vo.setEventTimeText(event.getEventTimeText());
            vo.setSummary(firstNonBlank(
                    event.getResultImpact(),
                    event.getHistoricalSignificance(),
                    event.getEventProcess(),
                    joinNames(eventRelatedSiteNames.get(event.getEventId()), "关联遗址：")));
            summaries.add(vo);
        }
        summaries.sort(Comparator.comparing(EventSummaryVO::getEventName, Comparator.nullsLast(String::compareTo)));
        return summaries;
    }

    private MapResourceMarkerVO toSiteMarker(RedSite site) {
        MapResourceMarkerVO marker = new MapResourceMarkerVO();
        marker.setId(site.getSiteId());
        marker.setType("site");
        marker.setName(site.getSiteName());
        marker.setLongitude(site.getLongitude());
        marker.setLatitude(site.getLatitude());
        marker.setAddress(site.getAddress());
        marker.setSummary(firstNonBlank(site.getIntro(), site.getHistoricalBackground()));
        marker.setRelationHint(site.getProtectionLevel());
        return marker;
    }

    private MapResourceMarkerVO toMemorialMarker(MemorialHall memorial) {
        MapResourceMarkerVO marker = new MapResourceMarkerVO();
        marker.setId(memorial.getMemorialId());
        marker.setType("memorial");
        marker.setName(memorial.getMemorialName());
        marker.setLongitude(memorial.getLongitude());
        marker.setLatitude(memorial.getLatitude());
        marker.setAddress(memorial.getAddress());
        marker.setSummary(firstNonBlank(memorial.getIntro(), memorial.getExhibitionContent()));
        marker.setRelationHint(memorial.getOpeningTimeDesc());
        return marker;
    }

    private MapResourceMarkerVO toEventMarker(HistoricalEvent event, Set<String> relatedSiteNames) {
        MapResourceMarkerVO marker = new MapResourceMarkerVO();
        marker.setId(event.getEventId());
        marker.setType("event");
        marker.setName(event.getEventName());
        marker.setLongitude(event.getLongitude());
        marker.setLatitude(event.getLatitude());
        marker.setSummary(firstNonBlank(
                event.getResultImpact(),
                event.getHistoricalSignificance(),
                event.getEventProcess(),
                joinNames(relatedSiteNames, "关联遗址：")));
        marker.setRelationHint(firstNonBlank(event.getEventTimeText(), joinNames(relatedSiteNames, "关联遗址：")));
        return marker;
    }

    private String resolveEntityName(EntityType entityType,
                                     Long entityId,
                                     Map<Long, RedSite> siteMap,
                                     Map<Long, MemorialHall> memorialMap,
                                     Map<Long, HistoricalEvent> eventMap,
                                     Map<Long, HeroPerson> heroMap) {
        if (entityType == EntityType.SITE) {
            RedSite site = siteMap.get(entityId);
            return site == null ? null : site.getSiteName();
        }
        if (entityType == EntityType.MEMORIAL) {
            MemorialHall memorial = memorialMap.get(entityId);
            return memorial == null ? null : memorial.getMemorialName();
        }
        if (entityType == EntityType.EVENT) {
            HistoricalEvent event = eventMap.get(entityId);
            return event == null ? null : event.getEventName();
        }
        if (entityType == EntityType.HERO) {
            HeroPerson hero = heroMap.get(entityId);
            return hero == null ? null : hero.getHeroName();
        }
        return null;
    }

    private String joinNames(Set<String> names, String prefix) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        return prefix + String.join("、", names);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<String> buildSuggestedQuestions(String regionName) {
        Set<String> questions = new LinkedHashSet<>();
        questions.add(regionName + "有哪些代表性的红色文化资源？");
        questions.add(regionName + "最值得重点讲解的英雄人物是谁？");
        questions.add(regionName + "这里的革命故事可以如何给学生讲解？");
        questions.add(regionName + "有哪些遗址、事件和人物之间的关联值得在地图上重点展示？");
        return new ArrayList<>(questions);
    }

    private List<AdministrativeRegion> loadAllRegions() {
        return administrativeRegionService.list(new LambdaQueryWrapper<AdministrativeRegion>()
                .orderByAsc(AdministrativeRegion::getRegionLevel)
                .orderByAsc(AdministrativeRegion::getRegionName));
    }

    private RegionLevel parseRegionLevel(String regionLevel) {
        if (regionLevel == null || regionLevel.isBlank()) {
            return null;
        }
        for (RegionLevel value : RegionLevel.values()) {
            if (value.name().equalsIgnoreCase(regionLevel) || value.getValue().equalsIgnoreCase(regionLevel)) {
                return value;
            }
        }
        return null;
    }

    private Map<Long, RedSite> toSiteMap(List<RedSite> sites) {
        return sites.stream()
                .filter(site -> site.getSiteId() != null)
                .collect(Collectors.toMap(RedSite::getSiteId, site -> site, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, MemorialHall> toMemorialMap(List<MemorialHall> memorials) {
        return memorials.stream()
                .filter(memorial -> memorial.getMemorialId() != null)
                .collect(Collectors.toMap(MemorialHall::getMemorialId, memorial -> memorial, (left, right) -> left, LinkedHashMap::new));
    }

    private static final class MysqlTownSnapshot {

        private final List<MapResourceMarkerVO> markers;
        private final List<HeroSummaryVO> heroes;
        private final List<StorySummaryVO> stories;
        private final List<EventSummaryVO> events;

        private MysqlTownSnapshot(List<MapResourceMarkerVO> markers,
                                  List<HeroSummaryVO> heroes,
                                  List<StorySummaryVO> stories,
                                  List<EventSummaryVO> events) {
            this.markers = markers;
            this.heroes = heroes;
            this.stories = stories;
            this.events = events;
        }

        private List<MapResourceMarkerVO> markers() {
            return markers;
        }

        private List<HeroSummaryVO> heroes() {
            return heroes;
        }

        private List<StorySummaryVO> stories() {
            return stories;
        }

        private List<EventSummaryVO> events() {
            return events;
        }
    }
}
