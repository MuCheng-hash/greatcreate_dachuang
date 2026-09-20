package com.redculture.platform.controller;

import com.redculture.platform.config.AuthContext;
import com.redculture.platform.mapper.KnowledgeDocumentImageMapper;
import com.redculture.platform.mapper.KnowledgeIngestJobMapper;
import com.redculture.platform.service.KnowledgeDocumentService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.KnowledgeDocumentListItem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentControllerTest {

    @Test
    void listsAdminDocumentSummariesForRequestedScope() {
        KnowledgeDocumentService service = mock(KnowledgeDocumentService.class);
        KnowledgeDocumentListItem item = new KnowledgeDocumentListItem();
        item.setDocumentId(8L);
        item.setDocumentStatus("RUNNING");
        when(service.listForAdmin("public", null)).thenReturn(List.of(item));
        KnowledgeDocumentController controller = new KnowledgeDocumentController(service,
                mock(KnowledgeIngestJobMapper.class), mock(KnowledgeDocumentImageMapper.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthCurrentUserVO admin = new AuthCurrentUserVO();
        admin.setRoleCode("platform_admin");
        request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, admin);

        var response = controller.list("public", null, request);

        assertEquals(200, response.getCode());
        assertEquals(8L, response.getData().getFirst().getDocumentId());
        assertEquals("RUNNING", response.getData().getFirst().getDocumentStatus());
    }
}
