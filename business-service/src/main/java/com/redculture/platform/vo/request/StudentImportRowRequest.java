package com.redculture.platform.vo.request;

import lombok.Data;

/** 学生导入行请求参数。 */
@Data
public class StudentImportRowRequest {
    /** 用户名。 */
    private String username;
    /** 用户提交的登录密码。 */
    private String password;
    /** 真实名称。 */
    private String realName;
    /** 学生编号。 */
    private String studentNo;
    /** 学校标识。 */
    private Long schoolId;
    /** 班级标识。 */
    private Long classId;
    /** 年级名称。 */
    private String gradeName;
    /** 手机号码。 */
    private String phone;
    /** 电子邮箱地址。 */
    private String email;
    /** 入学年份。 */
    private Integer enrollmentYear;
}
