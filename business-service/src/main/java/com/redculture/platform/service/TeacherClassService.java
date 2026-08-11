package com.redculture.platform.service;

import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;

import java.util.List;

public interface TeacherClassService {
    List<TeacherClassVO> listMine(AuthCurrentUserVO user);
    List<ClassTeacherVO> availableTeachers(AuthCurrentUserVO user);
    TeacherClassVO create(TeacherClassSaveRequest request, AuthCurrentUserVO user);
    TeacherClassVO update(Long classId, TeacherClassSaveRequest request, AuthCurrentUserVO user);
    TeacherClassDetailVO detail(Long classId, AuthCurrentUserVO user);
    List<ClassStudentVO> students(Long classId, AuthCurrentUserVO user);
    List<ClassStudentVO> availableStudents(Long classId, AuthCurrentUserVO user);
    void addStudent(Long classId, Long studentId, AuthCurrentUserVO user);
    void removeStudent(Long classId, Long studentId, AuthCurrentUserVO user);
    TeacherClassImportResultVO importStudents(Long classId, ClassStudentImportRequest request, AuthCurrentUserVO user);
    String rotateInviteCode(Long classId, AuthCurrentUserVO user);
    void disableInviteCode(Long classId, AuthCurrentUserVO user);
    TeacherClassVO joinByInvite(InviteJoinRequest request, AuthCurrentUserVO user);
    List<ClassTaskVO> tasks(Long classId, AuthCurrentUserVO user);
    ClassTaskVO publishTask(Long classId, ClassTaskSaveRequest request, AuthCurrentUserVO user);
    List<ClassTaskVO> studentTasks(AuthCurrentUserVO user);
    void completeTask(Long taskId, AuthCurrentUserVO user);
}
