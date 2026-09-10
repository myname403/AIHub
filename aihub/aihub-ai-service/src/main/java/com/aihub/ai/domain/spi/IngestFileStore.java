package com.aihub.ai.domain.spi;

/**
 * 入库原始文件暂存 SPI。
 *
 * <p>为什么需要它：文档入库是异步的，工作线程要读原始字节；失败重试同样要重读。
 * 早先实现把字节放在进程内的 {@code ConcurrentHashMap} 里，
 * <b>服务一重启文件就没了</b>，失败任务再也无法重试。
 *
 * <p>抽成接口是为了让更换后端不影响业务代码：
 * 现在是本地磁盘（{@code infra.storage}），将来换 MinIO / OSS 只需换实现。
 *
 * <p>约定：
 * <ul>
 *   <li>同租户同文档多次上传应覆盖（文档 ID 已保证唯一）；</li>
 *   <li>读取失败返回 {@link java.util.Optional#empty()} 而非抛异常，
 *       便于调用方给出「请重新上传」这类可读提示；</li>
 *   <li>清理失败不应影响主流程（属于垃圾回收性质的操作）。</li>
 * </ul>
 */
public interface IngestFileStore {

    /** 暂存原始文件 */
    void save(Long tenantId, Long docId, byte[] bytes);

    /** 读取暂存的原始文件；不存在或不可读时返回 empty */
    java.util.Optional<byte[]> load(Long tenantId, Long docId);

    /**
     * 任务进入终态后清理暂存文件。
     *
     * <p>注意：<b>失败态不清理</b>——失败任务仍需要文件来重试。
     * 因此调用方只在「成功 / 取消」后调用本方法。
     */
    void delete(Long tenantId, Long docId);
}
