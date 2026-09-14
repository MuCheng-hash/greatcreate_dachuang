package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

/** 用户资料保存请求参数。 */
@Data
public class UserProfileSaveRequest {
    /** 账号标识。 */
    private Long accountId;
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
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /** 备注。 */
    private String remark;
    /** 教师编号。 */
    private String teacherNo;
    /** 标题。 */
    private String title;
    /** 学生编号。 */
    private String studentNo;
    /** 年级名称。 */
    private String gradeName;
    /** 入学年份。 */
    private Integer enrollmentYear;
    /** 班级标识列表。 */
    private List<Long> classIds;
    /** 教师班级角色。 */
    private String teacherClassRole;
}
