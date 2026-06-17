package com.redculture.platform.vo;

import lombok.Data;

@Data
public class EventSummaryVO {

    private Long eventId;

    private String eventName;

    private String eventTimeText;

    private String summary;
}
