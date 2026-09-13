package com.aihub.platform.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 图形验证码 VO：id 回传 + base64 图片直接放进 <img> 展示 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthCaptchaRespVO {

    /** 验证码 ID（提交时回传） */
    private String id;

    /** base64 图片（data:image/png;base64,...） */
    private String imageBase64;
}
