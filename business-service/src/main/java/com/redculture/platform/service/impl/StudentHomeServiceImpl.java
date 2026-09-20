package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.*;
import com.redculture.platform.mapper.*;
import com.redculture.platform.service.*;
import com.redculture.platform.vo.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StudentHomeServiceImpl implements StudentHomeService {
    private final StudentProfileMapper studentMapper;
    private final ClassMemberMapper memberMapper;
    private final ClassInfoMapper classMapper;
    private final TeacherClassService classService;
    private final StudentResourceBrowseService browseService;
    private final TaskResourceRelMapper taskResourceMapper;

    public StudentHomeServiceImpl(StudentProfileMapper studentMapper, ClassMemberMapper memberMapper, ClassInfoMapper classMapper,
                                  TeacherClassService classService, StudentResourceBrowseService browseService, TaskResourceRelMapper taskResourceMapper) {
        this.studentMapper = studentMapper; this.memberMapper = memberMapper; this.classMapper = classMapper; this.classService = classService; this.browseService = browseService; this.taskResourceMapper = taskResourceMapper;
    }

    @Override
    public StudentHomeVO home(AuthCurrentUserVO user) {
        StudentProfile student = requireStudent(user);
        StudentHomeVO vo = new StudentHomeVO();
        StudentProfileVO profile = new StudentProfileVO(); profile.setStudentId(student.getStudentId()); profile.setStudentName(student.getStudentName()); profile.setStudentNo(student.getStudentNo()); profile.setGradeName(student.getGradeName()); vo.setStudent(profile);
        List<Long> classIds = memberMapper.selectList(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, "active")).stream().map(ClassMember::getClassId).toList();
        Map<Long, ClassMember> memberByClass = memberMapper.selectList(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, "active")).stream().collect(Collectors.toMap(ClassMember::getClassId, x -> x, (a, b) -> a));
        if (!classIds.isEmpty()) vo.setClasses(classMapper.selectBatchIds(classIds).stream().filter(c -> "active".equals(c.getStatus())).sorted(Comparator.comparing((ClassInfo c) -> !Boolean.TRUE.equals(memberByClass.get(c.getClassId()).getPrimaryClass())).thenComparing(ClassInfo::getGradeName, Comparator.nullsLast(String::compareTo)).thenComparing(ClassInfo::getClassName, Comparator.nullsLast(String::compareTo))).map(c -> { StudentClassSummaryVO x = new StudentClassSummaryVO(); x.setClassId(c.getClassId()); x.setClassName(c.getClassName()); x.setGradeName(c.getGradeName()); x.setPrimary(memberByClass.get(c.getClassId()).getPrimaryClass()); return x; }).toList());
        List<ClassTaskVO> tasks = classService.studentTasks(user).stream().filter(t -> !"completed".equalsIgnoreCase(t.getStudentStatus())).limit(5).toList();
        vo.setPendingTasks(tasks);
        if (!tasks.isEmpty()) { Map<Long, Long> counts = taskResourceMapper.selectList(new LambdaQueryWrapper<TaskResourceRel>().in(TaskResourceRel::getTaskId, tasks.stream().map(ClassTaskVO::getTaskId).toList())).stream().collect(Collectors.groupingBy(TaskResourceRel::getTaskId, Collectors.counting())); tasks.forEach(t -> t.setResourceCount(counts.getOrDefault(t.getTaskId(), 0L))); }
        vo.setRecentResources(browseService.recent(user, 5));
        vo.getSummary().setClassCount(vo.getClasses().size()); vo.getSummary().setPendingTaskCount(vo.getPendingTasks().size()); vo.getSummary().setRecentResourceCount(vo.getRecentResources().size());
        return vo;
    }

    private StudentProfile requireStudent(AuthCurrentUserVO user) {
        if (user == null || !"student".equalsIgnoreCase(user.getRoleCode()) || user.getAccountId() == null) throw new IllegalArgumentException("需要学生账号");
        StudentProfile student = studentMapper.selectOne(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getAccountId, user.getAccountId()).eq(StudentProfile::getStatus, "active").last("LIMIT 1"));
        if (student == null) throw new IllegalArgumentException("学生档案不可用"); return student;
    }
}
