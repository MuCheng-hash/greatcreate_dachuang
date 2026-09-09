package com.redculture.platform.vo.request;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
/** 学生任务提交请求参数。 */
@Data public class StudentTaskSubmissionRequest { /** 内容。 */ private String content; /** 已选择资源标识列表。 */ private List<Long> selectedResourceIds = new ArrayList<>(); }
