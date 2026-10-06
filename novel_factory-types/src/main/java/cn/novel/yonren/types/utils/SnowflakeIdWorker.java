package cn.novel.yonren.types.utils;

/**
 * 雪花算法 ID 生成器
 */
public class SnowflakeIdWorker {

    private static final long BEGIN_TIMESTAMP = 1780272000000L;

    private static final int WORKER_BITS = 10;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_BITS + SEQUENCE_BITS;

    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdWorker(long workerId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(
                    "机械码必须在0和" + MAX_WORKER_ID + "之间");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                try {
                    Thread.sleep(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("时钟回调被打断", e);
                }
                timestamp = System.currentTimeMillis();
                if (timestamp < lastTimestamp) {
                    throw new RuntimeException(
                            "时钟回调，拒绝生成ID");
                }
            } else {
                throw new RuntimeException(
                        "时钟回调了" + offset + "ms, 拒绝生成ID");
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = nextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - BEGIN_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    private long nextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}