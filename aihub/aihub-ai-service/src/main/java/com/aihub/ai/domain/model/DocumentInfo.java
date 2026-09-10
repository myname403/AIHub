package com.aihub.ai.domain.model;

/**
 * 文档概要信息。
 */
public record DocumentInfo(
        Long id,
        Long kbId,
        String name,
        String fileType,
        Integer status,
        String errorMsg
) {
    /* 状态：0待处理 1处理中 2完成 3失败 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;
}
