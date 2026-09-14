package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 用户资料管理端视图对象。 */
@Data
public class UserProfileAdminVO {
    /** 资料标识。 */
    private Long profileId;
    /** 账号标识。 */
    private Long accountId;
    /** 用户名。 */
    private String username;
    /**
     * 资料类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String profileType;
    /** 真实名称。 */
    private String realName;
    /** 性别。 */
    private String gender;
    /** 手机号码。 */
    private String phone;
    /** 电子邮箱地址。 */
    private String email;
    /** 学校标识。 */
    private Long schoolId;
    /** 学校名称。 */
    private String schoolName;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /** 备注。 */
    private String remark;
    /** 教师标识。 */
    private Long teacherId;
    /** 教师编号。 */
    private String teacherNo;
    /** 标题。 */
    private String title;
    /** 学生标识。 */
    private Long studentId;
    /** 学生编号。 */
    private String studentNo;
    /** 年级名称。 */
    private String gradeName;
    /** 入学年份。 */
    private Integer enrollmentYear;
    /** 班级标识列表。 */
    private List<Long> classIds = new ArrayList<>();
    /** 班级名称列表。 */
    private List<String> classNames = new ArrayList<>();
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
