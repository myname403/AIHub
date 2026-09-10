package com.aihub.ai.domain.model;

/**
 * 模型写入命令（新增 / 修改共用）。
 *
 * <p><b>安全要点</b>：{@code apiKey} 是明文，只在「进入仓储层加密之前」短暂存在。
 * 因此这里显式覆写 {@link #toString()} 把它遮掉——record 默认的 toString 会把
 * 所有字段拼进字符串，一旦被日志或异常栈打印出去就是密钥泄露。
 */
public record ModelCommand(
        String providerCode,
        String modelCode,
        String apiKey,
        String baseUrl,
        Integer vectorDim,
        boolean defaultModel,
        Integer status
) {
    /**
     * 更新时可为空的 Key：{@code null} 表示「不改动已有密钥」。
     *
     * <p>这样管理端编辑模型时不必回填明文（也无法回填，因为拿不到），
     * 留空即保持原值。
     */
    public boolean apiKeyProvided() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ModelCommand[providerCode=" + providerCode
                + ", modelCode=" + modelCode
                + ", apiKey=" + (apiKeyProvided() ? "***" : "null")
                + ", baseUrl=" + baseUrl
                + ", vectorDim=" + vectorDim
                + ", defaultModel=" + defaultModel
                + ", status=" + status + "]";
    }
}
