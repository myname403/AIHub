package com.aihub.ai.infra.outbox;

import com.aihub.ai.infra.persistence.mapper.AiEventOutboxMapper;
import com.aihub.api.client.PlatformClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 发件箱写入单测：正常入队 + 参数不完整跳过 + <b>落库失败绝不向上抛</b>。
 *
 * <p>最后一条是重点：调用方是在"上报已经失败"的兜底分支里调用本类的，
 * 这里再抛异常会把原始错误挤出堆栈（排查时只看得到一个次生异常）。
 */
class DbEventOutboxTest {

    private final AiEventOutboxMapper outboxMapper = Mockito.mock(AiEventOutboxMapper.class);
    private final DbEventOutbox outbox = new DbEventOutbox(outboxMapper);

    private static PlatformClient.UsageReport report() {
        return new PlatformClient.UsageReport(1L, 9L, "2", "m", 10, 20, 5L, "chat");
    }

    @Test
    void enqueueWritesPendingRow() {
        outbox.enqueue(1L, "usage.report", report());

        Mockito.verify(outboxMapper).insertEvent(Mockito.anyLong(), Mockito.eq(1L),
                Mockito.eq("usage.report"), Mockito.contains("\"action\":\"chat\""));
    }

    @Test
    void incompleteArgumentsAreSkipped() {
        outbox.enqueue(null, "usage.report", report());
        outbox.enqueue(1L, null, report());
        outbox.enqueue(1L, "usage.report", null);

        Mockito.verifyNoInteractions(outboxMapper);
    }

    @Test
    void mapperFailureIsSwallowed() {
        Mockito.when(outboxMapper.insertEvent(Mockito.anyLong(), Mockito.anyLong(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new RuntimeException("数据库不可用"));

        assertThatCode(() -> outbox.enqueue(1L, "usage.report", report()))
                .doesNotThrowAnyException();
    }
}
