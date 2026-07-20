package com.redculture.platform.service.impl;

import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.mapper.SchoolRegistrationMapper;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AuthPasswordChangeRequest;
import com.redculture.platform.vo.request.AuthProfileUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private final SchoolRegistrationMapper registrationMapper = mock(SchoolRegistrationMapper.class);
    private final SchoolUserAccountService accountService = mock(SchoolUserAccountService.class);
    private final SchoolService schoolService = mock(SchoolService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AuthServiceImpl service;
    private SchoolUserAccount account;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(registrationMapper, accountService, schoolService, passwordEncoder);
        account = new SchoolUserAccount();
        account.setAccountId(7L);
        account.setUsername("lizhuang");
        account.setPasswordHash(passwordEncoder.encode("old-password"));
        account.setRoleCode("school_admin");
        account.setSchoolId(3L);
        account.setStatus(AccountStatus.ACTIVE);

        School school = new School();
        school.setSchoolId(3L);
        school.setSchoolName("里庄小学");
        when(accountService.getById(7L)).thenReturn(account);
        when(schoolService.getById(3L)).thenReturn(school);

        session = new MockHttpSession();
        session.setAttribute(AuthServiceImpl.AUTH_SESSION_KEY, 7L);
    }

    @Test
    void currentUserReturnsNullWithoutSessionAccount() {
        AuthCurrentUserVO user = service.currentUser(new MockHttpSession());

        assertNull(user);
    }

    @Test
    void updateProfilePersistsTrimmedFields() {
        AuthProfileUpdateRequest request = new AuthProfileUpdateRequest();
        request.setDisplayName("  李老师  ");
        request.setContactName("  李明  ");
        request.setContactPhone("  13800000000  ");

        AuthCurrentUserVO result = service.updateProfile(request, session);

        assertEquals("李老师", result.getDisplayName());
        assertEquals("李明", result.getContactName());
        assertEquals("13800000000", result.getContactPhone());
        verify(accountService).updateById(account);
    }

    @Test
    void updateProfileRejectsFieldBeyondDatabaseLimit() {
        AuthProfileUpdateRequest request = new AuthProfileUpdateRequest();
        request.setContactPhone("1".repeat(51));

        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(request, session));
    }

    @Test
    void changePasswordRejectsIncorrectCurrentPassword() {
        AuthPasswordChangeRequest request = new AuthPasswordChangeRequest();
        request.setCurrentPassword("wrong-password");
        request.setNewPassword("new-password");

        assertThrows(IllegalArgumentException.class, () -> service.changePassword(request, session));
    }

    @Test
    void changePasswordReplacesHashAndKeepsSession() {
        AuthPasswordChangeRequest request = new AuthPasswordChangeRequest();
        request.setCurrentPassword("old-password");
        request.setNewPassword("new-password");

        service.changePassword(request, session);

        assertTrue(passwordEncoder.matches("new-password", account.getPasswordHash()));
        assertEquals(7L, session.getAttribute(AuthServiceImpl.AUTH_SESSION_KEY));
        verify(accountService).updateById(account);
    }
}
