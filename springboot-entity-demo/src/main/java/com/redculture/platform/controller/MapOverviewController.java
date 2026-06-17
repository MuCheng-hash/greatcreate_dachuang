package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.service.MapOverviewService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.vo.ClientMapConfigVO;
import com.redculture.platform.vo.MapOverviewVO;
import com.redculture.platform.vo.NearbyResourceVO;
import com.redculture.platform.vo.TownBoundaryVO;
import com.redculture.platform.vo.TownLocateRequest;
import com.redculture.platform.vo.TownLocateResponse;
import com.redculture.platform.vo.TownMapDetailVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/map")
public class MapOverviewController {

    private final MapOverviewService mapOverviewService;
    private final TownMapService townMapService;
    private final AppMapProperties appMapProperties;

    public MapOverviewController(MapOverviewService mapOverviewService,
                                 TownMapService townMapService,
                                 AppMapProperties appMapProperties) {
        this.mapOverviewService = mapOverviewService;
        this.townMapService = townMapService;
        this.appMapProperties = appMapProperties;
    }

    @GetMapping("/client-config")
    public ApiResponse<ClientMapConfigVO> clientConfig() {
        return ApiResponse.success(new ClientMapConfigVO(
            appMapProperties.getAmapKey(),
            appMapProperties.getAmapSecurityJsCode(),
            appMapProperties.getQaServiceBaseUrl()
        ));
    }

    @GetMapping("/overview")
    public ApiResponse<MapOverviewVO> overview(@RequestParam Long regionId) {
        MapOverviewVO data = mapOverviewService.getOverviewByRegionId(regionId);
        if (data == null) {
            return ApiResponse.fail("region not found");
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/nearby")
    public ApiResponse<NearbyResourceVO> nearby(@RequestParam BigDecimal longitude,
                                                @RequestParam BigDecimal latitude,
                                                @RequestParam(required = false) Double radiusKm,
                                                @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(mapOverviewService.getNearbyResources(longitude, latitude, radiusKm, limit));
    }

    @PostMapping("/locate-town")
    public ApiResponse<TownLocateResponse> locateTown(@RequestBody TownLocateRequest request) {
        if (request == null || request.getLongitude() == null || request.getLatitude() == null) {
            return ApiResponse.fail("longitude and latitude are required");
        }
        return ApiResponse.success(townMapService.locateTown(request.getLongitude(), request.getLatitude()));
    }

    @GetMapping("/towns/{regionId}")
    public ApiResponse<TownMapDetailVO> getTownMapDetail(@PathVariable Long regionId) {
        try {
            TownMapDetailVO detailVO = townMapService.getTownMapDetail(regionId);
            if (detailVO == null) {
                return ApiResponse.fail("town region not found");
            }
            return ApiResponse.success(detailVO);
        } catch (Exception exception) {
            return ApiResponse.fail("town detail load failed: " + exception.getClass().getSimpleName());
        }
    }

    @GetMapping("/towns/{regionId}/boundary")
    public ApiResponse<TownBoundaryVO> getTownBoundary(@PathVariable Long regionId) {
        TownBoundaryVO boundaryVO = townMapService.getTownBoundary(regionId);
        if (boundaryVO == null) {
            return ApiResponse.fail("town boundary not found");
        }
        return ApiResponse.success(boundaryVO);
    }

    @GetMapping("/towns/boundaries")
    public ApiResponse<List<TownBoundaryVO>> listTownBoundaries() {
        return ApiResponse.success(townMapService.listTownBoundaries());
    }

    @GetMapping("/regions/boundaries")
    public ApiResponse<List<TownBoundaryVO>> listRegionBoundaries(@RequestParam(required = false) String regionLevel,
                                                                  @RequestParam(required = false) Long parentRegionId,
                                                                  @RequestParam(required = false) Long ancestorRegionId) {
        return ApiResponse.success(townMapService.listRegionBoundaries(regionLevel, parentRegionId, ancestorRegionId));
    }
}
