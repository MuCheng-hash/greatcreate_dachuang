package com.redculture.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.vo.RegionCenterVO;
import com.redculture.platform.vo.TownBoundaryVO;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class TownBoundaryGeometryService {

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ObjectMapper objectMapper;
    private final AppMapProperties appMapProperties;

    public TownBoundaryGeometryService(ObjectMapper objectMapper, AppMapProperties appMapProperties) {
        this.objectMapper = objectMapper;
        this.appMapProperties = appMapProperties;
    }

    public TownBoundaryProjection project(AdministrativeRegion region) {
        if (region == null) {
            return null;
        }

        if (region.getBoundaryGeojson() != null && !region.getBoundaryGeojson().isBlank()) {
            Geometry geometry = parseGeoJson(region.getBoundaryGeojson());
            if (geometry != null) {
                return new TownBoundaryProjection(
                        geometry,
                        region.getBoundaryGeojson(),
                        "provided",
                        toBoundaryVo(region, region.getBoundaryGeojson(), "provided")
                );
            }
        }

        if (region.getCenterLongitude() != null && region.getCenterLatitude() != null) {
            GeneratedBoundary generatedBoundary = generateFallbackBoundary(region);
            return new TownBoundaryProjection(
                    generatedBoundary.geometry(),
                    generatedBoundary.geoJson(),
                    "generated",
                    toBoundaryVo(region, generatedBoundary.geoJson(), "generated")
            );
        }

        return new TownBoundaryProjection(
                null,
                null,
                "missing",
                toBoundaryVo(region, null, "missing")
        );
    }

    public boolean contains(TownBoundaryProjection projection, BigDecimal longitude, BigDecimal latitude) {
        if (projection == null || projection.geometry() == null || longitude == null || latitude == null) {
            return false;
        }
        Point point = geometryFactory.createPoint(new Coordinate(longitude.doubleValue(), latitude.doubleValue()));
        return projection.geometry().covers(point);
    }

    private Geometry parseGeoJson(String geoJson) {
        try {
            JsonNode root = objectMapper.readTree(geoJson);
            String type = root.path("type").asText();
            JsonNode coordinates = root.path("coordinates");
            if ("Polygon".equalsIgnoreCase(type)) {
                return toPolygon(coordinates);
            }
            if ("MultiPolygon".equalsIgnoreCase(type)) {
                List<Polygon> polygons = new ArrayList<>();
                for (JsonNode polygonNode : coordinates) {
                    Polygon polygon = toPolygon(polygonNode);
                    if (polygon != null) {
                        polygons.add(polygon);
                    }
                }
                if (!polygons.isEmpty()) {
                    return geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Polygon toPolygon(JsonNode polygonCoordinates) {
        if (polygonCoordinates == null || !polygonCoordinates.isArray() || polygonCoordinates.isEmpty()) {
            return null;
        }

        LinearRing shell = toRing(polygonCoordinates.get(0));
        if (shell == null) {
            return null;
        }

        List<LinearRing> holes = new ArrayList<>();
        for (int i = 1; i < polygonCoordinates.size(); i++) {
            LinearRing hole = toRing(polygonCoordinates.get(i));
            if (hole != null) {
                holes.add(hole);
            }
        }
        return geometryFactory.createPolygon(shell, holes.toArray(new LinearRing[0]));
    }

    private LinearRing toRing(JsonNode ringCoordinates) {
        if (ringCoordinates == null || !ringCoordinates.isArray() || ringCoordinates.size() < 4) {
            return null;
        }

        List<Coordinate> coordinates = new ArrayList<>();
        for (JsonNode node : ringCoordinates) {
            if (node.size() < 2) {
                continue;
            }
            coordinates.add(new Coordinate(node.get(0).asDouble(), node.get(1).asDouble()));
        }

        if (coordinates.size() < 4) {
            return null;
        }

        Coordinate first = coordinates.get(0);
        Coordinate last = coordinates.get(coordinates.size() - 1);
        if (Double.compare(first.x, last.x) != 0 || Double.compare(first.y, last.y) != 0) {
            coordinates.add(new Coordinate(first.x, first.y));
        }

        return geometryFactory.createLinearRing(coordinates.toArray(new Coordinate[0]));
    }

    private GeneratedBoundary generateFallbackBoundary(AdministrativeRegion region) {
        double centerLon = region.getCenterLongitude().doubleValue();
        double centerLat = region.getCenterLatitude().doubleValue();
        double radiusKm = appMapProperties.getGeneratedBoundaryRadiusKm();
        double radiusLat = radiusKm / 111.0D;
        double radiusLon = radiusKm / (111.0D * Math.cos(Math.toRadians(centerLat)));
        int steps = 48;
        Coordinate[] coordinates = new Coordinate[steps + 1];
        StringBuilder geoJson = new StringBuilder();
        geoJson.append("{\"type\":\"Polygon\",\"coordinates\":[[");

        for (int i = 0; i <= steps; i++) {
            double angle = (Math.PI * 2 * i) / steps;
            double lon = centerLon + Math.cos(angle) * radiusLon;
            double lat = centerLat + Math.sin(angle) * radiusLat;
            coordinates[i] = new Coordinate(lon, lat);
            if (i > 0) {
                geoJson.append(",");
            }
            geoJson.append("[")
                    .append(scale(lon))
                    .append(",")
                    .append(scale(lat))
                    .append("]");
        }

        geoJson.append("]]}");
        LinearRing shell = geometryFactory.createLinearRing(coordinates);
        return new GeneratedBoundary(geometryFactory.createPolygon(shell), geoJson.toString());
    }

    private double scale(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP).doubleValue();
    }

    private TownBoundaryVO toBoundaryVo(AdministrativeRegion region, String geoJson, String status) {
        TownBoundaryVO vo = new TownBoundaryVO();
        vo.setRegionId(region.getRegionId());
        vo.setParentRegionId(region.getParentRegionId());
        vo.setRegionName(region.getRegionName());
        vo.setRegionLevel(region.getRegionLevel() == null ? null : region.getRegionLevel().name().toLowerCase());
        vo.setAdcode(region.getAdcode());
        vo.setBoundaryGeoJson(geoJson);
        vo.setBoundaryStatus(status);

        RegionCenterVO centerVO = new RegionCenterVO();
        centerVO.setLongitude(region.getCenterLongitude());
        centerVO.setLatitude(region.getCenterLatitude());
        vo.setCenter(centerVO);
        return vo;
    }

    public static final class TownBoundaryProjection {

        private final Geometry geometry;
        private final String boundaryGeoJson;
        private final String boundaryStatus;
        private final TownBoundaryVO boundaryVO;

        public TownBoundaryProjection(Geometry geometry,
                                      String boundaryGeoJson,
                                      String boundaryStatus,
                                      TownBoundaryVO boundaryVO) {
            this.geometry = geometry;
            this.boundaryGeoJson = boundaryGeoJson;
            this.boundaryStatus = boundaryStatus;
            this.boundaryVO = boundaryVO;
        }

        public Geometry geometry() {
            return geometry;
        }

        public String boundaryGeoJson() {
            return boundaryGeoJson;
        }

        public String boundaryStatus() {
            return boundaryStatus;
        }

        public TownBoundaryVO boundaryVO() {
            return boundaryVO;
        }
    }

    private static final class GeneratedBoundary {
        private final Polygon geometry;
        private final String geoJson;

        private GeneratedBoundary(Polygon geometry, String geoJson) {
            this.geometry = geometry;
            this.geoJson = geoJson;
        }

        private Polygon geometry() {
            return geometry;
        }

        private String geoJson() {
            return geoJson;
        }
    }
}
