package com.redculture.platform.service.impl;

import com.redculture.platform.entity.SchoolRegistration;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.exception.AuthConflictException;
import com.redculture.platform.mapper.SchoolRegistrationMapper;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolRegistrationSubmitVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.RegistrationReviewRequest;
import com.redculture.platform.vo.request.SchoolRegisterRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    @Test
    void registerSchoolCreatesPendingRegistration() {
        SchoolRegistrationMapper registrationMapper = mock(SchoolRegistrationMapper.class);
        SchoolUserAccountService accountService = mock(SchoolUserAccountService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(accountService.count(any())).thenReturn(0L);
        when(registrationMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        doAnswer(invocation -> {
            SchoolRegistration registration = invocation.getArgument(0);
            registration.setRegistrationId(7L);
            return 1;
        }).when(registrationMapper).insert(any(SchoolRegistration.class));

        AuthServiceImpl service = new AuthServiceImpl(registrationMapper, accountService, mock(SchoolService.class), passwordEncoder);

        SchoolRegistrationSubmitVO result = service.registerSchool(request(" school-admin "));

        assertEquals(7L, result.getRegistrationId());
        assertEquals("pending", result.getReviewStatus());
        verify(registrationMapper).insert(any(SchoolRegistration.class));
    }

    @Test
    void registerSchoolRejectsDuplicateAccountWithConflict() {
        SchoolUserAccountService accountService = mock(SchoolUserAccountService.class);
        when(accountService.count(any())).thenReturn(1L);

        AuthServiceImpl service = new AuthServiceImpl(mock(SchoolRegistrationMapper.class), accountService,
                mock(SchoolService.class), mock(PasswordEncoder.class));

        assertThrows(AuthConflictException.class, () -> service.registerSchool(request("school-admin")));
    }

    @Test
    void registerSchoolRejectsMissingRequiredFields() {
        AuthServiceImpl service = new AuthServiceImpl(mock(SchoolRegistrationMapper.class),
                mock(SchoolUserAccountService.class), mock(SchoolService.class), mock(PasswordEncoder.class));
        SchoolRegisterRequest request = new SchoolRegisterRequest();
        request.setUsername("school-admin");
        request.setPassword("secret");

        assertThrows(IllegalArgumentException.class, () -> service.registerSchool(request));
    }

    @Test
    void loginUpdatesLastLoginAndStoresSessionForPlatformAdmin() {
        SchoolUserAccount account = new SchoolUserAccount();
        account.setAccountId(11L);
        account.setUsername("admin");
        account.setPasswordHash("encoded-secret");
        account.setRoleCode("platform_admin");
        account.setStatus(AccountStatus.ACTIVE);

        SchoolUserAccountService accountService = mock(SchoolUserAccountService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        HttpSession session = mock(HttpSession.class);
        when(accountService.getOne(any())).thenReturn(account);
        when(passwordEncoder.matches("secret", "encoded-secret")).thenReturn(true);
        when(accountService.updateById(account)).thenReturn(true);

        AuthServiceImpl service = new AuthServiceImpl(mock(SchoolRegistrationMapper.class), accountService,
                mock(SchoolService.class), passwordEncoder);
        AuthLoginRequest request = new AuthLoginRequest();
        request.setUsername("admin");
        request.setPassword("secret");

        AuthCurrentUserVO result = service.login(request, session);

        assertEquals(11L, result.getAccountId());
        assertEquals("platform_admin", result.getRoleCode());
        assertNotNull(account.getLastLoginAt());
        verify(session).setAttribute(AuthServiceImpl.AUTH_SESSION_KEY, 11L);
    }

    @Test
    void loginRejectsWrongPasswordWithoutUpdatingAccount() {
        SchoolUserAccount account = new SchoolUserAccount();
        account.setUsername("admin");
        account.setPasswordHash("encoded-secret");
        account.setStatus(AccountStatus.ACTIVE);

        SchoolUserAccountService accountService = mock(SchoolUserAccountService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(accountService.getOne(any())).thenReturn(account);
        when(passwordEncoder.matches("wrong", "encoded-secret")).thenReturn(false);

        AuthServiceImpl service = new AuthServiceImpl(mock(SchoolRegistrationMapper.class), accountService,
                mock(SchoolService.class), passwordEncoder);
        AuthLoginRequest request = new AuthLoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        assertThrows(IllegalArgumentException.class, () -> service.login(request, mock(HttpSession.class)));
        verify(accountService, never()).updateById(any(SchoolUserAccount.class));
    }

    @Test
    void authWritesUseMysqlTransactionManager() throws NoSuchMethodException {
        assertEquals("mysqlTransactionManager", AuthServiceImpl.class
                .getMethod("registerSchool", SchoolRegisterRequest.class)
                .getAnnotation(Transactional.class).transactionManager());
        assertEquals("mysqlTransactionManager", AuthServiceImpl.class
                .getMethod("login", AuthLoginRequest.class, HttpSession.class)
                .getAnnotation(Transactional.class).transactionManager());
        assertEquals("mysqlTransactionManager", SchoolRegistrationServiceImpl.class
                .getMethod("approveRegistration", Long.class, RegistrationReviewRequest.class)
                .getAnnotation(Transactional.class).transactionManager());
        assertEquals("mysqlTransactionManager", SchoolRegistrationServiceImpl.class
                .getMethod("rejectRegistration", Long.class, RegistrationReviewRequest.class)
                .getAnnotation(Transactional.class).transactionManager());
    }

    private SchoolRegisterRequest request(String username) {
        SchoolRegisterRequest request = new SchoolRegisterRequest();
        request.setUsername(username);
        request.setPassword("secret");
        request.setSchoolName("测试学校");
        return request;
    }
}
