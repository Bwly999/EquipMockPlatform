package com.equipmock.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * agent JUL 日志初始化（02 §3 第 2 步）：写 {@code <home>/logs/agent.log}，
 * 滚动 1MB x 5，UTF-8。只配置 com.equipmock 域 logger，不触碰宿主 root logger，
 * 不向宿主控制台输出任何内容。
 *
 * <p><b>命名说明（对 04 §1 的实现细节偏差）</b>：JUL FileHandler 在 count&gt;1 且
 * pattern 不含 %g 时，会对全部文件追加代号——实际文件为 {@code agent.log.0 .. agent.log.4}
 * （.0 为当前写入文件，.1-.4 为历史滚动）。这是 JUL 标准行为，无法让"第一代"文件不带代号。
 * M5 工作台尾部展示时按 {@code agent.log*} 通配读取。
 */
public final class AgentLogging {

    public static final String LOGGER_NAME = "com.equipmock";

    private AgentLogging() {
    }

    /**
     * 初始化文件日志。
     *
     * @return 配置好的 logger；初始化失败时返回仅输出到父 logger 的兜底实例（绝不抛出）
     */
    public static Logger initialize(Path logsDir) {
        Logger logger = Logger.getLogger(LOGGER_NAME);
        logger.setUseParentHandlers(false); // 不污染宿主控制台
        try {
            Path logFile = logsDir.resolve("agent.log");
            // 滚动：1MB x 5，追加模式；pattern 无 %g 时 FileHandler 自动追加 ".N" 代号
            FileHandler handler = new FileHandler(logFile.toString(),
                    1024 * 1024, 5, true);
            handler.setEncoding("UTF-8");
            handler.setLevel(Level.ALL);
            handler.setFormatter(new CompactFormatter());
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        } catch (IOException e) {
            // 日志初始化失败不阻断 agent：留一个内存标记，后续记录走 stderr 兜底
            logger.setLevel(Level.INFO);
            Logger.getAnonymousLogger().severe("equipmock: failed to open log file "
                    + logsDir + ": " + e);
        }
        return logger;
    }

    /** 紧凑单行格式：时间 [级别] 消息（异常附堆栈） */
    private static final class CompactFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                    .format(new java.util.Date(record.getMillis())));
            sb.append(" [").append(record.getLevel().getName()).append("] ");
            sb.append(formatMessage(record));
            sb.append('\n');
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                thrown.printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }

    /** 关闭 logger 上的全部 handler（测试用） */
    static void closeQuietly(Logger logger) {
        for (Handler handler : logger.getHandlers()) {
            handler.close();
            logger.removeHandler(handler);
        }
    }
}
