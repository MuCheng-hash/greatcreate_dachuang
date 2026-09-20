package com.redculture.platform.service;

import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;

import java.util.List;
import com.redculture.platform.common.PageResult;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

public interface TeacherClassService {
    /** 查询当前教师有管理权限的班级，范围由认证账号和所属学校确定。 */
    List<TeacherClassVO> listMine(AuthCurrentUserVO user);
    List<ClassTeacherVO> availableTeachers(AuthCurrentUserVO user);
    /** 创建班级并建立教师关系；服务校验创建者的学校范围。 */
    TeacherClassVO create(TeacherClassSaveRequest request, AuthCurrentUserVO user);
    /** 修改当前教师可管理的班级信息，不允许跨学校或跨班级更新。 */
    TeacherClassVO update(Long classId, TeacherClassSaveRequest request, AuthCurrentUserVO user);
    TeacherClassDetailVO detail(Long classId, AuthCurrentUserVO user);
    List<ClassStudentVO> students(Long classId, AuthCurrentUserVO user);
    List<ClassStudentVO> availableStudents(Long classId, AuthCurrentUserVO user);
    /** 增加学生到教师管理的班级，服务保证学生与班级归属一致。 */
    void addStudent(Long classId, Long studentId, AuthCurrentUserVO user);
    /** 从教师管理的班级移除学生，不删除学生账号或其历史提交。 */
    void removeStudent(Long classId, Long studentId, AuthCurrentUserVO user);
    TeacherClassImportResultVO importStudents(Long classId, ClassStudentImportRequest request, AuthCurrentUserVO user);
    TeacherClassImportResultVO importStudentsExcel(Long classId, MultipartFile file, AuthCurrentUserVO user);
    /** 轮换班级邀请码并使旧邀请码立即失效。 */
    String rotateInviteCode(Long classId, AuthCurrentUserVO user);
    void disableInviteCode(Long classId, AuthCurrentUserVO user);
    /** 学生通过邀请码加入班级，邀请码校验和成员关系写入由服务统一完成。 */
    TeacherClassVO joinByInvite(InviteJoinRequest request, AuthCurrentUserVO user);
    List<ClassTaskVO> tasks(Long classId, AuthCurrentUserVO user);
    /** 教师在有权限的班级内发布任务；重载版本可同时保存任务资料。 */
    ClassTaskVO publishTask(Long classId, ClassTaskSaveRequest request, AuthCurrentUserVO user);
    ClassTaskVO publishTask(Long classId, ClassTaskSaveRequest request, MultipartFile material, AuthCurrentUserVO user);
    record TaskMaterial(Resource resource, String filename, String contentType) {}
    TaskMaterial downloadTaskMaterial(Long taskId, AuthCurrentUserVO user);
    List<ClassTaskVO> studentTasks(AuthCurrentUserVO user);
    /** 学生确认完成自己所在班级的任务，服务负责验证成员资格和任务状态。 */
    void completeTask(Long taskId, AuthCurrentUserVO user);
    List<StudentClassSummaryVO> listStudentClasses(AuthCurrentUserVO user);
    StudentClassDetailVO studentClassDetail(Long classId, AuthCurrentUserVO user);
    PageResult<ClassTaskVO> studentClassTasks(Long classId, Long pageNum, Long pageSize, AuthCurrentUserVO user);
    PageResult<StudentClassActivityVO> studentClassActivities(Long classId, Long pageNum, Long pageSize, AuthCurrentUserVO user);
}
