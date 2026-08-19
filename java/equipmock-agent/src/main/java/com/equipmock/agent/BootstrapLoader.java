package com.equipmock.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLDecoder;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * bootstrap 兜底加载（对 02 §3 的补充增强，M1 任务 4）。
 *
 * <p>优先依赖 MANIFEST 的 {@code Boot-Class-Path: equip-mock-bootstrap.jar}（D4，JLS 标准机制）；
 * 当清单机制未生效（如 agent jar 被复制到没有 bootstrap jar 的目录、或宿主用错误方式挂载）时，
 * 按以下顺序兜底：
 * <ol>
 *   <li>先 {@code Class.forName("com.equipmock.bootstrap.Spy")} 探测（委派 bootstrap loader，
 *       Boot-Class-Path 已生效则直接可见，跳过兜底）；</li>
 *   <li>{@code -Dequipmock.bootstrap.jar} 显式指定路径；</li>
 *   <li>agent jar 同目录下的 {@code equip-mock-bootstrap*.jar}
 *       （agent jar 路径取 {@code AgentPremain.class.getProtectionDomain().getCodeSource()}）；</li>
 *   <li>找到则 {@code instrumentation.appendToBootstrapClassLoaderSearch}。</li>
 * </ol>
 * 找不到且 Spy 不可见：日志写可读错误（提示 Boot-Class-Path / -Dequipmock.bootstrap.jar），
 * agent 降级为"零插桩"模式，<b>绝不阻断宿主启动</b>。
 */
public final class BootstrapLoader {

    private static final String SPY_CLASS_NAME = "com.equipmock.bootstrap.Spy";

    private BootstrapLoader() {
    }

    /**
     * 确保 bootstrap 契约类可见。
     *
     * @return true = Spy 已可见（Boot-Class-Path 或兜底 append 成功）
     */
    public static boolean ensureContractVisible(Instrumentation inst, Logger log) {
        if (isSpyVisible()) {
            log.info("bootstrap contract visible via Boot-Class-Path (no fallback needed)");
            return true;
        }
        log.info("bootstrap contract not visible yet, trying fallback jar location");

        File bootstrapJar = locateBootstrapJar(log);
        if (bootstrapJar == null) {
            log.severe(readableMissingJarMessage());
            return false;
        }
        try {
            // 注意：按 JDK Instrumentation 契约，追加后不得关闭该 JarFile——
            // bootstrap loader 会惰性读取其中的类字节码
            JarFile jarFile = new JarFile(bootstrapJar);
            inst.appendToBootstrapClassLoaderSearch(jarFile);
        } catch (Throwable t) {
            log.severe("failed to append bootstrap jar '" + bootstrapJar + "': " + t);
            return false;
        }
        if (isSpyVisible()) {
            log.info("bootstrap contract appended to bootstrap loader from: " + bootstrapJar);
            return true;
        }
        log.severe("bootstrap jar appended but Spy is still not visible: " + bootstrapJar);
        return false;
    }

    /** 可读的缺 jar 错误信息（M1 验收：拔掉 bootstrap.jar 时报错可读） */
    public static String readableMissingJarMessage() {
        return "equip-mock-bootstrap.jar not found: mock disabled, host keeps running. "
                + "Fix: put equip-mock-bootstrap.jar next to equip-mock-agent.jar "
                + "(referenced by manifest Boot-Class-Path), "
                + "or pass -Dequipmock.bootstrap.jar=/path/to/equip-mock-bootstrap.jar";
    }

    private static boolean isSpyVisible() {
        try {
            Class.forName(SPY_CLASS_NAME);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            // 环境异常同样视为不可见，走兜底流程
            return false;
        }
    }

    /** 按 a) 系统属性 b) agent jar 同目录 的顺序定位 bootstrap jar */
    private static File locateBootstrapJar(Logger log) {
        String explicit = System.getProperty("equipmock.bootstrap.jar");
        if (explicit != null && explicit.trim().length() > 0) {
            File file = new File(explicit.trim());
            if (file.isFile()) {
                return file;
            }
            log.warning("-Dequipmock.bootstrap.jar points to missing file: " + explicit);
        }
        File agentJar = codeSourceOf(AgentPremain.class);
        if (agentJar == null) {
            log.warning("cannot locate agent jar code source");
            return null;
        }
        File dir = agentJar.getParentFile();
        if (dir == null) {
            return null;
        }
        File[] hits = dir.listFiles();
        if (hits == null) {
            return null;
        }
        File best = null;
        for (File candidate : hits) {
            String name = candidate.getName();
            if (candidate.isFile() && name.startsWith("equip-mock-bootstrap")
                    && name.endsWith(".jar")) {
                if (best == null || candidate.getName().compareTo(best.getName()) < 0) {
                    best = candidate; // 多个命中取字典序第一个，保证确定性
                }
            }
        }
        return best;
    }

    /** 取类所在 jar 文件；失败返回 null */
    static File codeSourceOf(Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            if ("file".equals(location.getProtocol())) {
                try {
                    return new File(location.toURI());
                } catch (Exception e) {
                    return new File(URLDecoder.decode(location.getPath(), "UTF-8"));
                }
            }
        } catch (Throwable t) {
            // 任何失败都按"无法定位"处理
        }
        return null;
    }
}
