package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.TeacherRegistrationInvite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TeacherRegistrationInviteMapper extends BaseMapper<TeacherRegistrationInvite> {

    @Update("""
            UPDATE teacher_registration_invite
            SET used_count = used_count + 1, updated_at = CURRENT_TIMESTAMP
            WHERE invite_id = #{inviteId}
              AND school_id = #{schoolId}
              AND status = 'active'
              AND expires_at > CURRENT_TIMESTAMP
              AND used_count < max_uses
            """)
    int consume(@Param("inviteId") Long inviteId, @Param("schoolId") Long schoolId);
}
