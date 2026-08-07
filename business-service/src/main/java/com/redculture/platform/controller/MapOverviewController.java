package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.service.MapOverviewService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.RedCultureGraphMapService;
import com.redculture.platform.vo.ClientMapConfigVO;
import com.redculture.platform.vo.MapOverviewVO;
import com.redculture.platform.vo.NearbyResourceVO;
import com.redculture.platform.vo.TownBoundaryVO;
import com.redculture.platform.vo.TownLocateRequest;
import com.redculture.platform.vo.TownLocateResponse;
import com.redculture.platform.vo.TownMapDetailVO;
import com.redculture.platform.vo.RedCultureSiteDetailVO;
import com.redculture.platform.vo.RedCultureSiteMarkerVO;
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
//数字地图主接口：红色文化点位、地图概览、附近资源、行政区/乡镇边界、乡镇定位及高德地图前端配置。
public class MapOverviewController {

    private final MapOverviewService mapOverviewService;
    private final TownMapService townMapService;
    private final AppMapProperties appMapProperties;
    private final RedCultureGraphMapService redCultureGraphMapService;

    public MapOverviewController(MapOverviewService mapOverviewService,
                                 TownMapService townMapService,
                                 AppMapProperties appMapProperties,
                                 RedCultureGraphMapService redCultureGraphMapService) {
        this.mapOverviewService = mapOverviewService;
        this.townMapService = townMapService;
        this.appMapProperties = appMapProperties;
        this.redCultureGraphMapService = redCultureGraphMapService;
    }


    //查询已发布的红色文化地点标记，用于地图上打点。可用 district 按区县筛选。
    @GetMapping("/red-culture/sites")
    public ApiResponse<List<RedCultureSiteMarkerVO>> listRedCultureSites(
            @RequestParam(required = false) String district) {
        return ApiResponse.success(redCultureGraphMapService.listPublishedSites(district));
    }

    //查询某个已发布红色文化地点的详细资料。不存在或未发布时返回 404。
    @GetMapping("/red-culture/sites/{siteId}")
    public ApiResponse<RedCultureSiteDetailVO> getRedCultureSite(@PathVariable String siteId) {
        RedCultureSiteDetailVO detail = redCultureGraphMapService.getPublishedSite(siteId);
        return detail == null ? ApiResponse.fail(404, "published red culture site not found") : ApiResponse.success(detail);
    }

    //返回高德地图前端初始化所需的 Key 和安全密钥。
    @GetMapping("/client-config")
    public ApiResponse<ClientMapConfigVO> clientConfig() {
        return ApiResponse.success(new ClientMapConfigVO(
            appMapProperties.getAmapKey(),
            appMapProperties.getAmapSecurityJsCode()
        ));
    }

    //返回指定行政区的地图概览数据。
    @GetMapping("/overview")
    public ApiResponse<MapOverviewVO> overview(@RequestParam Long regionId) {
        MapOverviewVO data = mapOverviewService.getOverviewByRegionId(regionId);
        if (data == null) {
            return ApiResponse.fail("region not found");
        }
        return ApiResponse.success(data);
    }

    //按经纬度查询附近教育资源。
    @GetMapping("/nearby")
    public ApiResponse<NearbyResourceVO> nearby(@RequestParam BigDecimal longitude,
                                                @RequestParam BigDecimal latitude,
                                                @RequestParam(required = false) Double radiusKm,
                                                @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(mapOverviewService.getNearbyResources(longitude, latitude, radiusKm, limit));
    }

    //根据经纬度反查该坐标所在乡镇。
    @PostMapping("/locate-town")
    public ApiResponse<TownLocateResponse> locateTown(@RequestBody TownLocateRequest request) {
        if (request == null || request.getLongitude() == null || request.getLatitude() == null) {
            return ApiResponse.fail("longitude and latitude are required");
        }
        return ApiResponse.success(townMapService.locateTown(request.getLongitude(), request.getLatitude()));
    }

    //查询某一个乡镇的地图详情。
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

    //查询某个乡镇的边界数据。
    @GetMapping("/towns/{regionId}/boundary")
    public ApiResponse<TownBoundaryVO> getTownBoundary(@PathVariable Long regionId) {
        TownBoundaryVO boundaryVO = townMapService.getTownBoundary(regionId);
        if (boundaryVO == null) {
            return ApiResponse.fail("town boundary not found");
        }
        return ApiResponse.success(boundaryVO);
    }

    //查询所有乡镇的边界，适合首次加载乡镇图层。
    @GetMapping("/towns/boundaries")
    public ApiResponse<List<TownBoundaryVO>> listTownBoundaries() {
        return ApiResponse.success(townMapService.listTownBoundaries());
    }

    //按行政层级、父级或祖先区域筛选并查询边界。
    @GetMapping("/regions/boundaries")
    public ApiResponse<List<TownBoundaryVO>> listRegionBoundaries(@RequestParam(required = false) String regionLevel,
                                                                  @RequestParam(required = false) Long parentRegionId,
                                                                  @RequestParam(required = false) Long ancestorRegionId) {
        return ApiResponse.success(townMapService.listRegionBoundaries(regionLevel, parentRegionId, ancestorRegionId));
    }
}
