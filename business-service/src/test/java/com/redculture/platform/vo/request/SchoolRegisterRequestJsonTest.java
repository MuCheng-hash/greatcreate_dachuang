package com.redculture.platform.vo.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoSourceType;
import com.redculture.platform.enums.SchoolLevel;
import com.redculture.platform.enums.SchoolNature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchoolRegisterRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsDatabaseValuesAndSerializesDatabaseValues() throws Exception {
        SchoolRegisterRequest request = objectMapper.readValue("""
                {
                  "username": "school-admin",
                  "password": "secret",
                  "schoolName": "测试学校",
                  "schoolLevel": "nine_year",
                  "schoolNature": "public",
                  "geoSourceType": "manual",
                  "geoConfidence": "unknown"
                }
                """, SchoolRegisterRequest.class);

        assertEquals(SchoolLevel.NINE_YEAR, request.getSchoolLevel());
        assertEquals(SchoolNature.PUBLIC, request.getSchoolNature());
        assertEquals(GeoSourceType.MANUAL, request.getGeoSourceType());
        assertEquals(GeoConfidenceLevel.UNKNOWN, request.getGeoConfidence());
        assertEquals("\"nine_year\"", objectMapper.writeValueAsString(request.getSchoolLevel()));
        assertEquals("\"public\"", objectMapper.writeValueAsString(request.getSchoolNature()));
    }

    @Test
    void acceptsJavaEnumNames() throws Exception {
        SchoolRegisterRequest request = objectMapper.readValue("""
                {
                  "username": "school-admin",
                  "password": "secret",
                  "schoolName": "测试学校",
                  "schoolLevel": "NINE_YEAR",
                  "schoolNature": "PUBLIC",
                  "geoSourceType": "MANUAL",
                  "geoConfidence": "UNKNOWN"
                }
                """, SchoolRegisterRequest.class);

        assertEquals(SchoolLevel.NINE_YEAR, request.getSchoolLevel());
        assertEquals(SchoolNature.PUBLIC, request.getSchoolNature());
        assertEquals(GeoSourceType.MANUAL, request.getGeoSourceType());
        assertEquals(GeoConfidenceLevel.UNKNOWN, request.getGeoConfidence());
    }

    @Test
    void rejectsUnsupportedEnumValue() {
        assertThrows(JsonProcessingException.class, () -> objectMapper.readValue("""
                {
                  "username": "school-admin",
                  "password": "secret",
                  "schoolName": "测试学校",
                  "schoolLevel": "not-a-school-level"
                }
                """, SchoolRegisterRequest.class));
    }

    @Test
    void acceptsTeachingPlanActivityDatabaseValue() throws Exception {
        TeachingPlanGenerateRequest request = objectMapper.readValue("""
                {
                  "schoolId": 1,
                  "grade": "四年级",
                  "theme": "敬老志愿服务",
                  "activityType": "volunteer_service",
                  "durationMinutes": 120,
                  "practiceRequired": true
                }
                """, TeachingPlanGenerateRequest.class);

        assertEquals(ActivityType.VOLUNTEER_SERVICE, request.getActivityType());
        assertEquals("\"volunteer_service\"", objectMapper.writeValueAsString(request.getActivityType()));
    }
}
