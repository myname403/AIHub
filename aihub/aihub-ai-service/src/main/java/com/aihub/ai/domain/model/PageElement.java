package com.aihub.ai.domain.model;

/**
 * 页面上的一个「可交互元素」——页面标注的产物。
 *
 * <p>为什么要有它：把整页 HTML 丢给模型既费 token 又会超限，而且 CSS/JS/不可见元素
 * 全是噪音。标注的做法是在页面里跑一段脚本，只挑出人真正能操作的元素，
 * 给每个元素分配一个短引用（{@code ref}），模型只看到这份精简清单。
 *
 * <p>{@code ref} 会同时写回页面的 {@code data-testid} 属性，
 * 因此后续「点击 / 输入」只要按 ref 查回来即可，不依赖任何写死的选择器——
 * 这正是本方案能适配任意网站的原因。
 *
 * @param ref  短引用（如 {@code e1}），模型回传它来指定操作对象
 * @param role 元素角色（button / link / textbox / checkbox ...），让模型理解它是什么
 * @param text 可见文本；输入类元素回退到 placeholder / value
 * @param x    视口内横坐标（模型可据此判断左右/上下关系）
 * @param y    视口内纵坐标（可用于判断是否需要滚动）
 */
public record PageElement(
        String ref,
        String role,
        String text,
        int x,
        int y
) {
}
