package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.TeacherRegistrationInvite;
import com.redculture.platform.mapper.TeacherRegistrationInviteMapper;
import com.redculture.platform.service.TeacherRegistrationInviteService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.TeacherRegistrationInviteVO;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class TeacherRegistrationInviteServiceImpl implements TeacherRegistrationInviteService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int DEFAULT_MAX_USES = 50;

    private final TeacherRegistrationInviteMapper inviteMapper;

    public TeacherRegistrationInviteServiceImpl(TeacherRegistrationInviteMapper inviteMapper) {
        this.inviteMapper = inviteMapper;
    }

    @Override
    public TeacherRegistrationInviteVO create(AuthCurrentUserVO user) {
        requireSchoolAdmin(user);
        return createForSchoolInternal(user.getSchoolId(), user.getAccountId());
    }

    @Override
    public TeacherRegistrationInviteVO createForSchool(Long schoolId, AuthCurrentUserVO user) {
        requirePlatformAdmin(user);
        if (schoolId == null) throw new IllegalArgumentException("schoolId 不能为空");
        return createForSchoolInternal(schoolId, user.getAccountId());
    }

    private TeacherRegistrationInviteVO createForSchoolInternal(Long schoolId, Long accountId) {
        String code = nextCode();
        TeacherRegistrationInvite invite = new TeacherRegistrationInvite();
        invite.setSchoolId(schoolId);
        invite.setCodeHash(InviteCodeHasher.hash(code));
        invite.setStatus("active");
        invite.setExpiresAt(LocalDateTime.now().plusDays(7));
        invite.setMaxUses(DEFAULT_MAX_USES);
        invite.setUsedCount(0);
        invite.setCreatedByAccountId(accountId);
        inviteMapper.insert(invite);
        TeacherRegistrationInviteVO result = toVO(invite);
        result.setInviteCode(code);
        return result;
    }

    @Override
    public List<TeacherRegistrationInviteVO> listMine(AuthCurrentUserVO user) {
        requireSchoolAdmin(user);
        return listBySchool(user.getSchoolId());
    }

    @Override
    public List<TeacherRegistrationInviteVO> listForSchool(Long schoolId, AuthCurrentUserVO user) {
        requirePlatformAdmin(user);
        if (schoolId == null) throw new IllegalArgumentException("schoolId 不能为空");
        return listBySchool(schoolId);
    }

    private List<TeacherRegistrationInviteVO> listBySchool(Long schoolId) {
        return inviteMapper.selectList(new LambdaQueryWrapper<TeacherRegistrationInvite>()
                        .eq(TeacherRegistrationInvite::getSchoolId, schoolId)
                        .orderByDesc(TeacherRegistrationInvite::getCreatedAt))
                .stream().map(this::toVO).toList();
    }

    @Override
    public void revoke(Long inviteId, AuthCurrentUserVO user) {
        if (user == null || !Set.of("school_admin", "platform_admin").contains(user.getRoleCode())) {
            throw new IllegalArgumentException("需要管理员权限");
        }
        TeacherRegistrationInvite invite = inviteMapper.selectById(inviteId);
        if (invite == null || ("school_admin".equals(user.getRoleCode()) && !user.getSchoolId().equals(invite.getSchoolId()))) {
            throw new IllegalArgumentException("邀请码不存在");
        }
        invite.setStatus("revoked");
        invite.setRevokedAt(LocalDateTime.now());
        inviteMapper.updateById(invite);
    }

    private void requireSchoolAdmin(AuthCurrentUserVO user) {
        if (user == null || !"school_admin".equals(user.getRoleCode()) || user.getSchoolId() == null) {
            throw new IllegalArgumentException("需要学校管理员权限");
        }
    }

    private void requirePlatformAdmin(AuthCurrentUserVO user) {
        if (user == null || !"platform_admin".equals(user.getRoleCode())) throw new IllegalArgumentException("需要平台管理员权限");
    }

    private String nextCode() {
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) value.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return value.toString();
    }

    private TeacherRegistrationInviteVO toVO(TeacherRegistrationInvite source) {
        TeacherRegistrationInviteVO target = new TeacherRegistrationInviteVO();
        target.setInviteId(source.getInviteId());
        target.setSchoolId(source.getSchoolId());
        target.setStatus(source.getStatus());
        target.setExpiresAt(source.getExpiresAt());
        target.setMaxUses(source.getMaxUses());
        target.setUsedCount(source.getUsedCount());
        target.setCreatedByAccountId(source.getCreatedByAccountId());
        target.setCreatedAt(source.getCreatedAt());
        return target;
    }
}
