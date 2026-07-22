package com.redculture.platform.service.agent;

import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.ai.AgentActorVO;
import com.redculture.platform.vo.ai.AgentScopeVO;
import com.redculture.platform.vo.ai.AgentToolRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAccessGuardTest {

    @Test
    void rejectsCrossSchoolScopeForSchoolAccount() {
        AgentAccessGuard guard = new AgentAccessGuard(mock(SchoolMapService.class));
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(1L);
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);

        assertThrows(IllegalArgumentException.class,
                () -> guard.resolveScope("SCHOOL", 2L, user, "查询资源"));
    }

    @Test
    void returnsClarificationForTwoSchools() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.listSchools(null, null, null, 100)).thenReturn(List.of(
                school(1L, "里庄小学"), school(2L, "示例小学")
        ));
        AgentAccessGuard guard = new AgentAccessGuard(schoolMapService);
        AuthCurrentUserVO admin = new AuthCurrentUserVO();
        admin.setAccountId(9L);
        admin.setRoleCode("platform_admin");

        AgentAccessGuard.ScopeResolution result = guard.resolveScope(
                null, null, admin, "里庄小学和示例小学附近有哪些资源？"
        );

        assertTrue(result.clarificationRequired());
        assertEquals(List.of("里庄小学", "示例小学"), result.options());
    }

    @Test
    void resolvesUniqueAdministrativeSuffixAlias() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.listSchools(null, null, null, 100)).thenReturn(List.of(
                school(1L, "石家庄市藁城区常安镇里庄小学")
        ));
        AgentAccessGuard guard = new AgentAccessGuard(schoolMapService);
        AuthCurrentUserVO admin = new AuthCurrentUserVO();
        admin.setAccountId(9L);
        admin.setRoleCode("platform_admin");

        AgentAccessGuard.ScopeResolution result = guard.resolveScope(
                null, null, admin, "里庄小学附近有哪些资源？"
        );

        assertFalse(result.clarificationRequired());
        assertEquals(1L, result.id());
    }

    @Test
    void returnsClarificationWhenAdministrativeSuffixAliasIsAmbiguous() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.listSchools(null, null, null, 100)).thenReturn(List.of(
                school(1L, "石家庄市藁城区常安镇里庄小学"),
                school(2L, "保定市莲池区南关街道里庄小学")
        ));
        AgentAccessGuard guard = new AgentAccessGuard(schoolMapService);
        AuthCurrentUserVO admin = new AuthCurrentUserVO();
        admin.setAccountId(9L);
        admin.setRoleCode("platform_admin");

        AgentAccessGuard.ScopeResolution result = guard.resolveScope(
                null, null, admin, "里庄小学附近有哪些资源？"
        );

        assertTrue(result.clarificationRequired());
        assertEquals(List.of(
                "石家庄市藁城区常安镇里庄小学",
                "保定市莲池区南关街道里庄小学"
        ), result.options());
    }

    @Test
    void rejectsSchoolAccountWhenAliasNamesAnotherSchool() {
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.listSchools(null, null, null, 100)).thenReturn(List.of(
                school(1L, "石家庄市藁城区常安镇第一小学"),
                school(2L, "保定市莲池区南关街道里庄小学")
        ));
        AgentAccessGuard guard = new AgentAccessGuard(schoolMapService);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(1L);
        user.setRoleCode("school_admin");
        user.setSchoolId(1L);

        assertThrows(IllegalArgumentException.class, () -> guard.resolveScope(
                null, null, user, "里庄小学附近有哪些资源？"
        ));
    }

    @Test
    void rejectsToolRequestWithDifferentSchoolActor() {
        AgentAccessGuard guard = new AgentAccessGuard(mock(SchoolMapService.class));
        AgentToolRequest request = new AgentToolRequest();
        AgentActorVO actor = new AgentActorVO();
        actor.setAccountId(1L);
        actor.setRoleCode("school_admin");
        actor.setSchoolId(1L);
        AgentScopeVO scope = new AgentScopeVO();
        scope.setScopeType("SCHOOL");
        scope.setScopeId(2L);
        request.setActor(actor);
        request.setScope(scope);

        assertThrows(IllegalArgumentException.class, () -> guard.assertToolAccess(request));
    }

    private SchoolSummaryVO school(Long id, String name) {
        SchoolSummaryVO school = new SchoolSummaryVO();
        school.setSchoolId(id);
        school.setSchoolName(name);
        return school;
    }
}
