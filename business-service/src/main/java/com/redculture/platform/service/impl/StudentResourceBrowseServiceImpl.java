package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.*;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.*;
import com.redculture.platform.service.StudentResourceBrowseService;
import com.redculture.platform.vo.*;
import com.redculture.platform.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class StudentResourceBrowseServiceImpl implements StudentResourceBrowseService {
    private final StudentProfileMapper studentMapper;
    private final StudentResourceBrowseHistoryMapper historyMapper;
    private final LocalEduResourceMapper resourceMapper;
    private final SchoolResourceRelMapper relationMapper;

    public StudentResourceBrowseServiceImpl(StudentProfileMapper studentMapper, StudentResourceBrowseHistoryMapper historyMapper,
                                            LocalEduResourceMapper resourceMapper, SchoolResourceRelMapper relationMapper) {
        this.studentMapper = studentMapper; this.historyMapper = historyMapper; this.resourceMapper = resourceMapper; this.relationMapper = relationMapper;
    }

    @Override @Transactional
    public void record(Long resourceId, AuthCurrentUserVO user) {
        StudentProfile student = student(user);
        if (resourceId == null) throw new IllegalArgumentException("resourceId is required");
        LocalEduResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !Boolean.TRUE.equals(resource.getActive()) || resource.getReviewStatus() != ReviewStatus.APPROVED) throw new IllegalArgumentException("resource is unavailable");
        boolean accessible = relationMapper.exists(new LambdaQueryWrapper<SchoolResourceRel>().eq(SchoolResourceRel::getSchoolId, student.getSchoolId()).eq(SchoolResourceRel::getResourceId, resourceId));
        if (!accessible) throw new IllegalArgumentException("resource is not available to this school");
        StudentResourceBrowseHistory item = historyMapper.selectOne(new LambdaQueryWrapper<StudentResourceBrowseHistory>().eq(StudentResourceBrowseHistory::getStudentId, student.getStudentId()).eq(StudentResourceBrowseHistory::getResourceId, resourceId).last("LIMIT 1"));
        if (item == null) { item = new StudentResourceBrowseHistory(); item.setStudentId(student.getStudentId()); item.setResourceId(resourceId); item.setViewCount(1); item.setViewedAt(LocalDateTime.now()); historyMapper.insert(item); }
        else { item.setViewedAt(LocalDateTime.now()); item.setViewCount((item.getViewCount() == null ? 0 : item.getViewCount()) + 1); historyMapper.updateById(item); }
    }

    @Override
    public List<StudentRecentResourceVO> recent(AuthCurrentUserVO user, Integer limit) {
        StudentProfile student = student(user); int safe = limit == null || limit < 1 ? 5 : Math.min(limit, 10);
        List<StudentResourceBrowseHistory> history = historyMapper.selectList(new LambdaQueryWrapper<StudentResourceBrowseHistory>().eq(StudentResourceBrowseHistory::getStudentId, student.getStudentId()).orderByDesc(StudentResourceBrowseHistory::getViewedAt).last("LIMIT " + safe));
        if (history.isEmpty()) return List.of();
        return toResources(history, student.getSchoolId());
    }

    @Override
    public PageResult<StudentRecentResourceVO> history(AuthCurrentUserVO user, Long pageNum, Long pageSize) {
        StudentProfile student = student(user);
        long page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        List<StudentResourceBrowseHistory> all = historyMapper.selectList(new LambdaQueryWrapper<StudentResourceBrowseHistory>()
                .eq(StudentResourceBrowseHistory::getStudentId, student.getStudentId()).orderByDesc(StudentResourceBrowseHistory::getViewedAt));
        List<StudentRecentResourceVO> records = toResources(all, student.getSchoolId());
        int from = (int) Math.min((page - 1) * size, records.size());
        int to = (int) Math.min(from + size, records.size());
        return PageResult.of(records.subList(from, to), records.size(), page, size);
    }

    @Override @Transactional
    public void remove(Long resourceId, AuthCurrentUserVO user) {
        StudentProfile student = student(user);
        if (resourceId == null) throw new IllegalArgumentException("resourceId is required");
        historyMapper.delete(new LambdaQueryWrapper<StudentResourceBrowseHistory>().eq(StudentResourceBrowseHistory::getStudentId, student.getStudentId()).eq(StudentResourceBrowseHistory::getResourceId, resourceId));
    }

    @Override @Transactional
    public void clear(AuthCurrentUserVO user) {
        StudentProfile student = student(user);
        historyMapper.delete(new LambdaQueryWrapper<StudentResourceBrowseHistory>().eq(StudentResourceBrowseHistory::getStudentId, student.getStudentId()));
    }

    private List<StudentRecentResourceVO> toResources(List<StudentResourceBrowseHistory> history, Long schoolId) {
        if (history.isEmpty()) return List.of();
        Map<Long, LocalEduResource> resources = resourceMapper.selectBatchIds(history.stream().map(StudentResourceBrowseHistory::getResourceId).toList()).stream()
                .filter(r -> Boolean.TRUE.equals(r.getActive()) && r.getReviewStatus() == ReviewStatus.APPROVED)
                .collect(java.util.stream.Collectors.toMap(LocalEduResource::getResourceId, r -> r));
        return history.stream().filter(h -> resources.containsKey(h.getResourceId()) && relationMapper.exists(new LambdaQueryWrapper<SchoolResourceRel>().eq(SchoolResourceRel::getSchoolId, schoolId).eq(SchoolResourceRel::getResourceId, h.getResourceId()))).map(h -> {
            LocalEduResource r = resources.get(h.getResourceId()); StudentRecentResourceVO vo = new StudentRecentResourceVO();
            vo.setResourceId(r.getResourceId()); vo.setResourceName(r.getResourceName()); vo.setResourceCategory(r.getResourceCategory() == null ? null : r.getResourceCategory().getValue()); vo.setAddress(r.getAddress()); vo.setViewedAt(h.getViewedAt()); vo.setViewCount(h.getViewCount()); return vo;
        }).toList();
    }

    private StudentProfile student(AuthCurrentUserVO user) {
        if (user == null || !"student".equalsIgnoreCase(user.getRoleCode()) || user.getAccountId() == null) throw new IllegalArgumentException("student account is required");
        StudentProfile student = studentMapper.selectOne(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getAccountId, user.getAccountId()).eq(StudentProfile::getStatus, "active").last("LIMIT 1"));
        if (student == null) throw new IllegalArgumentException("student profile is unavailable"); return student;
    }
}
