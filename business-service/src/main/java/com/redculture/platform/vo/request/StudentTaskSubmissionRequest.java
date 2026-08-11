package com.redculture.platform.vo.request;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data public class StudentTaskSubmissionRequest { private String content; private List<Long> selectedResourceIds = new ArrayList<>(); }
