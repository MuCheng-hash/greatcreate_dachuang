package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.StudentHomeVO;

public interface StudentHomeService {
    StudentHomeVO home(AuthCurrentUserVO user);
}
