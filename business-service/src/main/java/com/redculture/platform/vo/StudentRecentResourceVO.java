package com.redculture.platform.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class StudentRecentResourceVO {
    private Long resourceId;
    private String resourceName;
    private String resourceCategory;
    private String address;
    private LocalDateTime viewedAt;
    private Integer viewCount;
}
