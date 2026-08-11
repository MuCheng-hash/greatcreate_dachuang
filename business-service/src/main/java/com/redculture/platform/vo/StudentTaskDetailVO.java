package com.redculture.platform.vo;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data public class StudentTaskDetailVO extends ClassTaskVO { private String taskType; private String submissionRule; private boolean allowLateSubmission; private List<TaskResourceVO> resources = new ArrayList<>(); private StudentTaskSubmissionVO currentSubmission; }
