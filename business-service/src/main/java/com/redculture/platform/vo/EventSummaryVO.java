package com.redculture.platform.vo;

import lombok.Data;

/** 事件摘要视图对象。 */
@Data
public class EventSummaryVO {

    /** 事件标识。 */
    private Long eventId;

    /** 事件名称。 */
    private String eventName;

    /** 事件时间文本。 */
    private String eventTimeText;

    /** 摘要信息。 */
    private String summary;
}
