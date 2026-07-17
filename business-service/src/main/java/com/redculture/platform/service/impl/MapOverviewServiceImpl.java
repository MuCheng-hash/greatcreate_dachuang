package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.entity.RedStory;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.service.AdministrativeRegionService;
import com.redculture.platform.service.HistoricalEventService;
import com.redculture.platform.service.MapOverviewService;
import com.redculture.platform.service.MemorialHallService;
import com.redculture.platform.service.RedSiteService;
import com.redculture.platform.service.RedStoryService;
import com.redculture.platform.vo.MapOverviewVO;
import com.redculture.platform.vo.NearbyResourceItemVO;
import com.redculture.platform.vo.NearbyResourceVO;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class MapOverviewServiceImpl implements MapOverviewService {

    private final AdministrativeRegionService administrativeRegionService;
    private final RedSiteService redSiteService;
    private final MemorialHallService memorialHallService;
    private final HistoricalEventService historicalEventService;
    private final RedStoryService redStoryService;

    public MapOverviewServiceImpl(AdministrativeRegionService administrativeRegionService,
                                  RedSiteService redSiteService,
                                  MemorialHallService memorialHallService,
                                  HistoricalEventService historicalEventService,
                                  RedStoryService redStoryService) {
        this.administrativeRegionService = administrativeRegionService;
        this.redSiteService = redSiteService;
        this.memorialHallService = memorialHallService;
        this.historicalEventService = historicalEventService;
        this.redStoryService = redStoryService;
    }

    @Override
    public MapOverviewVO getOverviewByRegionId(Long regionId) {
        AdministrativeRegion region = administrativeRegionService.getById(regionId);
        if (region == null) {
            return null;
        }

        List<Long> includedRegionIds = collectRegionIdsRecursively(regionId);

        List<RedSite> sites = redSiteService.list(new LambdaQueryWrapper<RedSite>()
                .in(RedSite::getRegionId, includedRegionIds)
                .eq(RedSite::getReviewStatus, ReviewStatus.APPROVED)
                .eq(RedSite::getActive, true)
                .orderByAsc(RedSite::getSiteName));

        List<MemorialHall> memorials = memorialHallService.list(new LambdaQueryWrapper<MemorialHall>()
                .in(MemorialHall::getRegionId, includedRegionIds)
                .eq(MemorialHall::getReviewStatus, ReviewStatus.APPROVED)
                .eq(MemorialHall::getActive, true)
                .orderByAsc(MemorialHall::getMemorialName));

        List<HistoricalEvent> events = historicalEventService.list(new LambdaQueryWrapper<HistoricalEvent>()
                .in(HistoricalEvent::getPrimaryRegionId, includedRegionIds)
                .eq(HistoricalEvent::getReviewStatus, ReviewStatus.APPROVED)
                .eq(HistoricalEvent::getActive, true)
                .orderByDesc(HistoricalEvent::getStartDate));

        List<RedStory> stories = redStoryService.list(new LambdaQueryWrapper<RedStory>()
                .in(RedStory::getRelatedRegionId, includedRegionIds)
                .eq(RedStory::getReviewStatus, ReviewStatus.APPROVED)
                .eq(RedStory::getActive, true)
                .orderByDesc(RedStory::getCreatedAt));

        MapOverviewVO vo = new MapOverviewVO();
        vo.setRegion(region);
        vo.setIncludedRegionIds(includedRegionIds);
        vo.setSites(defaultList(sites));
        vo.setMemorials(defaultList(memorials));
        vo.setEvents(defaultList(events));
        vo.setStories(defaultList(stories));
        vo.setSiteCount(vo.getSites().size());
        vo.setMemorialCount(vo.getMemorials().size());
        vo.setEventCount(vo.getEvents().size());
        vo.setStoryCount(vo.getStories().size());
        return vo;
    }

    @Override
    public NearbyResourceVO getNearbyResources(BigDecimal longitude, BigDecimal latitude, Double radiusKm, Integer limit) {
        double effectiveRadiusKm = radiusKm == null || radiusKm <= 0 ? 20D : radiusKm;
        int effectiveLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);

        List<NearbyResourceItemVO> siteItems = redSiteService.list(new LambdaQueryWrapper<RedSite>()
                        .isNotNull(RedSite::getLongitude)
                        .isNotNull(RedSite::getLatitude)
                        .eq(RedSite::getReviewStatus, ReviewStatus.APPROVED)
                        .eq(RedSite::getActive, true))
                .stream()
                .map(site -> toNearbySiteItem(site, longitude, latitude))
                .filter(item -> item.getDistanceKm() <= effectiveRadiusKm)
                .collect(Collectors.toList());

        List<NearbyResourceItemVO> memorialItems = memorialHallService.list(new LambdaQueryWrapper<MemorialHall>()
                        .isNotNull(MemorialHall::getLongitude)
                        .isNotNull(MemorialHall::getLatitude)
                        .eq(MemorialHall::getReviewStatus, ReviewStatus.APPROVED)
                        .eq(MemorialHall::getActive, true))
                .stream()
                .map(memorial -> toNearbyMemorialItem(memorial, longitude, latitude))
                .filter(item -> item.getDistanceKm() <= effectiveRadiusKm)
                .collect(Collectors.toList());

        List<NearbyResourceItemVO> resources = new ArrayList<>();
        resources.addAll(siteItems);
        resources.addAll(memorialItems);
        resources = resources.stream()
                .sorted(Comparator.comparing(NearbyResourceItemVO::getDistanceKm))
                .limit(effectiveLimit)
                .collect(Collectors.toList());

        NearbyResourceVO vo = new NearbyResourceVO();
        vo.setCurrentLongitude(longitude);
        vo.setCurrentLatitude(latitude);
        vo.setRadiusKm(effectiveRadiusKm);
        vo.setTotalCount(resources.size());
        vo.setResources(resources);
        return vo;
    }

    private <T> List<T> defaultList(List<T> data) {
        return data == null ? Collections.emptyList() : data;
    }

    private List<Long> collectRegionIdsRecursively(Long rootRegionId) {
        Set<Long> regionIds = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(rootRegionId);

        while (!queue.isEmpty()) {
            Long currentRegionId = queue.poll();
            if (!regionIds.add(currentRegionId)) {
                continue;
            }

            List<AdministrativeRegion> children = administrativeRegionService.list(
                    new LambdaQueryWrapper<AdministrativeRegion>()
                            .eq(AdministrativeRegion::getParentRegionId, currentRegionId)
                            .select(AdministrativeRegion::getRegionId)
            );

            for (AdministrativeRegion child : children) {
                if (child.getRegionId() != null) {
                    queue.add(child.getRegionId());
                }
            }
        }

        return new ArrayList<>(regionIds);
    }

    private NearbyResourceItemVO toNearbySiteItem(RedSite site, BigDecimal longitude, BigDecimal latitude) {
        NearbyResourceItemVO item = new NearbyResourceItemVO();
        item.setResourceType("site");
        item.setResourceId(site.getSiteId());
        item.setResourceName(site.getSiteName());
        item.setRegionId(site.getRegionId());
        item.setAddress(site.getAddress());
        item.setLongitude(site.getLongitude());
        item.setLatitude(site.getLatitude());
        item.setOpeningTimeDesc(site.getOpeningTimeDesc());
        item.setDistanceKm(calculateDistanceKm(latitude.doubleValue(), longitude.doubleValue(),
                site.getLatitude().doubleValue(), site.getLongitude().doubleValue()));
        return item;
    }

    private NearbyResourceItemVO toNearbyMemorialItem(MemorialHall memorial, BigDecimal longitude, BigDecimal latitude) {
        NearbyResourceItemVO item = new NearbyResourceItemVO();
        item.setResourceType("memorial");
        item.setResourceId(memorial.getMemorialId());
        item.setResourceName(memorial.getMemorialName());
        item.setRegionId(memorial.getRegionId());
        item.setAddress(memorial.getAddress());
        item.setLongitude(memorial.getLongitude());
        item.setLatitude(memorial.getLatitude());
        item.setOpeningTimeDesc(memorial.getOpeningTimeDesc());
        item.setDistanceKm(calculateDistanceKm(latitude.doubleValue(), longitude.doubleValue(),
                memorial.getLatitude().doubleValue(), memorial.getLongitude().doubleValue()));
        return item;
    }

    private Double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371.0D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = earthRadius * c;
        return BigDecimal.valueOf(distance).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
