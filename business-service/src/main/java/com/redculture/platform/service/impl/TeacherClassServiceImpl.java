package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.*;
import com.redculture.platform.mapper.*;
import com.redculture.platform.service.TeacherClassService;
import com.redculture.platform.common.PageResult;
import com.redculture.platform.vo.*;
import com.redculture.platform.vo.request.*;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;

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
    private final com.redculture.platform.service.TaskSubmissionStorageService storage;

    public TeacherClassServiceImpl(ClassInfoMapper classMapper, ClassTeacherMapper classTeacherMapper,
                                   ClassMemberMapper classMemberMapper, TeacherProfileMapper teacherMapper,
                                   StudentProfileMapper studentMapper, ClassLearningTaskMapper taskMapper,
                                   StudentTaskProgressMapper progressMapper, TaskResourceRelMapper taskResourceMapper,
                                   LocalEduResourceMapper resourceMapper, com.redculture.platform.service.TaskSubmissionStorageService storage) {
        this.classMapper = classMapper;
        this.classTeacherMapper = classTeacherMapper;
        this.classMemberMapper = classMemberMapper;
        this.teacherMapper = teacherMapper;
        this.studentMapper = studentMapper;
        this.taskMapper = taskMapper;
        this.progressMapper = progressMapper;
        this.taskResourceMapper = taskResourceMapper;
        this.resourceMapper = resourceMapper;
        this.storage = storage;
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
        requireAdmin(user);
        if (user.getSchoolId() == null) throw new IllegalArgumentException("需要学校账号");
        return teacherMapper.selectList(new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getSchoolId, user.getSchoolId())
                        .eq(TeacherProfile::getStatus, ACTIVE).orderByAsc(TeacherProfile::getTeacherName)).stream()
                .map(profile -> { ClassTeacherVO vo = new ClassTeacherVO(); vo.setTeacherId(profile.getTeacherId()); vo.setTeacherName(profile.getTeacherName()); return vo; }).toList();
    }

    @Override
    @Transactional
    public TeacherClassVO create(TeacherClassSaveRequest request, AuthCurrentUserVO user) {
        requireAdmin(user);
        validateSaveRequest(request, user);
        ClassInfo entity = new ClassInfo();
        fillClass(entity, request);
        entity.setStatus(ACTIVE);
        classMapper.insert(entity);
        replaceTeachers(entity.getClassId(), request);
        // 创建者在创建后可能有意不建立班主任关系。
        return joinedClassVO(entity);
    }

    @Override
    @Transactional
    public TeacherClassVO update(Long classId, TeacherClassSaveRequest request, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireAdmin(user);
        if (request != null && request.getSchoolId() != null && !request.getSchoolId().equals(entity.getSchoolId())) {
            throw new IllegalArgumentException("班级所属学校不能修改");
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
        detail.setCanManageStudents(isAdmin(user));
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
        requireAdmin(user);
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
        requireAdmin(user);
        StudentProfile student = studentMapper.selectById(studentId);
        if (student == null || !ACTIVE.equals(student.getStatus()) || !entity.getSchoolId().equals(student.getSchoolId())) {
            throw new IllegalArgumentException("学生必须是本校在读学生");
        }
        addOrRestoreMember(entity.getClassId(), student.getStudentId(), "manual");
        assignPublishedTasks(entity.getClassId(), student.getStudentId());
    }

    @Override
    @Transactional
    public void removeStudent(Long classId, Long studentId, AuthCurrentUserVO user) {
        requireAdmin(user);
        ClassMember member = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId).eq(ClassMember::getStudentId, studentId).last("LIMIT 1"));
        if (member == null || !ACTIVE.equals(member.getStatus())) throw new IllegalArgumentException("学生不在该班级中");
        member.setStatus("removed");
        member.setPrimaryClass(false);
        classMemberMapper.updateById(member);
    }

    @Override
    @Transactional
    public TeacherClassImportResultVO importStudents(Long classId, ClassStudentImportRequest request, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireAdmin(user);
        TeacherClassImportResultVO result = new TeacherClassImportResultVO();
        List<String> numbers = request == null || request.getStudentNos() == null ? Collections.emptyList() : request.getStudentNos();
        for (String raw : numbers) {
            String studentNo = raw == null ? "" : raw.trim();
            try {
                if (!StringUtils.hasText(studentNo)) throw new IllegalArgumentException("学号不能为空");
                StudentProfile student = studentMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getSchoolId, entity.getSchoolId()).eq(StudentProfile::getStudentNo, studentNo).last("LIMIT 1"));
                if (student == null || !ACTIVE.equals(student.getStatus())) throw new IllegalArgumentException("学生不存在或未启用");
                Long existing = classMemberMapper.selectCount(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId)
                        .eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, ACTIVE));
                if (existing > 0) throw new IllegalArgumentException("学生已在该班级中");
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
    public TeacherClassImportResultVO importStudentsExcel(Long classId, MultipartFile file, AuthCurrentUserVO user) {
        requireAdmin(user);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 Excel 文件");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("仅支持 .xlsx 文件");
        List<String> studentNos = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 1) throw new IllegalArgumentException("Excel 文件没有数据");
            Row header = sheet.getRow(sheet.getFirstRowNum());
            int studentNoColumn = -1;
            DataFormatter formatter = new DataFormatter();
            for (Cell cell : header) {
                String value = formatter.formatCellValue(cell).trim();
                if (Set.of("学号", "studentNo", "student_no", "学生学号").contains(value)) { studentNoColumn = cell.getColumnIndex(); break; }
            }
            if (studentNoColumn < 0) throw new IllegalArgumentException("Excel 首行必须包含“学号”列");
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex); if (row == null) continue;
                String studentNo = formatter.formatCellValue(row.getCell(studentNoColumn)).trim();
                if (StringUtils.hasText(studentNo)) studentNos.add(studentNo);
            }
        } catch (IllegalArgumentException exception) { throw exception;
        } catch (Exception exception) { throw new IllegalArgumentException("无法读取 Excel 文件：" + exception.getMessage()); }
        ClassStudentImportRequest request = new ClassStudentImportRequest();
        request.setStudentNos(studentNos);
        return importStudents(classId, request, user);
    }

    @Override
    @Transactional
    public String rotateInviteCode(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireAdmin(user);
        for (int i = 0; i < 10; i++) {
            String code = nextInviteCode();
            if (classMapper.selectCount(new LambdaQueryWrapper<ClassInfo>().eq(ClassInfo::getInviteCode, code)) == 0) {
                entity.setInviteCode(code); classMapper.updateById(entity); return code;
            }
        }
        throw new IllegalStateException("无法生成唯一邀请码");
    }

    @Override
    public void disableInviteCode(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        requireAdmin(user);
        entity.setInviteCode(null); classMapper.updateById(entity);
    }

    @Override
    @Transactional
    public TeacherClassVO joinByInvite(InviteJoinRequest request, AuthCurrentUserVO user) {
        if (user == null || !"student".equals(user.getRoleCode())) throw new IllegalArgumentException("需要学生权限");
        if (request == null || !StringUtils.hasText(request.getInviteCode())) throw new IllegalArgumentException("inviteCode 不能为空");
        ClassInfo entity = classMapper.selectOne(new LambdaQueryWrapper<ClassInfo>()
                .eq(ClassInfo::getInviteCode, request.getInviteCode().trim()).eq(ClassInfo::getStatus, ACTIVE).last("LIMIT 1"));
        if (entity == null) throw new IllegalArgumentException("邀请码无效");
        StudentProfile student = requireStudent(user);
        if (!entity.getSchoolId().equals(student.getSchoolId())) throw new IllegalArgumentException("不能加入其他学校的班级");
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
        return publishTask(classId, request, null, user);
    }

    @Override
    @Transactional
    public ClassTaskVO publishTask(Long classId, ClassTaskSaveRequest request, MultipartFile material, AuthCurrentUserVO user) {
        ClassInfo entity = requireClass(classId);
        Access access = requireClassAccess(entity, user);
        if (!access.teacher || access.teacherId == null) throw new IllegalArgumentException("必须由已分配的教师发布任务");
        if (request == null || !StringUtils.hasText(request.getTitle())) throw new IllegalArgumentException("任务标题不能为空");
        if (request.getDueAt() != null && request.getDueAt().isBefore(LocalDateTime.now())) throw new IllegalArgumentException("dueAt 必须为未来时间");
        if (request.getStartAt() != null && request.getDueAt() != null && !request.getDueAt().isAfter(request.getStartAt())) throw new IllegalArgumentException("dueAt 必须晚于 startAt");
        String taskType = StringUtils.hasText(request.getTaskType()) ? request.getTaskType() : "red_culture_learning";
        String rule = StringUtils.hasText(request.getSubmissionRule()) ? request.getSubmissionRule() : "text_required";
        if (!Set.of("red_culture_learning", "map_exploration").contains(taskType)) throw new IllegalArgumentException("不支持的 taskType");
        if (!Set.of("text_only", "text_required", "attachment_required", "text_and_attachment", "image_required", "document_required", "image_or_document", "text_and_image", "text_and_document", "image_and_document", "text_and_image_and_document").contains(rule)) throw new IllegalArgumentException("不支持的 submissionRule");
        List<Long> resourceIds = request.getResourceIds() == null ? Collections.emptyList() : request.getResourceIds().stream().filter(Objects::nonNull).distinct().toList();
        if ("map_exploration".equals(taskType) && resourceIds.isEmpty()) throw new IllegalArgumentException("地图探索任务至少需要一个资源");
        for (Long resourceId : resourceIds) {
            LocalEduResource resource = resourceMapper.selectById(resourceId);
            if (resource == null || !Boolean.TRUE.equals(resource.getActive()) || resource.getReviewStatus() != com.redculture.platform.enums.ReviewStatus.APPROVED) throw new IllegalArgumentException("资源尚未发布");
        }
        com.redculture.platform.service.TaskSubmissionStorageService.StoredFile stored = null;
        if (material != null && !material.isEmpty()) {
            String name = material.getOriginalFilename() == null ? "" : material.getOriginalFilename().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".docx")) throw new IllegalArgumentException("任务材料必须为 DOCX Word 文档");
            stored = storage.store(material);
        }
        ClassLearningTask task = new ClassLearningTask();
        task.setClassId(classId); task.setPublisherTeacherId(access.teacherId); task.setTitle(request.getTitle().trim());
        task.setDescription(request.getDescription()); task.setTaskType(taskType); task.setSubmissionRule(rule); task.setAllowLateSubmission(!Boolean.FALSE.equals(request.getAllowLateSubmission())); task.setPublishedAt(LocalDateTime.now()); task.setStartAt(request.getStartAt()); task.setDueAt(request.getDueAt()); task.setStatus("published");
        if (stored != null) { task.setMaterialFilename(stored.filename()); task.setMaterialStorageKey(stored.key()); task.setMaterialContentType(stored.contentType()); }
        try { taskMapper.insert(task); } catch (RuntimeException e) { if (stored != null) storage.delete(stored.key()); throw e; }
        for (int index = 0; index < resourceIds.size(); index++) { TaskResourceRel rel = new TaskResourceRel(); rel.setTaskId(task.getTaskId()); rel.setResourceId(resourceIds.get(index)); rel.setSortOrder(index); taskResourceMapper.insert(rel); }
        classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId).eq(ClassMember::getStatus, ACTIVE))
                .forEach(member -> insertProgressIfMissing(task.getTaskId(), member.getStudentId()));
        return toTaskVO(task, null);
    }

    @Override
    public TeacherClassService.TaskMaterial downloadTaskMaterial(Long taskId, AuthCurrentUserVO user) {
        ClassLearningTask task = requireTask(taskId);
        if (!"published".equals(task.getStatus()) || !StringUtils.hasText(task.getMaterialStorageKey())) throw new IllegalArgumentException("任务材料不存在");
        boolean allowed = isAdmin(user);
        if (!allowed && "student".equals(user == null ? null : user.getRoleCode())) {
            StudentProfile student = requireStudent(user);
            allowed = classMemberMapper.selectCount(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, task.getClassId()).eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, ACTIVE)) > 0;
        } else if (!allowed && "teacher".equals(user == null ? null : user.getRoleCode())) {
            TeacherProfile teacher = requireTeacher(user); allowed = teacher.getTeacherId().equals(task.getPublisherTeacherId());
        }
        if (!allowed) throw new IllegalArgumentException("没有任务材料访问权限");
        Resource resource = storage.load(task.getMaterialStorageKey());
        return new TeacherClassService.TaskMaterial(resource, task.getMaterialFilename(), task.getMaterialContentType());
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
        if (progress == null) throw new IllegalArgumentException("该任务未分配给当前学生");
        if (!"completed".equals(progress.getStatus())) {
            progress.setStatus("completed"); progress.setCompletedAt(LocalDateTime.now()); progressMapper.updateById(progress);
        }
    }

    @Override
    public List<StudentClassSummaryVO> listStudentClasses(AuthCurrentUserVO user) {
        StudentProfile student = requireStudent(user);
        Map<Long, ClassMember> members = classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, ACTIVE)).stream()
                .collect(Collectors.toMap(ClassMember::getClassId, item -> item, (a, b) -> a));
        if (members.isEmpty()) return Collections.emptyList();
        Map<Long, List<StudentTaskProgress>> progress = progressMapper.selectList(new LambdaQueryWrapper<StudentTaskProgress>()
                .eq(StudentTaskProgress::getStudentId, student.getStudentId())).stream().collect(Collectors.groupingBy(StudentTaskProgress::getTaskId));
        Map<Long, ClassLearningTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ClassLearningTask>().in(ClassLearningTask::getClassId, members.keySet()).eq(ClassLearningTask::getStatus, "published")).stream().collect(Collectors.toMap(ClassLearningTask::getTaskId, item -> item));
        return classMapper.selectBatchIds(members.keySet()).stream().filter(item -> ACTIVE.equals(item.getStatus())).sorted(Comparator.comparing((ClassInfo item) -> !Boolean.TRUE.equals(members.get(item.getClassId()).getPrimaryClass())).thenComparing(ClassInfo::getGradeName, Comparator.nullsLast(String::compareTo)).thenComparing(ClassInfo::getClassName, Comparator.nullsLast(String::compareTo))).map(item -> {
            StudentClassSummaryVO vo = new StudentClassSummaryVO(); vo.setClassId(item.getClassId()); vo.setClassName(item.getClassName()); vo.setGradeName(item.getGradeName()); vo.setPrimary(members.get(item.getClassId()).getPrimaryClass());
            tasks.values().stream().filter(task -> item.getClassId().equals(task.getClassId())).forEach(task -> {
                StudentTaskProgress taskProgress = progress.getOrDefault(task.getTaskId(), List.of()).stream().findFirst().orElse(null);
                String status = statusFor(task, taskProgress);
                if ("completed".equals(status)) vo.setCompletedTaskCount(vo.getCompletedTaskCount() + 1);
                else if ("overdue".equals(status)) vo.setOverdueTaskCount(vo.getOverdueTaskCount() + 1);
                else vo.setPendingTaskCount(vo.getPendingTaskCount() + 1);
            });
            vo.setTeacherCount(classTeacherMapper.selectCount(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getClassId, item.getClassId()).eq(ClassTeacher::getStatus, ACTIVE))); return vo;
        }).toList();
    }

    @Override
    public StudentClassDetailVO studentClassDetail(Long classId, AuthCurrentUserVO user) {
        ClassInfo entity = requireStudentClass(classId, user); StudentProfile student = requireStudent(user);
        StudentClassDetailVO detail = new StudentClassDetailVO(); detail.setClassId(entity.getClassId()); detail.setClassName(entity.getClassName()); detail.setGradeName(entity.getGradeName()); detail.setClassType(entity.getClassType()); detail.setSchoolName(user.getSchoolName());
        ClassMember member = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId).eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, ACTIVE).last("LIMIT 1")); detail.setPrimary(member != null && Boolean.TRUE.equals(member.getPrimaryClass()));
        detail.setStudentCount(classMemberMapper.selectCount(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId).eq(ClassMember::getStatus, ACTIVE)));
        detail.setTeachers(classTeacherMapper.selectList(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getClassId, classId).eq(ClassTeacher::getStatus, ACTIVE)).stream().map(rel -> { TeacherProfile teacher = teacherMapper.selectById(rel.getTeacherId()); ClassTeacherVO vo = new ClassTeacherVO(); vo.setTeacherId(rel.getTeacherId()); vo.setTeacherName(teacher == null ? "教师" : teacher.getTeacherName()); vo.setTeacherRole(rel.getTeacherRole()); return vo; }).toList());
        studentClassTasks(classId, 1L, 100L, user).getRecords().forEach(task -> { String status = task.getStudentStatus(); if ("completed".equals(status)) detail.getTaskSummary().setCompletedCount(detail.getTaskSummary().getCompletedCount() + 1); else if ("submitted".equals(status)) detail.getTaskSummary().setSubmittedCount(detail.getTaskSummary().getSubmittedCount() + 1); else if ("overdue".equals(status)) detail.getTaskSummary().setOverdueCount(detail.getTaskSummary().getOverdueCount() + 1); else detail.getTaskSummary().setPendingCount(detail.getTaskSummary().getPendingCount() + 1); });
        return detail;
    }

    @Override
    public PageResult<ClassTaskVO> studentClassTasks(Long classId, Long pageNum, Long pageSize, AuthCurrentUserVO user) {
        ClassInfo entity = requireStudentClass(classId, user); StudentProfile student = requireStudent(user); long page = pageNum == null || pageNum < 1 ? 1 : pageNum; long size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        List<ClassLearningTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ClassLearningTask>().eq(ClassLearningTask::getClassId, entity.getClassId()).eq(ClassLearningTask::getStatus, "published"));
        Map<Long, StudentTaskProgress> progress = progressMapper.selectList(new LambdaQueryWrapper<StudentTaskProgress>().eq(StudentTaskProgress::getStudentId, student.getStudentId())).stream().collect(Collectors.toMap(StudentTaskProgress::getTaskId, item -> item, (a, b) -> a));
        List<ClassTaskVO> records = tasks.stream().map(task -> toTaskVO(task, progress.get(task.getTaskId()))).sorted(Comparator.comparingInt((ClassTaskVO task) -> ("overdue".equals(task.getStudentStatus()) || "pending".equals(task.getStudentStatus())) ? 0 : 1).thenComparing(ClassTaskVO::getDueAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ClassTaskVO::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int from = (int) Math.min((page - 1) * size, records.size()); int to = (int) Math.min(from + size, records.size()); return PageResult.of(records.subList(from, to), records.size(), page, size);
    }

    @Override
    public PageResult<StudentClassActivityVO> studentClassActivities(Long classId, Long pageNum, Long pageSize, AuthCurrentUserVO user) {
        StudentProfile student = requireStudent(user); requireStudentClass(classId, user); List<StudentClassActivityVO> activities = new ArrayList<>();
        taskMapper.selectList(new LambdaQueryWrapper<ClassLearningTask>().eq(ClassLearningTask::getClassId, classId).eq(ClassLearningTask::getStatus, "published")).forEach(task -> { StudentClassActivityVO item = new StudentClassActivityVO(); item.setActivityType("TASK_PUBLISHED"); item.setTitle("班级发布了新任务"); item.setContent(task.getTitle()); item.setRelatedTaskId(task.getTaskId()); item.setCreatedAt(task.getPublishedAt()); activities.add(item); });
        progressMapper.selectList(new LambdaQueryWrapper<StudentTaskProgress>().eq(StudentTaskProgress::getStudentId, student.getStudentId()).eq(StudentTaskProgress::getStatus, "completed")).forEach(progress -> { ClassLearningTask task = taskMapper.selectById(progress.getTaskId()); if (task != null && classId.equals(task.getClassId())) { StudentClassActivityVO item = new StudentClassActivityVO(); item.setActivityType("TASK_COMPLETED"); item.setTitle("你完成了学习任务"); item.setContent(task.getTitle()); item.setRelatedTaskId(task.getTaskId()); item.setCreatedAt(progress.getCompletedAt()); activities.add(item); } });
        activities.sort(Comparator.comparing(StudentClassActivityVO::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))); long page = pageNum == null || pageNum < 1 ? 1 : pageNum; long size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100); int from = (int)Math.min((page - 1) * size, activities.size()); int to = (int)Math.min(from + size, activities.size()); return PageResult.of(activities.subList(from, to), activities.size(), page, size);
    }

    private ClassInfo requireStudentClass(Long classId, AuthCurrentUserVO user) { StudentProfile student = requireStudent(user); ClassInfo entity = requireClass(classId); boolean joined = classMemberMapper.exists(new LambdaQueryWrapper<ClassMember>().eq(ClassMember::getClassId, classId).eq(ClassMember::getStudentId, student.getStudentId()).eq(ClassMember::getStatus, ACTIVE)); if (!joined) throw new IllegalArgumentException("班级不存在"); return entity; }

    private String statusFor(ClassLearningTask task, StudentTaskProgress progress) { if (progress != null && "completed".equals(progress.getStatus())) return "completed"; return task.getDueAt() != null && task.getDueAt().isBefore(LocalDateTime.now()) ? "overdue" : "pending"; }

    private void validateSaveRequest(TeacherClassSaveRequest request, AuthCurrentUserVO user) {
        if (request == null || request.getSchoolId() == null || !StringUtils.hasText(request.getClassName()) || !StringUtils.hasText(request.getClassType()))
            throw new IllegalArgumentException("schoolId、className 和 classType 不能为空");
        if (!CLASS_TYPES.contains(request.getClassType())) throw new IllegalArgumentException("classType 必须为 administrative 或 teaching");
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
        Access access = requireClassAccess(entity, user); vo.setHeadTeacher(access.headTeacher); if (includeInvite && isAdmin(user)) vo.setInviteCode(entity.getInviteCode());
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
        ClassTaskVO vo = new ClassTaskVO(); vo.setTaskId(task.getTaskId()); vo.setClassId(task.getClassId()); vo.setTitle(task.getTitle()); vo.setDescription(task.getDescription()); vo.setPublishedAt(task.getPublishedAt()); vo.setStartAt(task.getStartAt()); vo.setDueAt(task.getDueAt()); vo.setMaterialFilename(task.getMaterialFilename()); vo.setStatus(task.getStatus()); vo.setTaskType(task.getTaskType()); vo.setSubmissionRule(task.getSubmissionRule()); vo.setAllowLateSubmission(Boolean.TRUE.equals(task.getAllowLateSubmission()));
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

    private ClassInfo requireClass(Long classId) { ClassInfo entity = classMapper.selectById(classId); if (entity == null || !ACTIVE.equals(entity.getStatus())) throw new IllegalArgumentException("班级不存在"); return entity; }
    private ClassLearningTask requireTask(Long taskId) { ClassLearningTask task = taskMapper.selectById(taskId); if (task == null) throw new IllegalArgumentException("任务不存在"); return task; }
    private TeacherProfile requireTeacher(AuthCurrentUserVO user) { TeacherProfile teacher = teacherMapper.selectOne(new LambdaQueryWrapper<TeacherProfile>().eq(TeacherProfile::getAccountId, user.getAccountId()).eq(TeacherProfile::getStatus, ACTIVE).last("LIMIT 1")); if (teacher == null) throw new IllegalArgumentException("需要有效的教师档案"); return teacher; }
    private StudentProfile requireStudent(AuthCurrentUserVO user) { if (user == null || !"student".equals(user.getRoleCode())) throw new IllegalArgumentException("需要学生权限"); StudentProfile student = studentMapper.selectOne(new LambdaQueryWrapper<StudentProfile>().eq(StudentProfile::getAccountId, user.getAccountId()).eq(StudentProfile::getStatus, ACTIVE).last("LIMIT 1")); if (student == null) throw new IllegalArgumentException("需要有效的学生档案"); return student; }
    private TeacherProfile requireActiveTeacher(Long teacherId, Long schoolId) { if (teacherId == null) throw new IllegalArgumentException("teacherId 不能为空"); TeacherProfile teacher = teacherMapper.selectById(teacherId); if (teacher == null || !ACTIVE.equals(teacher.getStatus()) || !schoolId.equals(teacher.getSchoolId())) throw new IllegalArgumentException("教师必须处于启用状态且属于本校"); return teacher; }
    private void requireTeacherOrAdmin(AuthCurrentUserVO user) { if (user == null || !Set.of("teacher", "school_admin", "platform_admin").contains(user.getRoleCode())) throw new IllegalArgumentException("需要教师权限"); if (!"platform_admin".equals(user.getRoleCode()) && user.getSchoolId() == null) throw new IllegalArgumentException("需要学校账号"); }
    private void requireAdmin(AuthCurrentUserVO user) { requireTeacherOrAdmin(user); if (!isAdmin(user)) throw new IllegalArgumentException("需要管理员权限"); }
    private void requireSchoolAccess(Long schoolId, AuthCurrentUserVO user) { requireTeacherOrAdmin(user); if (!"platform_admin".equals(user.getRoleCode()) && !schoolId.equals(user.getSchoolId())) throw new IllegalArgumentException("无权访问其他学校"); }
    private Access requireClassAccess(ClassInfo entity, AuthCurrentUserVO user) { requireTeacherOrAdmin(user); requireSchoolAccess(entity.getSchoolId(), user); if (isAdmin(user)) return new Access(null, true, true); TeacherProfile teacher = requireTeacher(user); ClassTeacher relation = classTeacherMapper.selectOne(new LambdaQueryWrapper<ClassTeacher>().eq(ClassTeacher::getClassId, entity.getClassId()).eq(ClassTeacher::getTeacherId, teacher.getTeacherId()).eq(ClassTeacher::getStatus, ACTIVE).last("LIMIT 1")); if (relation == null) throw new IllegalArgumentException("无权访问该班级"); return new Access(teacher.getTeacherId(), true, "head_teacher".equals(relation.getTeacherRole())); }
    private void requireHeadTeacherOrAdmin(ClassInfo entity, AuthCurrentUserVO user) { if (!requireClassAccess(entity, user).headTeacher && !isAdmin(user)) throw new IllegalArgumentException("需要班主任或管理员权限"); }
    private boolean isAdmin(AuthCurrentUserVO user) { return user != null && Set.of("school_admin", "platform_admin").contains(user.getRoleCode()); }
    private String nextInviteCode() { StringBuilder result = new StringBuilder(10); for (int i = 0; i < 10; i++) result.append(INVITE_ALPHABET[RANDOM.nextInt(INVITE_ALPHABET.length)]); return result.toString(); }
    private void copy(TeacherClassVO source, TeacherClassDetailVO target) { target.setClassId(source.getClassId()); target.setSchoolId(source.getSchoolId()); target.setClassName(source.getClassName()); target.setGradeName(source.getGradeName()); target.setClassType(source.getClassType()); target.setInviteCode(source.getInviteCode()); target.setStatus(source.getStatus()); target.setHeadTeacher(source.isHeadTeacher()); target.setStudentCount(source.getStudentCount()); target.setActiveTaskCount(source.getActiveTaskCount()); target.setCompletedTaskCount(source.getCompletedTaskCount()); target.setOverdueTaskCount(source.getOverdueTaskCount()); target.setCompletionRate(source.getCompletionRate()); target.setTeachers(source.getTeachers()); }
    private record Access(Long teacherId, boolean teacher, boolean headTeacher) { }
}
