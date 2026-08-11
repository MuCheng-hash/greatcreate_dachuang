package com.redculture.platform.service;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.TeacherResourceVO;
import com.redculture.platform.vo.request.TeacherResourceQueryRequest;
import java.util.List;
public interface TeacherResourceService { List<TeacherResourceVO> nearby(TeacherResourceQueryRequest request, AuthCurrentUserVO user); List<TeacherResourceVO> favorites(AuthCurrentUserVO user); List<TeacherResourceVO> selection(AuthCurrentUserVO user); TeacherResourceVO detail(Long resourceId, AuthCurrentUserVO user); void favorite(Long resourceId, AuthCurrentUserVO user); void unfavorite(Long resourceId, AuthCurrentUserVO user); }
