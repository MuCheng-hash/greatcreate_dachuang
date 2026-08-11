package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.*;
import com.redculture.platform.mapper.*;
import com.redculture.platform.service.TeacherClassService;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TeacherClassServiceImpl implements TeacherClassService {
    private static final Set<String> CLASS_TYPES = Set.of("administrative", "teaching");
    private static final String ACTIVE = "active";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final ClassInfoMapper classMapper;
    private final ClassTeacherMapper classTeacherMapper;
    private final ClassMemberMapper classMemberMapper;
    private final TeacherProfileMapper teacherMapper;
    private final StudentProfileMapper studentMapper;
    private final ClassLearningTaskMapper taskMapper;
    private final StudentTaskProgressMapper progressMapper;
    private final TaskResourceRelMapper taskResourceMapper;
    private final LocalEduResourceMapper resourceMapper;

    public TeacherClassServiceImpl(ClassInfoMapper classMapper, ClassTeacherMapper classTeacherMapper,
                                   ClassMemberMapper classMemberMapper, TeacherProfileMapper teacherMapper,
                                   StudentProfileMapper studentMapper, ClassLearningTaskMapper taskMapper,
                                   StudentTaskProgressMapper progressMapper, TaskResourceRelMapper taskResourceMapper,
                                   LocalEduResourceMapper resourceMapper) {
        this.classMapper = classMapper;
        this.classTeacherMapper = classTeacherMapper;
        this.classMemberMapper = classMemberMapper;
        this.teacherMapper = teacherMapper;
        this.studentMapper = studentMapper;
        this.taskMapper = taskMapper;
        this.progressMapper = progressMapper;
        this.taskResourceMapper = taskResourceMapper;
        this.resourceMapper = resourceMapper;
    }

    @Override
    public List<TeacherClassVO> listMine(AuthCurrentUserVO user) {
        requireTeacherOrAdmin(user);
        List<ClassInfo> classes;
        if (isAdmin(user)) {
            classes = classMapper.selectList(new LambdaQueryWrapper<ClassInfo>()
                    .eq(user.getSchoolId() != null, ClassInfo::getSchoolId, user.getSchoolId())
                    .eq(ClassInfo::getStatus, ACTIVE).orderByAsc(ClassInfo::getGradeName).orderByAsc(ClassInfo::getClassName));
        } else {
            TeacherProfile teacher = requireTeacher(user);
            List<Long> ids = classTeacherMapper.selectList(new LambdaQueryWrapper<ClassTeacher>()
                            .eq(ClassTeacher::getTeacherId, teacher.getTeacherId()).eq(ClassTeacher::getStatus, ACTIVE))
                    .stream().map(ClassTeacher::getClassId).toList();
            if (ids.isEmpty()) return Collections.emptyList();
            classes = classMapper.selectBatchIds(ids).stream().filter(item -> ACTIVE.equals(item.getStatus())).toList();
        }
        return classes.stream().map(item -> toClassVO(item, user, false)).toList();
    }

    @Override
    public List<ClassTeacherVO> availableTeachers(AuthCurrentUserVO user) {
        requireTeacherOrAdmin(user);
        if (user.getSchoolId() == null) throw new IllegalArgumentException("school account is required");
        return teacherMapper.selectList(new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getSchoolId, user.getSchoolId())
                        .eq(TeacherProfile::getStatus, ACTIVE).orderByAsc(TeacherProfile::getTeacherName)).stream()
                .map(profile -> { ClassTeacherVO vo = new ClassTeacherVO(); vo.setTeacherId(profile.getTeacherId()); vo.setTeacherName(profile.getTeacherName()); return vo; }).toList();
    }

    @Override
    @Transactional
    public TeacherClassVO create(TeacherClassSaveRequest request, AuthCurrentUserVO user) {
        requireTeacherOrAdmin(user);
        validateSaveRequest(request, user);
        ClassInfo entity = new ClassInfo();
        fillClass(entity, request);
        entity.setStatus(ACTIVE);
        classMapper.insert(entity);
        replaceTeachers(entity.getClassId(), request);
        // The creator may deliberately have no class-teacher relation after creation.
        return joinedClassVO(entity);
    }

    @Override
    @Transactional
    public TeacherClassVO update(Long classId, TeacherClassSaveRequest request, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireHeadTeacherOrAdmin(entity, user);
        if (request != null && request.getSchoolId() != null && !request.getSchoolId().equals(entity.getSchoolId())) {
            throw new IllegalArgumentException("class school cannot be changed");
        }
        if (request != null) request.setSchoolId(entity.getSchoolId());
        validateSaveRequest(request, user);
        fillClass(entity, request);
        classMapper.updateById(entity);
        replaceTeachers(entity.getClassId(), request);
        return toClassVO(entity, user, false);
    }

    @Override
    public TeacherClassDetailVO detail(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        Access access = requireClassAccess(entity, user);
        TeacherClassDetailVO detail = new TeacherClassDetailVO();
        copy(toClassVO(entity, user, true), detail);
        detail.setCanManageStudents(access.headTeacher || isAdmin(user));
        detail.setStudents(students(classId, user));
        detail.setTasks(tasks(classId, user));
        return detail;
    }

    @Override
    public List<ClassStudentVO> students(Long classId, AuthCurrentUserVO user) {
        requireClassAccess(requireClass(classId), user);
        List<ClassMember> members = classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId).eq(ClassMember::getStatus, ACTIVE));
        if (members.isEmpty()) return Collections.emptyList();
        Map<Long, StudentProfile> students = studentMapper.selectBatchIds(members.stream().map(ClassMember::getStudentId).toList())
                .stream().collect(Collectors.toMap(StudentProfile::getStudentId, Function.identity()));
        return members.stream().map(member -> {
            StudentProfile student = students.get(member.getStudentId());
            if (student == null) return null;
            ClassStudentVO vo = new ClassStudentVO();
            vo.setStudentId(student.getStudentId()); vo.setStudentNo(student.getStudentNo());
            vo.setStudentName(student.getStudentName()); vo.setGradeName(student.getGradeName()); vo.setMemberStatus(member.getStatus());
            return vo;
        }).filter(Objects::nonNull).sorted(Comparator.comparing(ClassStudentVO::getStudentNo, Comparator.nullsLast(String::compareTo))).toList();
    }

    @Override
    public List<ClassStudentVO> availableStudents(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireHeadTeacherOrAdmin(entity, user);
        Set<Long> enrolled = classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId).eq(ClassMember::getStatus, ACTIVE))
                .stream().map(ClassMember::getStudentId).collect(Collectors.toSet());
        return studentMapper.selectList(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getSchoolId, entity.getSchoolId())
                        .eq(StudentProfile::getStatus, ACTIVE).orderByAsc(StudentProfile::getStudentNo)).stream()
                .filter(student -> !enrolled.contains(student.getStudentId())).map(student -> {
                    ClassStudentVO vo = new ClassStudentVO(); vo.setStudentId(student.getStudentId()); vo.setStudentNo(student.getStudentNo());
                    vo.setStudentName(student.getStudentName()); vo.setGradeName(student.getGradeName()); return vo;
                }).toList();
    }

    @Override
    @Transactional
    public void addStudent(Long classId, Long studentId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireHeadTeacherOrAdmin(entity, user);
        StudentProfile student = studentMapper.selectById(studentId);
        if (student == null || !ACTIVE.equals(student.getStatus()) || !entity.getSchoolId().equals(student.getSchoolId())) {
            throw new IllegalArgumentException("student must be an active student in this school");
        }
        addOrRestoreMember(entity.getClassId(), student.getStudentId(), "manual");
        assignPublishedTasks(entity.getClassId(), student.getStudentId());
    }

    @Override
    @Transactional
    public void removeStudent(Long classId, Long studentId, AuthCurrentUserVO user) {
        requireHeadTeacherOrAdmin(requireClass(classId), user);
        ClassMember member = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId).eq(ClassMember::getStudentId, studentId).last("LIMIT 1"));
        if (member == null || !ACTIVE.equals(member.getStatus())) throw new IllegalArgumentException("student is not in this class");
        member.setStatus("removed");
        member.setPrimaryClass(false);
        classMemberMapper.updateById(member);
    }

    @Override
    @Transactional
    public TeacherClassImportResultVO importStudents(Long classId, ClassStudentImportRequest request, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireHeadTeacherOrAdmin(entity, user);
        TeacherClassImportResultVO result = new TeacherClassImportResultVO();
        List<String> numbers = request == null || request.getStudentNos() == null ? Collections.emptyList() : request.getStudentNos();
        for (String raw : numbers) {
            String studentNo = raw == null ? "" : raw.trim();
            try {
                if (!StringUtils.hasText(studentNo)) throw new IllegalArgumentException("student number is required");
                StudentProfile student = studentMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getSchoolId, entity.getSchoolId()).eq(StudentProfile::getStudentNo, studentNo).last("LIMIT 1"));
                if (student == null || !ACTIVE.equals(student.getStatus())) throw new IllegalArgumentException("student not found or inactive");
                Long existing = classMemberMapper.selectCount(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId)
                        .eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, ACTIVE));
                if (existing > 0) throw new IllegalArgumentException("student is already in this class");
                addOrRestoreMember(classId, student.getStudentId(), "import");
                assignPublishedTasks(classId, student.getStudentId());
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (RuntimeException exception) {
                result.setFailedCount(result.getFailedCount() + 1);
                result.getErrors().add(studentNo + ": " + exception.getMessage());
            }
        }
        return result;
    }

    @Override
    @Transactional
    public String rotateInviteCode(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireHeadTeacherOrAdmin(entity, user);
        for (int i = 0; i < 10; i++) {
            String code = nextInviteCode();
            if (classMapper.selectCount(new LambdaQueryWrapper<ClassInfo>().eq(ClassInfo::getInviteCode, code)) == 0) {
                entity.setInviteCode(code); classMapper.updateById(entity); return code;
            }
        }
        throw new IllegalStateException("could not generate a unique invite code");
    }

    @Override
    public void disableInviteCode(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireHeadTeacherOrAdmin(entity, user);
        entity.setInviteCode(null); classMapper.updateById(entity);
    }

    @Override
    @Transactional
    public TeacherClassVO joinByInvite(InviteJoinRequest request, AuthCurrentUserVO user) {
        if (user == null || !"student".equals(user.getRoleCode())) throw new IllegalArgumentException("student access required");
        if (request == null || !StringUtils.hasText(request.getInviteCode())) throw new IllegalArgumentException("inviteCode is required");
        ClassInfo entity = classMapper.selectOne(new LambdaQueryWrapper<ClassInfo>()
                .eq(ClassInfo::getInviteCode, request.getInviteCode().trim()).eq(ClassInfo::getStatus, ACTIVE).last("LIMIT 1"));
        if (entity == null) throw new IllegalArgumentException("invalid invite code");
        StudentProfile student = requireStudent(user);
        if (!entity.getSchoolId().equals(student.getSchoolId())) throw new IllegalArgumentException("cannot join a class from another school");
        addOrRestoreMember(entity.getClassId(), student.getStudentId(), "invite");
        assignPublishedTasks(entity.getClassId(), student.getStudentId());
        return joinedClassVO(entity);
    }

    @Override
    public List<ClassTaskVO> tasks(Long classId, AuthCurrentUserVO user) {
        requireClassAccess(requireClass(classId), user);
        return taskMapper.selectList(new LambdaQueryWrapper<ClassLearningTask>().eq(ClassLearningTask::getClassId, classId)
                .orderByDesc(ClassLearningTask::getPublishedAt)).stream().map(task -> toTaskVO(task, null)).toList();
    }

    @Override
    @Transactional
    public ClassTaskVO publishTask(Long classId, ClassTaskSaveRequest request, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        Access access = requireClassAccess(entity, user);
        if (!access.teacher || access.teacherId == null) throw new IllegalArgumentException("an assigned teacher must publish the task");
        if (request == null || !StringUtils.hasText(request.getTitle())) throw new IllegalArgumentException("task title is required");
        if (request.getDueAt() != null && request.getDueAt().isBefore(LocalDateTime.now())) throw new IllegalArgumentException("dueAt must be in the future");
        String taskType = StringUtils.hasText(request.getTaskType()) ? request.getTaskType() : "red_culture_learning";
        String rule = StringUtils.hasText(request.getSubmissionRule()) ? request.getSubmissionRule() : "text_required";
        if (!Set.of("red_culture_learning", "map_exploration").contains(taskType)) throw new IllegalArgumentException("unsupported taskType");
        if (!Set.of("text_only", "text_required", "attachment_required", "text_and_attachment").contains(rule)) throw new IllegalArgumentException("unsupported submissionRule");
        List<Long> resourceIds = request.getResourceIds() == null ? Collections.emptyList() : request.getResourceIds().stream().filter(Objects::nonNull).distinct().toList();
        if ("map_exploration".equals(taskType) && resourceIds.isEmpty()) throw new IllegalArgumentException("map exploration tasks require at least one resource");
        for (Long resourceId : resourceIds) {
            LocalEduResource resource = resourceMapper.selectById(resourceId);
            if (resource == null || !Boolean.TRUE.equals(resource.getActive()) || resource.getReviewStatus() != com.redculture.platform.enums.ReviewStatus.APPROVED) throw new IllegalArgumentException("resource is not published");
        }
        ClassLearningTask task = new ClassLearningTask();
        task.setClassId(classId); task.setPublisherTeacherId(access.teacherId); task.setTitle(request.getTitle().trim());
        task.setDescription(request.getDescription()); task.setTaskType(taskType); task.setSubmissionRule(rule); task.setAllowLateSubmission(!Boolean.FALSE.equals(request.getAllowLateSubmission())); task.setPublishedAt(LocalDateTime.now()); task.setDueAt(request.getDueAt()); task.setStatus("published");
        taskMapper.insert(task);
        for (int index = 0; index < resourceIds.size(); index++) { TaskResourceRel rel = new TaskResourceRel(); rel.setTaskId(task.getTaskId()); rel.setResourceId(resourceIds.get(index)); rel.setSortOrder(index); taskResourceMapper.insert(rel); }
        classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId).eq(ClassMember::getStatus, ACTIVE))
                .forEach(member -> insertProgressIfMissing(task.getTaskId(), member.getStudentId()));
        return toTaskVO(task, null);
    }

    @Override
    public List<ClassTaskVO> studentTasks(AuthCurrentUserVO user) {
        StudentProfile student = requireStudent(user);
        List<StudentTaskProgress> progress = progressMapper.selectList(new LambdaQueryWrapper<StudentTaskProgress>()
                .eq(StudentTaskProgress::getStudentId, student.getStudentId()).orderByDesc(StudentTaskProgress::getCreatedAt));
        if (progress.isEmpty()) return Collections.emptyList();
        Map<Long, StudentTaskProgress> byTask = progress.stream().collect(Collectors.toMap(StudentTaskProgress::getTaskId, Function.identity()));
        Set<Long> activeClassIds = classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getStudentId, student.getStudentId())
                        .eq(ClassMember::getStatus, ACTIVE)).stream().map(ClassMember::getClassId).collect(Collectors.toSet());
        return taskMapper.selectBatchIds(byTask.keySet()).stream().filter(task -> "published".equals(task.getStatus()) && activeClassIds.contains(task.getClassId()))
                .sorted(Comparator.comparing(ClassLearningTask::getDueAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(task -> toTaskVO(task, byTask.get(task.getTaskId()))).toList();
    }

    @Override
    @Transactional
    public void completeTask(Long taskId, AuthCurrentUserVO user) {
        StudentProfile student = requireStudent(user);
        StudentTaskProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<StudentTaskProgress>()
                .eq(StudentTaskProgress::getTaskId, taskId).eq(StudentTaskProgress::getStudentId, student.getStudentId()).last("LIMIT 1"));
        if (progress == null) throw new IllegalArgumentException("task is not assigned to this student");
        if (!"completed".equals(progress.getStatus())) {
            progress.setStatus("completed"); progress.setCompletedAt(LocalDateTime.now()); progressMapper.updateById(progress);
        }
    }

    private void validateSaveRequest(TeacherClassSaveRequest request, AuthCurrentUserVO user) {
        if (request == null || request.getSchoolId() == null || !StringUtils.hasText(request.getClassName()) || !StringUtils.hasText(request.getClassType()))
            throw new IllegalArgumentException("schoolId, className and classType are required");
        if (!CLASS_TYPES.contains(request.getClassType())) throw new IllegalArgumentException("classType must be administrative or teaching");
        requireSchoolAccess(request.getSchoolId(), user);
        if (request.getHeadTeacherId() != null) requireActiveTeacher(request.getHeadTeacherId(), request.getSchoolId());
        for (Long id : request.getSubjectTeacherIds() == null ? Collections.<Long>emptyList() : request.getSubjectTeacherIds()) requireActiveTeacher(id, request.getSchoolId());
    }

    private void fillClass(ClassInfo entity, TeacherClassSaveRequest request) {
        entity.setSchoolId(request.getSchoolId()); entity.setClassName(request.getClassName().trim());
        entity.setGradeName(request.getGradeName()); entity.setClassType(request.getClassType());
    }

    private void replaceTeachers(Long classId, TeacherClassSaveRequest request) {
        classTeacherMapper.delete(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getClassId, classId));
        Set<Long> subjectIds = new LinkedHashSet<>(request.getSubjectTeacherIds() == null ? Collections.emptyList() : request.getSubjectTeacherIds());
        if (request.getHeadTeacherId() != null) insertTeacher(classId, request.getHeadTeacherId(), "head_teacher");
        for (Long id : subjectIds) if (id != null && !id.equals(request.getHeadTeacherId())) insertTeacher(classId, id, "subject_teacher");
    }

    private void insertTeacher(Long classId, Long teacherId, String role) {
        ClassTeacher relation = new ClassTeacher(); relation.setClassId(classId); relation.setTeacherId(teacherId); relation.setTeacherRole(role); relation.setStatus(ACTIVE); classTeacherMapper.insert(relation);
    }

    private void addOrRestoreMember(Long classId, Long studentId, String source) {
        ClassMember member = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId)
                .eq(ClassMember::getStudentId, studentId).last("LIMIT 1"));
        if (member == null) {
            member = new ClassMember(); member.setClassId(classId); member.setStudentId(studentId); member.setPrimaryClass(false); classMemberMapper.insert(member);
        }
        member.setJoinSource(source); member.setStatus(ACTIVE); member.setPrimaryClass(false); classMemberMapper.updateById(member);
    }

    private void assignPublishedTasks(Long classId, Long studentId) {
        taskMapper.selectList(new LambdaQueryWrapper<ClassLearningTask>().eq(ClassLearningTask::getClassId, classId).eq(ClassLearningTask::getStatus, "published"))
                .forEach(task -> insertProgressIfMissing(task.getTaskId(), studentId));
    }

    private void insertProgressIfMissing(Long taskId, Long studentId) {
        if (progressMapper.selectCount(new LambdaQueryWrapper<StudentTaskProgress>().eq(StudentTaskProgress::getTaskId, taskId)
                .eq(StudentTaskProgress::getStudentId, studentId)) > 0) return;
        StudentTaskProgress progress = new StudentTaskProgress(); progress.setTaskId(taskId); progress.setStudentId(studentId); progress.setStatus("pending"); progressMapper.insert(progress);
    }

    private TeacherClassVO toClassVO(ClassInfo entity, AuthCurrentUserVO user, boolean includeInvite) {
        TeacherClassVO vo = new TeacherClassVO();
        vo.setClassId(entity.getClassId()); vo.setSchoolId(entity.getSchoolId()); vo.setClassName(entity.getClassName());
        vo.setGradeName(entity.getGradeName()); vo.setClassType(entity.getClassType()); vo.setStatus(entity.getStatus());
        Access access = requireClassAccess(entity, user); vo.setHeadTeacher(access.headTeacher); if (includeInvite && (access.headTeacher || isAdmin(user))) vo.setInviteCode(entity.getInviteCode());
        List<ClassTeacher> relations = classTeacherMapper.selectList(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getClassId, entity.getClassId()).eq(ClassTeacher::getStatus, ACTIVE));
        Map<Long, TeacherProfile> teachers = relations.isEmpty() ? Collections.emptyMap() : teacherMapper.selectBatchIds(relations.stream().map(ClassTeacher::getTeacherId).toList()).stream().collect(Collectors.toMap(TeacherProfile::getTeacherId, Function.identity()));
        vo.setTeachers(relations.stream().map(rel -> { ClassTeacherVO item = new ClassTeacherVO(); item.setTeacherId(rel.getTeacherId()); item.setTeacherRole(rel.getTeacherRole()); TeacherProfile profile = teachers.get(rel.getTeacherId()); item.setTeacherName(profile == null ? "Unknown" : profile.getTeacherName()); return item; }).toList());
        vo.setStudentCount(classMemberMapper.selectCount(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, entity.getClassId()).eq(ClassMember::getStatus, ACTIVE)));
        List<ClassLearningTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ClassLearningTask>().eq(ClassLearningTask::getClassId, entity.getClassId()).eq(ClassLearningTask::getStatus, "published"));
        vo.setActiveTaskCount(tasks.size()); long completed = 0; long overdue = 0; long total = 0;
        for (ClassLearningTask task : tasks) { ClassTaskVO summary = toTaskVO(task, null); completed += summary.getCompletedCount(); overdue += summary.getOverdueCount(); total += summary.getTotalCount(); }
        vo.setCompletedTaskCount(completed); vo.setOverdueTaskCount(overdue); vo.setCompletionRate(total == 0 ? 0 : Math.round(completed * 10000.0 / total) / 100.0);
        return vo;
    }

    private ClassTaskVO toTaskVO(ClassLearningTask task, StudentTaskProgress studentProgress) {
        ClassTaskVO vo = new ClassTaskVO(); vo.setTaskId(task.getTaskId()); vo.setClassId(task.getClassId()); vo.setTitle(task.getTitle()); vo.setDescription(task.getDescription()); vo.setPublishedAt(task.getPublishedAt()); vo.setDueAt(task.getDueAt()); vo.setStatus(task.getStatus()); vo.setTaskType(task.getTaskType()); vo.setSubmissionRule(task.getSubmissionRule()); vo.setAllowLateSubmission(Boolean.TRUE.equals(task.getAllowLateSubmission()));
        TeacherProfile publisher = task.getPublisherTeacherId() == null ? null : teacherMapper.selectById(task.getPublisherTeacherId()); vo.setPublisherName(publisher == null ? "School administrator" : publisher.getTeacherName());
        List<StudentTaskProgress> progress = progressMapper.selectList(new LambdaQueryWrapper<StudentTaskProgress>().eq(StudentTaskProgress::getTaskId, task.getTaskId()));
        long completed = progress.stream().filter(item -> "completed".equals(item.getStatus())).count();
        boolean overdue = task.getDueAt() != null && task.getDueAt().isBefore(LocalDateTime.now());
        long overdueCount = overdue ? progress.stream().filter(item -> !"completed".equals(item.getStatus())).count() : 0;
        vo.setTotalCount(progress.size()); vo.setCompletedCount(completed); vo.setOverdueCount(overdueCount);
        if (studentProgress != null) vo.setStudentStatus("completed".equals(studentProgress.getStatus()) ? "completed" : overdue ? "overdue" : "pending");
        return vo;
    }

    private TeacherClassVO joinedClassVO(ClassInfo entity) {
        TeacherClassVO vo = new TeacherClassVO();
        vo.setClassId(entity.getClassId()); vo.setSchoolId(entity.getSchoolId()); vo.setClassName(entity.getClassName());
        vo.setGradeName(entity.getGradeName()); vo.setClassType(entity.getClassType()); vo.setStatus(entity.getStatus());
        return vo;
    }

    private ClassInfo requireClass(Long classId) { ClassInfo entity = classMapper.selectById(classId); if (entity == null || !ACTIVE.equals(entity.getStatus())) throw new IllegalArgumentException("class not found"); return entity; }
    private TeacherProfile requireTeacher(AuthCurrentUserVO user) { TeacherProfile teacher = teacherMapper.selectOne(new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getAccountId, user.getAccountId()).eq(TeacherProfile::getStatus, ACTIVE).last("LIMIT 1")); if (teacher == null) throw new IllegalArgumentException("active teacher profile is required"); return teacher; }
    private StudentProfile requireStudent(AuthCurrentUserVO user) { if (user == null || !"student".equals(user.getRoleCode())) throw new IllegalArgumentException("student access required"); StudentProfile student = studentMapper.selectOne(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getAccountId, user.getAccountId()).eq(StudentProfile::getStatus, ACTIVE).last("LIMIT 1")); if (student == null) throw new IllegalArgumentException("active student profile is required"); return student; }
    private TeacherProfile requireActiveTeacher(Long teacherId, Long schoolId) { if (teacherId == null) throw new IllegalArgumentException("teacherId is required"); TeacherProfile teacher = teacherMapper.selectById(teacherId); if (teacher == null || !ACTIVE.equals(teacher.getStatus()) || !schoolId.equals(teacher.getSchoolId())) throw new IllegalArgumentException("teacher must be active and in this school"); return teacher; }
    private void requireTeacherOrAdmin(AuthCurrentUserVO user) { if (user == null || !Set.of("teacher", "school_admin", "platform_admin").contains(user.getRoleCode())) throw new IllegalArgumentException("teacher access required"); if (!"platform_admin".equals(user.getRoleCode()) && user.getSchoolId() == null) throw new IllegalArgumentException("school account is required"); }
    private void requireSchoolAccess(Long schoolId, AuthCurrentUserVO user) { requireTeacherOrAdmin(user); if (!"platform_admin".equals(user.getRoleCode()) && !schoolId.equals(user.getSchoolId())) throw new IllegalArgumentException("cannot access another school"); }
    private Access requireClassAccess(ClassInfo entity, AuthCurrentUserVO user) { requireTeacherOrAdmin(user); requireSchoolAccess(entity.getSchoolId(), user); if (isAdmin(user)) return new Access(null, true, true); TeacherProfile teacher = requireTeacher(user); ClassTeacher relation = classTeacherMapper.selectOne(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getClassId, entity.getClassId()).eq(ClassTeacher::getTeacherId, teacher.getTeacherId()).eq(ClassTeacher::getStatus, ACTIVE).last("LIMIT 1")); if (relation == null) throw new IllegalArgumentException("cannot access this class"); return new Access(teacher.getTeacherId(), true, "head_teacher".equals(relation.getTeacherRole())); }
    private void requireHeadTeacherOrAdmin(ClassInfo entity, AuthCurrentUserVO user) { if (!requireClassAccess(entity, user).headTeacher && !isAdmin(user)) throw new IllegalArgumentException("head teacher access required"); }
    private boolean isAdmin(AuthCurrentUserVO user) { return user != null && Set.of("school_admin", "platform_admin").contains(user.getRoleCode()); }
    private String nextInviteCode() { StringBuilder result = new StringBuilder(10); for (int i = 0; i < 10; i++) result.append(INVITE_ALPHABET[RANDOM.nextInt(INVITE_ALPHABET.length)]); return result.toString(); }
    private void copy(TeacherClassVO source, TeacherClassDetailVO target) { target.setClassId(source.getClassId()); target.setSchoolId(source.getSchoolId()); target.setClassName(source.getClassName()); target.setGradeName(source.getGradeName()); target.setClassType(source.getClassType()); target.setInviteCode(source.getInviteCode()); target.setStatus(source.getStatus()); target.setHeadTeacher(source.isHeadTeacher()); target.setStudentCount(source.getStudentCount()); target.setActiveTaskCount(source.getActiveTaskCount()); target.setCompletedTaskCount(source.getCompletedTaskCount()); target.setOverdueTaskCount(source.getOverdueTaskCount()); target.setCompletionRate(source.getCompletionRate()); target.setTeachers(source.getTeachers()); }
    private record Access(Long teacherId, boolean teacher, boolean headTeacher) { }
}
