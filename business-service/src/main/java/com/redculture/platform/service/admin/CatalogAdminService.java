package com.redculture.platform.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.entity.*;
import com.redculture.platform.enums.*;
import com.redculture.platform.mapper.*;
import com.redculture.platform.vo.admin.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class CatalogAdminService {

    private final LocalEduResourceMapper resourceMapper;
    private final RedSiteMapper siteMapper;
    private final MemorialHallMapper memorialMapper;
    private final HeroPersonMapper heroMapper;
    private final HistoricalEventMapper eventMapper;
    private final RedStoryMapper storyMapper;
    private final ResourceMediaMapper mediaMapper;
    private final EntitySourceRelMapper sourceRelMapper;
    private final SiteEventRelMapper siteEventRelMapper;
    private final SiteHeroRelMapper siteHeroRelMapper;
    private final EventHeroRelMapper eventHeroRelMapper;
    private final MemorialSiteRelMapper memorialSiteRelMapper;
    private final MemorialHeroRelMapper memorialHeroRelMapper;
    private final MemorialEventRelMapper memorialEventRelMapper;
    private final StoryEntityRelMapper storyEntityRelMapper;
    private final CatalogMediaStorageService mediaStorageService;

    public CatalogAdminService(LocalEduResourceMapper resourceMapper, RedSiteMapper siteMapper,
                               MemorialHallMapper memorialMapper, HeroPersonMapper heroMapper,
                               HistoricalEventMapper eventMapper, RedStoryMapper storyMapper,
                               ResourceMediaMapper mediaMapper, EntitySourceRelMapper sourceRelMapper,
                               SiteEventRelMapper siteEventRelMapper, SiteHeroRelMapper siteHeroRelMapper,
                               EventHeroRelMapper eventHeroRelMapper, MemorialSiteRelMapper memorialSiteRelMapper,
                               MemorialHeroRelMapper memorialHeroRelMapper, MemorialEventRelMapper memorialEventRelMapper,
                               StoryEntityRelMapper storyEntityRelMapper, CatalogMediaStorageService mediaStorageService) {
        this.resourceMapper = resourceMapper; this.siteMapper = siteMapper; this.memorialMapper = memorialMapper;
        this.heroMapper = heroMapper; this.eventMapper = eventMapper; this.storyMapper = storyMapper;
        this.mediaMapper = mediaMapper; this.sourceRelMapper = sourceRelMapper;
        this.siteEventRelMapper = siteEventRelMapper; this.siteHeroRelMapper = siteHeroRelMapper;
        this.eventHeroRelMapper = eventHeroRelMapper; this.memorialSiteRelMapper = memorialSiteRelMapper;
        this.memorialHeroRelMapper = memorialHeroRelMapper; this.memorialEventRelMapper = memorialEventRelMapper;
        this.storyEntityRelMapper = storyEntityRelMapper;
        this.mediaStorageService = mediaStorageService;
    }

    public PageResult<CatalogEntityVO> page(EntityType entityType, ResourceCategory resourceCategory, Long regionId,
                                            ReviewStatus reviewStatus, Boolean active, String keyword,
                                            Long pageNum, Long pageSize) {
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        List<CatalogEntityVO> all = new ArrayList<>();
        if (entityType == null) {
            for (EntityType type : EntityType.values()) all.addAll(list(type));
        } else all.addAll(list(entityType));
        String normalized = clean(keyword);
        if (normalized != null) all.removeIf(item -> !contains(item, normalized));
        if (resourceCategory != null) all.removeIf(item -> !resourceCategory.getValue().equals(item.getResourceCategory()));
        if (regionId != null) all.removeIf(item -> !regionId.equals(item.getRegionId()));
        if (reviewStatus != null) all.removeIf(item -> !reviewStatus.getValue().equals(item.getReviewStatus()));
        if (active != null) all.removeIf(item -> !active.equals(item.getActive()));
        all.forEach(this::attachCover);
        all.sort(Comparator.comparing(CatalogEntityVO::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        int from = (int) Math.min((current - 1) * size, all.size());
        int to = (int) Math.min(from + size, all.size());
        return PageResult.of(all.subList(from, to), all.size(), current, size);
    }

    public CatalogEntityVO detail(EntityType type, Long id) {
        CatalogEntityVO result = toVO(type, find(type, id));
        if (result == null) return null;
        result.setMedia(mediaMapper.selectList(new LambdaQueryWrapper<ResourceMedia>()
                .eq(ResourceMedia::getEntityType, type).eq(ResourceMedia::getEntityId, id)
                .orderByDesc(ResourceMedia::getPrimary).orderByAsc(ResourceMedia::getSortOrder)).stream().map(this::toMedia).toList());
        result.setSources(sourceRelMapper.selectList(new LambdaQueryWrapper<EntitySourceRel>()
                .eq(EntitySourceRel::getEntityType, type).eq(EntitySourceRel::getEntityId, id)).stream().map(this::toSource).toList());
        return result;
    }

    @Transactional
    public CatalogEntityVO create(CatalogEntityRequest request) {
        validateRequest(request);
        Long id = insert(request);
        replaceAttachments(request.getEntityType(), id, request.getMedia(), request.getSources());
        return detail(request.getEntityType(), id);
    }

    @Transactional
    public CatalogEntityVO update(EntityType type, Long id, CatalogEntityRequest request) {
        validateRequest(request);
        if (type != request.getEntityType()) throw new IllegalArgumentException("entityType cannot be changed");
        Object entity = require(type, id);
        boolean wasApproved = ReviewStatus.APPROVED == reviewStatus(entity);
        apply(type, entity, request);
        setReviewStatus(entity, wasApproved ? ReviewStatus.PENDING : reviewStatus(entity));
        update(type, entity);
        replaceAttachments(type, id, request.getMedia(), request.getSources());
        return detail(type, id);
    }

    @Transactional
    public CatalogEntityVO approve(EntityType type, Long id) {
        Object entity = require(type, id);
        setReviewStatus(entity, ReviewStatus.APPROVED);
        setActive(entity, true);
        update(type, entity);
        return detail(type, id);
    }

    @Transactional
    public CatalogEntityVO submitForReview(EntityType type, Long id) {
        Object entity = require(type, id);
        setReviewStatus(entity, ReviewStatus.PENDING);
        update(type, entity);
        return detail(type, id);
    }

    @Transactional
    public CatalogEntityVO deactivate(EntityType type, Long id) {
        Object entity = require(type, id);
        setActive(entity, false);
        update(type, entity);
        return detail(type, id);
    }

    @Transactional
    public CatalogMediaRequest uploadMedia(EntityType type, Long id, MultipartFile file) {
        Object entity = require(type, id);
        if (!Boolean.TRUE.equals(active(entity))) {
            throw new IllegalArgumentException("inactive entities cannot receive media");
        }
        CatalogMediaStorageService.StoredMedia stored = mediaStorageService.store(file);
        try {
            long count = mediaMapper.selectCount(new LambdaQueryWrapper<ResourceMedia>()
                    .eq(ResourceMedia::getEntityType, type).eq(ResourceMedia::getEntityId, id));
            ResourceMedia media = new ResourceMedia();
            media.setEntityType(type); media.setEntityId(id); media.setMediaType(MediaType.IMAGE);
            media.setMediaTitle(stored.originalTitle()); media.setMediaUrl(stored.publicUrl());
            media.setPrimary(count == 0); media.setSortOrder((int) count);
            mediaMapper.insert(media);
            return toMedia(media);
        } catch (RuntimeException exception) {
            mediaStorageService.deleteIfManaged(stored.publicUrl());
            throw exception;
        }
    }

    @Transactional
    public void deleteMedia(EntityType type, Long id, Long mediaId) {
        require(type, id);
        ResourceMedia media = mediaMapper.selectById(mediaId);
        if (media == null || media.getEntityType() != type || !id.equals(media.getEntityId())) {
            throw new IllegalArgumentException("catalog media not found");
        }
        mediaMapper.deleteById(mediaId);
        mediaStorageService.deleteIfManaged(media.getMediaUrl());
    }

    @Transactional
    public CatalogRelationVO createRelation(CatalogRelationRequest request) {
        if (request == null || request.getSourceType() == null || request.getTargetType() == null
                || request.getSourceId() == null || request.getTargetId() == null || !StringUtils.hasText(request.getRelationType())) {
            throw new IllegalArgumentException("relation source, target and type are required");
        }
        requirePublished(request.getSourceType(), request.getSourceId());
        requirePublished(request.getTargetType(), request.getTargetId());
        String relation = request.getRelationType().trim().toUpperCase(Locale.ROOT);
        CatalogRelationVO result = relationVO(request);
        if (request.getSourceType() == EntityType.SITE && request.getTargetType() == EntityType.EVENT) {
            SiteEventRel item = new SiteEventRel(); item.setSiteId(request.getSourceId()); item.setEventId(request.getTargetId());
            item.setRelationType(SiteEventRelationType.valueOf(relation)); item.setRemark(clean(request.getRemark())); siteEventRelMapper.insert(item);
            result.setRelationKind("site_event"); result.setRelationId(item.getRelId()); return result;
        }
        if (request.getSourceType() == EntityType.SITE && request.getTargetType() == EntityType.HERO) {
            SiteHeroRel item = new SiteHeroRel(); item.setSiteId(request.getSourceId()); item.setHeroId(request.getTargetId());
            item.setRelationType(SiteHeroRelationType.valueOf(relation)); item.setRemark(clean(request.getRemark())); siteHeroRelMapper.insert(item);
            result.setRelationKind("site_hero"); result.setRelationId(item.getRelId()); return result;
        }
        if (request.getSourceType() == EntityType.EVENT && request.getTargetType() == EntityType.HERO) {
            EventHeroRel item = new EventHeroRel(); item.setEventId(request.getSourceId()); item.setHeroId(request.getTargetId());
            item.setRelationType(EventHeroRelationType.valueOf(relation)); item.setContributionText(clean(request.getRemark())); eventHeroRelMapper.insert(item);
            result.setRelationKind("event_hero"); result.setRelationId(item.getRelId()); return result;
        }
        if (request.getSourceType() == EntityType.MEMORIAL && request.getTargetType() == EntityType.SITE) {
            MemorialSiteRel item = new MemorialSiteRel(); item.setMemorialId(request.getSourceId()); item.setSiteId(request.getTargetId());
            item.setRelationType(MemorialRelationType.valueOf(relation)); memorialSiteRelMapper.insert(item);
            result.setRelationKind("memorial_site"); result.setRelationId(item.getRelId()); return result;
        }
        if (request.getSourceType() == EntityType.MEMORIAL && request.getTargetType() == EntityType.HERO) {
            MemorialHeroRel item = new MemorialHeroRel(); item.setMemorialId(request.getSourceId()); item.setHeroId(request.getTargetId());
            item.setRelationType(MemorialRelationType.valueOf(relation)); memorialHeroRelMapper.insert(item);
            result.setRelationKind("memorial_hero"); result.setRelationId(item.getRelId()); return result;
        }
        if (request.getSourceType() == EntityType.MEMORIAL && request.getTargetType() == EntityType.EVENT) {
            MemorialEventRel item = new MemorialEventRel(); item.setMemorialId(request.getSourceId()); item.setEventId(request.getTargetId());
            item.setRelationType(MemorialRelationType.valueOf(relation)); memorialEventRelMapper.insert(item);
            result.setRelationKind("memorial_event"); result.setRelationId(item.getRelId()); return result;
        }
        if (request.getSourceType() == EntityType.STORY && List.of(EntityType.RESOURCE, EntityType.SITE, EntityType.MEMORIAL, EntityType.HERO, EntityType.EVENT).contains(request.getTargetType())) {
            StoryEntityRel item = new StoryEntityRel(); item.setStoryId(request.getSourceId()); item.setEntityType(request.getTargetType()); item.setEntityId(request.getTargetId());
            item.setRelationType(StoryEntityRelationType.valueOf(relation)); storyEntityRelMapper.insert(item);
            result.setRelationKind("story_entity"); result.setRelationId(item.getRelId()); return result;
        }
        throw new IllegalArgumentException("unsupported relation direction or type");
    }

    public CatalogRelationVO relation(String kind, Long relationId) {
        if (!StringUtils.hasText(kind) || relationId == null) return null;
        return switch (kind) {
            case "site_event" -> { SiteEventRel item=siteEventRelMapper.selectOne(new LambdaQueryWrapper<SiteEventRel>().select(SiteEventRel::getRelId,SiteEventRel::getSiteId,SiteEventRel::getEventId,SiteEventRel::getRelationType,SiteEventRel::getRemark).eq(SiteEventRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.SITE,item.getSiteId(),EntityType.EVENT,item.getEventId(),enumValue(item.getRelationType()),item.getRemark(),kind,relationId); }
            case "site_hero" -> { SiteHeroRel item=siteHeroRelMapper.selectOne(new LambdaQueryWrapper<SiteHeroRel>().select(SiteHeroRel::getRelId,SiteHeroRel::getSiteId,SiteHeroRel::getHeroId,SiteHeroRel::getRelationType,SiteHeroRel::getRemark).eq(SiteHeroRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.SITE,item.getSiteId(),EntityType.HERO,item.getHeroId(),enumValue(item.getRelationType()),item.getRemark(),kind,relationId); }
            case "event_hero" -> { EventHeroRel item=eventHeroRelMapper.selectOne(new LambdaQueryWrapper<EventHeroRel>().select(EventHeroRel::getRelId,EventHeroRel::getEventId,EventHeroRel::getHeroId,EventHeroRel::getRelationType,EventHeroRel::getContributionText).eq(EventHeroRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.EVENT,item.getEventId(),EntityType.HERO,item.getHeroId(),enumValue(item.getRelationType()),item.getContributionText(),kind,relationId); }
            case "memorial_site" -> { MemorialSiteRel item=memorialSiteRelMapper.selectOne(new LambdaQueryWrapper<MemorialSiteRel>().select(MemorialSiteRel::getRelId,MemorialSiteRel::getMemorialId,MemorialSiteRel::getSiteId,MemorialSiteRel::getRelationType).eq(MemorialSiteRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.MEMORIAL,item.getMemorialId(),EntityType.SITE,item.getSiteId(),enumValue(item.getRelationType()),null,kind,relationId); }
            case "memorial_hero" -> { MemorialHeroRel item=memorialHeroRelMapper.selectOne(new LambdaQueryWrapper<MemorialHeroRel>().select(MemorialHeroRel::getRelId,MemorialHeroRel::getMemorialId,MemorialHeroRel::getHeroId,MemorialHeroRel::getRelationType).eq(MemorialHeroRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.MEMORIAL,item.getMemorialId(),EntityType.HERO,item.getHeroId(),enumValue(item.getRelationType()),null,kind,relationId); }
            case "memorial_event" -> { MemorialEventRel item=memorialEventRelMapper.selectOne(new LambdaQueryWrapper<MemorialEventRel>().select(MemorialEventRel::getRelId,MemorialEventRel::getMemorialId,MemorialEventRel::getEventId,MemorialEventRel::getRelationType).eq(MemorialEventRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.MEMORIAL,item.getMemorialId(),EntityType.EVENT,item.getEventId(),enumValue(item.getRelationType()),null,kind,relationId); }
            case "story_entity" -> { StoryEntityRel item=storyEntityRelMapper.selectOne(new LambdaQueryWrapper<StoryEntityRel>().select(StoryEntityRel::getRelId,StoryEntityRel::getStoryId,StoryEntityRel::getEntityType,StoryEntityRel::getEntityId,StoryEntityRel::getRelationType).eq(StoryEntityRel::getRelId,relationId)); yield item==null?null:relationVO(EntityType.STORY,item.getStoryId(),item.getEntityType(),item.getEntityId(),enumValue(item.getRelationType()),null,kind,relationId); }
            default -> null;
        };
    }

    public List<CatalogRelationVO> relations(EntityType entityType, Long entityId) {
        require(entityType, entityId);
        List<CatalogRelationVO> all = new ArrayList<>();
        siteEventRelMapper.selectList(new LambdaQueryWrapper<SiteEventRel>().select(SiteEventRel::getRelId,SiteEventRel::getSiteId,SiteEventRel::getEventId,SiteEventRel::getRelationType,SiteEventRel::getRemark)).forEach(item -> all.add(relationVO(EntityType.SITE, item.getSiteId(), EntityType.EVENT, item.getEventId(), enumValue(item.getRelationType()), item.getRemark(), "site_event", item.getRelId())));
        siteHeroRelMapper.selectList(new LambdaQueryWrapper<SiteHeroRel>().select(SiteHeroRel::getRelId,SiteHeroRel::getSiteId,SiteHeroRel::getHeroId,SiteHeroRel::getRelationType,SiteHeroRel::getRemark)).forEach(item -> all.add(relationVO(EntityType.SITE, item.getSiteId(), EntityType.HERO, item.getHeroId(), enumValue(item.getRelationType()), item.getRemark(), "site_hero", item.getRelId())));
        eventHeroRelMapper.selectList(new LambdaQueryWrapper<EventHeroRel>().select(EventHeroRel::getRelId,EventHeroRel::getEventId,EventHeroRel::getHeroId,EventHeroRel::getRelationType,EventHeroRel::getContributionText)).forEach(item -> all.add(relationVO(EntityType.EVENT, item.getEventId(), EntityType.HERO, item.getHeroId(), enumValue(item.getRelationType()), item.getContributionText(), "event_hero", item.getRelId())));
        memorialSiteRelMapper.selectList(new LambdaQueryWrapper<MemorialSiteRel>().select(MemorialSiteRel::getRelId,MemorialSiteRel::getMemorialId,MemorialSiteRel::getSiteId,MemorialSiteRel::getRelationType)).forEach(item -> all.add(relationVO(EntityType.MEMORIAL, item.getMemorialId(), EntityType.SITE, item.getSiteId(), enumValue(item.getRelationType()), null, "memorial_site", item.getRelId())));
        memorialHeroRelMapper.selectList(new LambdaQueryWrapper<MemorialHeroRel>().select(MemorialHeroRel::getRelId,MemorialHeroRel::getMemorialId,MemorialHeroRel::getHeroId,MemorialHeroRel::getRelationType)).forEach(item -> all.add(relationVO(EntityType.MEMORIAL, item.getMemorialId(), EntityType.HERO, item.getHeroId(), enumValue(item.getRelationType()), null, "memorial_hero", item.getRelId())));
        memorialEventRelMapper.selectList(new LambdaQueryWrapper<MemorialEventRel>().select(MemorialEventRel::getRelId,MemorialEventRel::getMemorialId,MemorialEventRel::getEventId,MemorialEventRel::getRelationType)).forEach(item -> all.add(relationVO(EntityType.MEMORIAL, item.getMemorialId(), EntityType.EVENT, item.getEventId(), enumValue(item.getRelationType()), null, "memorial_event", item.getRelId())));
        storyEntityRelMapper.selectList(new LambdaQueryWrapper<StoryEntityRel>().select(StoryEntityRel::getRelId,StoryEntityRel::getStoryId,StoryEntityRel::getEntityType,StoryEntityRel::getEntityId,StoryEntityRel::getRelationType)).forEach(item -> all.add(relationVO(EntityType.STORY, item.getStoryId(), item.getEntityType(), item.getEntityId(), enumValue(item.getRelationType()), null, "story_entity", item.getRelId())));
        return all.stream().filter(item -> (entityType.getValue().equals(item.getSourceType()) && entityId.equals(item.getSourceId()))
                || (entityType.getValue().equals(item.getTargetType()) && entityId.equals(item.getTargetId()))).toList();
    }

    @Transactional
    public CatalogRelationVO deleteRelation(String kind, Long relationId) {
        CatalogRelationVO result = relation(kind, relationId);
        if (result == null) {
            throw new IllegalArgumentException("catalog relation not found");
        }
        switch (kind) {
            case "site_event" -> siteEventRelMapper.deleteById(relationId);
            case "site_hero" -> siteHeroRelMapper.deleteById(relationId);
            case "event_hero" -> eventHeroRelMapper.deleteById(relationId);
            case "memorial_site" -> memorialSiteRelMapper.deleteById(relationId);
            case "memorial_hero" -> memorialHeroRelMapper.deleteById(relationId);
            case "memorial_event" -> memorialEventRelMapper.deleteById(relationId);
            case "story_entity" -> storyEntityRelMapper.deleteById(relationId);
            default -> throw new IllegalArgumentException("unsupported relation kind");
        }
        return result;
    }

    public List<CatalogRelationOptionVO> relationOptions() {
        List<CatalogRelationOptionVO> options = new ArrayList<>();
        addOptions(options, EntityType.SITE, EntityType.EVENT, SiteEventRelationType.values());
        addOptions(options, EntityType.SITE, EntityType.HERO, SiteHeroRelationType.values());
        addOptions(options, EntityType.EVENT, EntityType.HERO, EventHeroRelationType.values());
        addOptions(options, EntityType.MEMORIAL, EntityType.SITE, MemorialRelationType.values());
        addOptions(options, EntityType.MEMORIAL, EntityType.HERO, MemorialRelationType.values());
        addOptions(options, EntityType.MEMORIAL, EntityType.EVENT, MemorialRelationType.values());
        for (EntityType target : List.of(EntityType.RESOURCE, EntityType.SITE, EntityType.MEMORIAL, EntityType.HERO, EntityType.EVENT)) {
            addOptions(options, EntityType.STORY, target, StoryEntityRelationType.values());
        }
        return options;
    }

    private List<CatalogEntityVO> list(EntityType type) {
        return switch (type) {
            case RESOURCE -> resourceMapper.selectList(null).stream().map(item -> toVO(type, item)).toList();
            case SITE -> siteMapper.selectList(null).stream().map(item -> toVO(type, item)).toList();
            case MEMORIAL -> memorialMapper.selectList(null).stream().map(item -> toVO(type, item)).toList();
            case HERO -> heroMapper.selectList(null).stream().map(item -> toVO(type, item)).toList();
            case EVENT -> eventMapper.selectList(null).stream().map(item -> toVO(type, item)).toList();
            case STORY -> storyMapper.selectList(null).stream().map(item -> toVO(type, item)).toList();
            default -> List.of();
        };
    }

    private Long insert(CatalogEntityRequest request) {
        EntityType type = request.getEntityType();
        return switch (type) {
            case RESOURCE -> { LocalEduResource item = new LocalEduResource(); apply(type, item, request); item.setReviewStatus(ReviewStatus.DRAFT); item.setActive(true); resourceMapper.insert(item); yield item.getResourceId(); }
            case SITE -> { RedSite item = new RedSite(); apply(type, item, request); item.setReviewStatus(ReviewStatus.DRAFT); item.setActive(true); siteMapper.insert(item); yield item.getSiteId(); }
            case MEMORIAL -> { MemorialHall item = new MemorialHall(); apply(type, item, request); item.setReviewStatus(ReviewStatus.DRAFT); item.setActive(true); memorialMapper.insert(item); yield item.getMemorialId(); }
            case HERO -> { HeroPerson item = new HeroPerson(); apply(type, item, request); item.setReviewStatus(ReviewStatus.DRAFT); item.setActive(true); heroMapper.insert(item); yield item.getHeroId(); }
            case EVENT -> { HistoricalEvent item = new HistoricalEvent(); apply(type, item, request); item.setReviewStatus(ReviewStatus.DRAFT); item.setActive(true); eventMapper.insert(item); yield item.getEventId(); }
            case STORY -> { RedStory item = new RedStory(); apply(type, item, request); item.setReviewStatus(ReviewStatus.DRAFT); item.setActive(true); storyMapper.insert(item); yield item.getStoryId(); }
            default -> throw new IllegalArgumentException("unsupported entity type");
        };
    }

    private void apply(EntityType type, Object entity, CatalogEntityRequest request) {
        switch (type) {
            case RESOURCE -> { LocalEduResource item=(LocalEduResource) entity; item.setResourceCode(clean(request.getCode())); item.setResourceName(clean(request.getName())); item.setResourceAlias(clean(request.getAlias())); item.setResourceCategory(request.getResourceCategory() == null ? (item.getResourceCategory() == null ? ResourceCategory.OTHER : item.getResourceCategory()) : request.getResourceCategory()); item.setResourceSubcategory(clean(request.getResourceSubcategory())); item.setRegionId(request.getRegionId()); item.setAddress(clean(request.getAddress())); item.setLongitude(request.getLongitude()); item.setLatitude(request.getLatitude()); item.setOrganizationName(clean(request.getOrganizationName())); item.setContactPhone(clean(request.getContactPhone())); item.setOpeningTimeDesc(clean(request.getOpeningTimeDesc())); item.setReservationRequired(request.getReservationRequired()); item.setRecommendedVisitMinutes(request.getRecommendedVisitMinutes()); item.setIntro(clean(request.getSummary())); item.setEducationValue(clean(request.getDetail())); item.setActivitySuggestion(clean(request.getActivitySuggestion())); item.setTargetGrade(clean(request.getTargetGrade())); item.setSafetyNote(clean(request.getSafetyNote())); }
            case SITE -> { RedSite item=(RedSite) entity; item.setSiteCode(clean(request.getCode())); item.setSiteName(clean(request.getName())); item.setSiteAlias(clean(request.getAlias())); item.setRegionId(request.getRegionId()); item.setAddress(clean(request.getAddress())); item.setLongitude(request.getLongitude()); item.setLatitude(request.getLatitude()); item.setIntro(clean(request.getSummary())); item.setHistoricalBackground(clean(request.getDetail())); }
            case MEMORIAL -> { MemorialHall item=(MemorialHall) entity; item.setMemorialCode(clean(request.getCode())); item.setMemorialName(clean(request.getName())); item.setRegionId(request.getRegionId()); item.setAddress(clean(request.getAddress())); item.setLongitude(request.getLongitude()); item.setLatitude(request.getLatitude()); item.setIntro(clean(request.getSummary())); item.setExhibitionContent(clean(request.getDetail())); }
            case HERO -> { HeroPerson item=(HeroPerson) entity; item.setHeroCode(clean(request.getCode())); item.setHeroName(clean(request.getName())); item.setHeroAlias(clean(request.getAlias())); item.setNativePlaceRegionId(request.getRegionId()); item.setNativePlaceText(clean(request.getAddress())); item.setProfileSummary(clean(request.getSummary())); item.setMainDeeds(clean(request.getDetail())); }
            case EVENT -> { HistoricalEvent item=(HistoricalEvent) entity; item.setEventCode(clean(request.getCode())); item.setEventName(clean(request.getName())); item.setEventAlias(clean(request.getAlias())); item.setPrimaryRegionId(request.getRegionId()); item.setLongitude(request.getLongitude()); item.setLatitude(request.getLatitude()); item.setHistoricalSignificance(clean(request.getSummary())); item.setEventProcess(clean(request.getDetail())); }
            case STORY -> { RedStory item=(RedStory) entity; item.setStoryCode(clean(request.getCode())); item.setStoryTitle(clean(request.getName())); item.setRelatedRegionId(request.getRegionId()); item.setSummary(clean(request.getSummary())); item.setStoryContent(clean(request.getDetail())); }
            default -> throw new IllegalArgumentException("unsupported entity type");
        }
    }

    private Object find(EntityType type, Long id) {
        if (id == null || type == null) return null;
        return switch (type) { case RESOURCE -> resourceMapper.selectById(id); case SITE -> siteMapper.selectById(id); case MEMORIAL -> memorialMapper.selectById(id); case HERO -> heroMapper.selectById(id); case EVENT -> eventMapper.selectById(id); case STORY -> storyMapper.selectById(id); default -> null; };
    }

    private Object require(EntityType type, Long id) { Object value=find(type,id); if(value==null) throw new IllegalArgumentException("catalog entity not found"); return value; }
    private void requirePublished(EntityType type, Long id) { Object value=require(type,id); if(reviewStatus(value)!=ReviewStatus.APPROVED || !Boolean.TRUE.equals(active(value))) throw new IllegalArgumentException("relation endpoints must be approved and active"); }

    private void update(EntityType type, Object entity) { switch (type) { case RESOURCE -> resourceMapper.updateById((LocalEduResource)entity); case SITE -> siteMapper.updateById((RedSite)entity); case MEMORIAL -> memorialMapper.updateById((MemorialHall)entity); case HERO -> heroMapper.updateById((HeroPerson)entity); case EVENT -> eventMapper.updateById((HistoricalEvent)entity); case STORY -> storyMapper.updateById((RedStory)entity); default -> throw new IllegalArgumentException("unsupported entity type"); } }
    private ReviewStatus reviewStatus(Object value) { return switchEntity(value, LocalEduResource::getReviewStatus, RedSite::getReviewStatus, MemorialHall::getReviewStatus, HeroPerson::getReviewStatus, HistoricalEvent::getReviewStatus, RedStory::getReviewStatus); }
    private Boolean active(Object value) { return switchEntity(value, LocalEduResource::getActive, RedSite::getActive, MemorialHall::getActive, HeroPerson::getActive, HistoricalEvent::getActive, RedStory::getActive); }
    private void setReviewStatus(Object value, ReviewStatus status) { if(value instanceof LocalEduResource x)x.setReviewStatus(status); else if(value instanceof RedSite x)x.setReviewStatus(status); else if(value instanceof MemorialHall x)x.setReviewStatus(status); else if(value instanceof HeroPerson x)x.setReviewStatus(status); else if(value instanceof HistoricalEvent x)x.setReviewStatus(status); else if(value instanceof RedStory x)x.setReviewStatus(status); }
    private void setActive(Object value, boolean active) { if(value instanceof LocalEduResource x)x.setActive(active); else if(value instanceof RedSite x)x.setActive(active); else if(value instanceof MemorialHall x)x.setActive(active); else if(value instanceof HeroPerson x)x.setActive(active); else if(value instanceof HistoricalEvent x)x.setActive(active); else if(value instanceof RedStory x)x.setActive(active); }
    private <T> T switchEntity(Object value, java.util.function.Function<LocalEduResource,T> resource, java.util.function.Function<RedSite,T> site, java.util.function.Function<MemorialHall,T> memorial, java.util.function.Function<HeroPerson,T> hero, java.util.function.Function<HistoricalEvent,T> event, java.util.function.Function<RedStory,T> story) { if(value instanceof LocalEduResource x)return resource.apply(x); if(value instanceof RedSite x)return site.apply(x); if(value instanceof MemorialHall x)return memorial.apply(x); if(value instanceof HeroPerson x)return hero.apply(x); if(value instanceof HistoricalEvent x)return event.apply(x); if(value instanceof RedStory x)return story.apply(x); return null; }

    private CatalogEntityVO toVO(EntityType type, Object entity) {
        if(entity==null) return null; CatalogEntityVO vo=new CatalogEntityVO(); vo.setEntityType(type.getValue());
        if(entity instanceof LocalEduResource x){vo.setEntityId(x.getResourceId());vo.setCode(x.getResourceCode());vo.setName(x.getResourceName());vo.setAlias(x.getResourceAlias());vo.setRegionId(x.getRegionId());vo.setAddress(x.getAddress());vo.setLongitude(x.getLongitude());vo.setLatitude(x.getLatitude());vo.setSummary(x.getIntro());vo.setDetail(x.getEducationValue());vo.setTargetGrade(x.getTargetGrade());vo.setResourceCategory(enumValue(x.getResourceCategory()));vo.setResourceSubcategory(x.getResourceSubcategory());vo.setOrganizationName(x.getOrganizationName());vo.setContactPhone(x.getContactPhone());vo.setOpeningTimeDesc(x.getOpeningTimeDesc());vo.setReservationRequired(x.getReservationRequired());vo.setRecommendedVisitMinutes(x.getRecommendedVisitMinutes());vo.setActivitySuggestion(x.getActivitySuggestion());vo.setSafetyNote(x.getSafetyNote());vo.setReviewStatus(enumValue(x.getReviewStatus()));vo.setActive(x.getActive());vo.setCreatedAt(x.getCreatedAt());vo.setUpdatedAt(x.getUpdatedAt());}
        else if(entity instanceof RedSite x){vo.setEntityId(x.getSiteId());vo.setCode(x.getSiteCode());vo.setName(x.getSiteName());vo.setAlias(x.getSiteAlias());vo.setRegionId(x.getRegionId());vo.setAddress(x.getAddress());vo.setLongitude(x.getLongitude());vo.setLatitude(x.getLatitude());vo.setSummary(x.getIntro());vo.setDetail(x.getHistoricalBackground());vo.setReviewStatus(enumValue(x.getReviewStatus()));vo.setActive(x.getActive());vo.setCreatedAt(x.getCreatedAt());vo.setUpdatedAt(x.getUpdatedAt());}
        else if(entity instanceof MemorialHall x){vo.setEntityId(x.getMemorialId());vo.setCode(x.getMemorialCode());vo.setName(x.getMemorialName());vo.setRegionId(x.getRegionId());vo.setAddress(x.getAddress());vo.setLongitude(x.getLongitude());vo.setLatitude(x.getLatitude());vo.setSummary(x.getIntro());vo.setDetail(x.getExhibitionContent());vo.setReviewStatus(enumValue(x.getReviewStatus()));vo.setActive(x.getActive());vo.setCreatedAt(x.getCreatedAt());vo.setUpdatedAt(x.getUpdatedAt());}
        else if(entity instanceof HeroPerson x){vo.setEntityId(x.getHeroId());vo.setCode(x.getHeroCode());vo.setName(x.getHeroName());vo.setAlias(x.getHeroAlias());vo.setRegionId(x.getNativePlaceRegionId());vo.setAddress(x.getNativePlaceText());vo.setSummary(x.getProfileSummary());vo.setDetail(x.getMainDeeds());vo.setReviewStatus(enumValue(x.getReviewStatus()));vo.setActive(x.getActive());vo.setCreatedAt(x.getCreatedAt());vo.setUpdatedAt(x.getUpdatedAt());}
        else if(entity instanceof HistoricalEvent x){vo.setEntityId(x.getEventId());vo.setCode(x.getEventCode());vo.setName(x.getEventName());vo.setAlias(x.getEventAlias());vo.setRegionId(x.getPrimaryRegionId());vo.setLongitude(x.getLongitude());vo.setLatitude(x.getLatitude());vo.setSummary(x.getHistoricalSignificance());vo.setDetail(x.getEventProcess());vo.setReviewStatus(enumValue(x.getReviewStatus()));vo.setActive(x.getActive());vo.setCreatedAt(x.getCreatedAt());vo.setUpdatedAt(x.getUpdatedAt());}
        else if(entity instanceof RedStory x){vo.setEntityId(x.getStoryId());vo.setCode(x.getStoryCode());vo.setName(x.getStoryTitle());vo.setRegionId(x.getRelatedRegionId());vo.setSummary(x.getSummary());vo.setDetail(x.getStoryContent());vo.setReviewStatus(enumValue(x.getReviewStatus()));vo.setActive(x.getActive());vo.setCreatedAt(x.getCreatedAt());vo.setUpdatedAt(x.getUpdatedAt());}
        return vo;
    }

    private void attachCover(CatalogEntityVO item) { EntityType type=entityType(item.getEntityType()); ResourceMedia media=mediaMapper.selectOne(new LambdaQueryWrapper<ResourceMedia>().eq(ResourceMedia::getEntityType,type).eq(ResourceMedia::getEntityId,item.getEntityId()).orderByDesc(ResourceMedia::getPrimary).orderByAsc(ResourceMedia::getSortOrder).last("LIMIT 1")); if(media!=null)item.setCoverUrl(StringUtils.hasText(media.getCoverUrl())?media.getCoverUrl():media.getMediaUrl()); }

    private void replaceAttachments(EntityType type, Long id, List<CatalogMediaRequest> media, List<CatalogSourceRequest> sources) {
        if (media != null) replaceMedia(type, id, media);
        if (sources != null) replaceSources(type, id, sources);
    }
    private void replaceMedia(EntityType type, Long id, List<CatalogMediaRequest> media) { List<ResourceMedia> existing=mediaMapper.selectList(new LambdaQueryWrapper<ResourceMedia>().eq(ResourceMedia::getEntityType,type).eq(ResourceMedia::getEntityId,id)); mediaMapper.delete(new LambdaQueryWrapper<ResourceMedia>().eq(ResourceMedia::getEntityType,type).eq(ResourceMedia::getEntityId,id)); int order=0; for(CatalogMediaRequest value:media){ if(!StringUtils.hasText(value.getMediaUrl())) continue; ResourceMedia item=new ResourceMedia();item.setEntityType(type);item.setEntityId(id);item.setMediaType(parseMedia(value.getMediaType()));item.setMediaTitle(clean(value.getMediaTitle()));item.setMediaUrl(clean(value.getMediaUrl()));item.setCoverUrl(clean(value.getCoverUrl()));item.setDescription(clean(value.getDescription()));item.setCopyrightNote(clean(value.getCopyrightNote()));item.setPrimary(Boolean.TRUE.equals(value.getPrimary()));item.setSortOrder(order++);mediaMapper.insert(item); } java.util.Set<String> retained=media.stream().map(CatalogMediaRequest::getMediaUrl).filter(StringUtils::hasText).collect(java.util.stream.Collectors.toSet()); existing.stream().map(ResourceMedia::getMediaUrl).filter(url -> !retained.contains(url)).forEach(mediaStorageService::deleteIfManaged); }
    private void replaceSources(EntityType type, Long id, List<CatalogSourceRequest> sources) { sourceRelMapper.delete(new LambdaQueryWrapper<EntitySourceRel>().eq(EntitySourceRel::getEntityType,type).eq(EntitySourceRel::getEntityId,id)); for(CatalogSourceRequest value:sources){ if(value.getSourceId()==null && !StringUtils.hasText(value.getSourceUrl())) continue; EntitySourceRel item=new EntitySourceRel();item.setEntityType(type);item.setEntityId(id);item.setSourceId(value.getSourceId());item.setSourceUrl(clean(value.getSourceUrl()));item.setSourceExcerpt(clean(value.getSourceExcerpt()));item.setCredibilityScore(value.getCredibilityScore());item.setCapturedAt(LocalDateTime.now());sourceRelMapper.insert(item); } }
    private CatalogMediaRequest toMedia(ResourceMedia value){CatalogMediaRequest item=new CatalogMediaRequest();item.setMediaId(value.getMediaId());item.setMediaUrl(value.getMediaUrl());item.setCoverUrl(value.getCoverUrl());item.setMediaTitle(value.getMediaTitle());item.setMediaType(enumValue(value.getMediaType()));item.setDescription(value.getDescription());item.setCopyrightNote(value.getCopyrightNote());item.setPrimary(value.getPrimary());return item;}
    private CatalogSourceRequest toSource(EntitySourceRel value){CatalogSourceRequest item=new CatalogSourceRequest();item.setSourceId(value.getSourceId());item.setSourceUrl(value.getSourceUrl());item.setSourceExcerpt(value.getSourceExcerpt());item.setCredibilityScore(value.getCredibilityScore());return item;}
    private void addOptions(List<CatalogRelationOptionVO> options, EntityType source, EntityType target, Enum<?>[] relationTypes) { for (Enum<?> relationType : relationTypes) options.add(new CatalogRelationOptionVO(source.getValue(), target.getValue(), relationType.name(), relationType.name().replace('_', ' '))); }
    private CatalogRelationVO relationVO(CatalogRelationRequest request){CatalogRelationVO vo=new CatalogRelationVO();vo.setSourceType(request.getSourceType().getValue());vo.setSourceId(request.getSourceId());vo.setTargetType(request.getTargetType().getValue());vo.setTargetId(request.getTargetId());vo.setRelationType(request.getRelationType());vo.setRemark(request.getRemark());return vo;}
    private CatalogRelationVO relationVO(EntityType sourceType,Long sourceId,EntityType targetType,Long targetId,String relationType,String remark,String kind,Long relationId){CatalogRelationVO vo=new CatalogRelationVO();vo.setSourceType(sourceType.getValue());vo.setSourceId(sourceId);vo.setTargetType(targetType.getValue());vo.setTargetId(targetId);vo.setRelationType(relationType);vo.setRemark(remark);vo.setRelationKind(kind);vo.setRelationId(relationId);return vo;}
    private MediaType parseMedia(String value){if(!StringUtils.hasText(value))return MediaType.IMAGE;try{return MediaType.valueOf(value.trim().toUpperCase(Locale.ROOT));}catch(IllegalArgumentException ex){throw new IllegalArgumentException("unsupported mediaType");}}
    private void validateRequest(CatalogEntityRequest request){if(request==null||request.getEntityType()==null||!StringUtils.hasText(request.getCode())||!StringUtils.hasText(request.getName()))throw new IllegalArgumentException("entityType, code and name are required");if(request.getEntityType()==EntityType.SCHOOL||request.getEntityType()==EntityType.ACTIVITY_PLAN)throw new IllegalArgumentException("unsupported catalog entity type");}
    private EntityType entityType(String value){for(EntityType type:EntityType.values())if(type.getValue().equals(value))return type;throw new IllegalArgumentException("unsupported catalog entity type");}
    private boolean contains(CatalogEntityVO item,String keyword){String lower=keyword.toLowerCase(Locale.ROOT);return containsText(item.getCode(),lower)||containsText(item.getName(),lower)||containsText(item.getAlias(),lower)||containsText(item.getSummary(),lower)||containsText(item.getAddress(),lower);}
    private boolean containsText(String value,String keyword){return value!=null&&value.toLowerCase(Locale.ROOT).contains(keyword);}
    private String clean(String value){return StringUtils.hasText(value)?value.trim():null;}
    private String enumValue(Object value){if(value==null)return null;try{return String.valueOf(value.getClass().getMethod("getValue").invoke(value));}catch(ReflectiveOperationException ex){return String.valueOf(value).toLowerCase(Locale.ROOT);}}
}
