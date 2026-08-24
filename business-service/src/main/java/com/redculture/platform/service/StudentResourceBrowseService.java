package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.StudentRecentResourceVO;
import com.redculture.platform.common.PageResult;
import java.util.List;

public interface StudentResourceBrowseService {
    void record(Long resourceId, AuthCurrentUserVO user);
    List<StudentRecentResourceVO> recent(AuthCurrentUserVO user, Integer limit);
    PageResult<StudentRecentResourceVO> history(AuthCurrentUserVO user, Long pageNum, Long pageSize);
    void remove(Long resourceId, AuthCurrentUserVO user);
    void clear(AuthCurrentUserVO user);
}
