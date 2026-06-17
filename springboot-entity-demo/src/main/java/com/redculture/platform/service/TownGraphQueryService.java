package com.redculture.platform.service;

import com.redculture.platform.vo.EventSummaryVO;
import com.redculture.platform.vo.HeroSummaryVO;
import com.redculture.platform.vo.MapResourceMarkerVO;
import com.redculture.platform.vo.StorySummaryVO;

import java.util.List;

public interface TownGraphQueryService {

    TownGraphSnapshot loadTownGraph(Long regionId);

    class TownGraphSnapshot {
        private final boolean available;
        private final String message;
        private final List<MapResourceMarkerVO> markers;
        private final List<HeroSummaryVO> heroes;
        private final List<StorySummaryVO> stories;
        private final List<EventSummaryVO> events;

        public TownGraphSnapshot(boolean available,
                                 String message,
                                 List<MapResourceMarkerVO> markers,
                                 List<HeroSummaryVO> heroes,
                                 List<StorySummaryVO> stories,
                                 List<EventSummaryVO> events) {
            this.available = available;
            this.message = message;
            this.markers = markers;
            this.heroes = heroes;
            this.stories = stories;
            this.events = events;
        }

        public boolean isAvailable() {
            return available;
        }

        public String getMessage() {
            return message;
        }

        public List<MapResourceMarkerVO> getMarkers() {
            return markers;
        }

        public List<HeroSummaryVO> getHeroes() {
            return heroes;
        }

        public List<StorySummaryVO> getStories() {
            return stories;
        }

        public List<EventSummaryVO> getEvents() {
            return events;
        }
    }
}
