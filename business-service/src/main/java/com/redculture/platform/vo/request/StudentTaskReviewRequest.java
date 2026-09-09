package com.redculture.platform.vo.request;
import lombok.Data;
/** 学生任务审核请求参数。 */
@Data public class StudentTaskReviewRequest { /** 本次审核动作。 具体取值由任务审核协议定义。 */ private String reviewAction; /** 评语或备注内容。 */ private String comment; /** 年级。 */ private String grade; }
