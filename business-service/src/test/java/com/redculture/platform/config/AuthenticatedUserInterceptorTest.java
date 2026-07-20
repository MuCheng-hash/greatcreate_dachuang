package com.redculture.platform.config;

import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticatedUserInterceptorTest {

    @Test
    void rejectsRequestWithoutAuthenticatedSession() throws Exception {
        AuthenticatedUserInterceptor interceptor = new AuthenticatedUserInterceptor(mock(SchoolUserAccountService.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void permitsActiveAccount() throws Exception {
        SchoolUserAccountService accountService = mock(SchoolUserAccountService.class);
        SchoolUserAccount account = new SchoolUserAccount();
        account.setStatus(AccountStatus.ACTIVE);
        when(accountService.getById(9L)).thenReturn(account);
        AuthenticatedUserInterceptor interceptor = new AuthenticatedUserInterceptor(accountService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthServiceImpl.AUTH_SESSION_KEY, 9L);

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }
}
