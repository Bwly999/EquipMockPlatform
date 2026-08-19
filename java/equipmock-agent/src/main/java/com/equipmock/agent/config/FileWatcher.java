package com.equipmock.agent.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 文件监听（03 §7 / 04 §7-8，D14）：
 *
 * <ul>
 *   <li>WatchService 递归 watch config/groups/**、plugins/、settings.json 所在目录
 *       （新目录创建时动态注册）；</li>
 *   <li>事件 500ms 防抖合并（同一文件多次事件只触发一次回调）；</li>
 *   <li>忽略 *.tmp* / *.bak（写方临时文件协议，04 §5）；</li>
 *   <li>ScheduledExecutorService 每 5s 兜底扫描契约文件 mtime+size
 *       （Windows 偶发丢事件的可靠性保险），两通道共用同一防抖出口；</li>
 *   <li>回调在监听线程上执行（ConfigCenter 内部 synchronized 串行化）。</li>
 * </ul>
 */
public final class FileWatcher {

    /** 变更事件分类回调（ConfigCenter / AgentPremain 装配） */
    public interface Listener {
        /** settings.json 变化（切组/总开关） */
        void onSettingsChanged(Path settingsFile);

        /** config/groups/&lt;group&gt;/ 下文件变化（group=组目录名；活动组才重载） */
        void onGroupFileChanged(String group, Path file);

        /** plugins/plugin-registry.json 变化（M3 预留：通知 PluginService diff） */
        void onPluginRegistryChanged(Path registryFile);
    }

    private static final long DEBOUNCE_MILLIS = 500;
    private static final long POLL_INTERVAL_SECONDS = 5;
    private static final long POLL_TIMEOUT_MILLIS = 200;

    private final Path homeRoot;
    private final Path configDir;
    private final Path groupsDir;
    private final Path pluginsDir;
    private final Path settingsFile;
    private final Path registryFile;
    private final Listener listener;
    private final Logger log;

    private final Object pendingLock = new Object();
    private final Set<String> pendingPaths = new HashSet<String>();
    private volatile long lastEventAt = 0L;

    private final Map<String, String> fingerprints = new ConcurrentHashMap<String, String>();
    private final Set<Path> registeredDirs = new HashSet<Path>();

    private volatile boolean running = false;
    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService pollScheduler;

    public FileWatcher(Path homeRoot, Listener listener, Logger log) {
        this.homeRoot = homeRoot;
        this.configDir = homeRoot.resolve("config");
        this.groupsDir = configDir.resolve("groups");
        this.pluginsDir = homeRoot.resolve("plugins");
        this.settingsFile = homeRoot.resolve("settings.json");
        this.registryFile = pluginsDir.resolve("plugin-registry.json");
        this.listener = listener;
        this.log = log;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        watchService = FileSystems.getDefault().newWatchService();
        registerTree();

        watchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                watchLoop();
            }
        }, "equipmock-file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();

        pollScheduler = Executors.newSingleThreadScheduledExecutor(
                new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "equipmock-file-poller");
                        t.setDaemon(true);
                        return t;
                    }
                });
        snapshotFingerprints(); // 基线：启动时不触发
        pollScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    pollFallback();
                } catch (Throwable t) {
                    log.warning("fallback poll failed: " + t);
                }
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("file watcher started (debounce=" + DEBOUNCE_MILLIS + "ms, "
                + "fallback poll=" + POLL_INTERVAL_SECONDS + "s)");
    }

    public synchronized void stop() {
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 关闭失败可忽略
            }
        }
    }

    // ------------------------------------------------------------------
    // WatchService 通道
    // ------------------------------------------------------------------

    private void registerTree() throws IOException {
        registeredDirs.clear();
        register(homeRoot);
        registerIfDir(configDir);
        registerIfDir(groupsDir);
        registerIfDir(pluginsDir);
        if (Files.isDirectory(groupsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(groupsDir)) {
                for (Path group : stream) {
                    if (Files.isDirectory(group)) {
                        registerIfDir(group);
                    }
                }
            }
        }
    }

    private void registerIfDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            register(dir);
        }
    }

    private void register(Path dir) throws IOException {
        if (registeredDirs.contains(dir)) {
            return;
        }
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        registeredDirs.add(dir);
        log.fine("watching directory: " + dir);
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException closed) {
                return; // stop() 中断
            } catch (ClosedWatchServiceException closed) {
                return;
            }
            if (key != null) {
                drainKey(key);
                key.reset();
            }
            maybeFlush();
        }
    }

    private void drainKey(WatchKey key) {
        Path dir = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                // 溢出：全量兜底由轮询通道覆盖
                continue;
            }
            Object context = event.context();
            if (!(context instanceof Path)) {
                continue;
            }
            Path path = dir.resolve((Path) context);
            String name = path.getFileName().toString();
            if (Files.isDirectory(path)) {
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    try {
                        registerIfDir(path); // 新组目录动态注册
                    } catch (IOException e) {
                        log.warning("failed to register new directory " + path + ": " + e);
                    }
                }
                if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    // Windows 对目录内容变更会报目录条目自身的 MODIFY——
                    // 子文件事件由该目录自身的 watch key 单独上报，此处跳过防重复
                    continue;
                }
                // 目录 CREATE/DELETE 继续走分发（新建组目录/组目录删除，04 §7）
            }
            if (ConfigFiles.isTempOrBackup(name)) {
                continue; // *.tmp* / *.bak 忽略
            }
            addPending(path);
        }
    }

    // ------------------------------------------------------------------
    // 防抖出口（两通道共用）
    // ------------------------------------------------------------------

    private void addPending(Path path) {
        synchronized (pendingLock) {
            pendingPaths.add(path.toString().replace('\\', '/'));
            lastEventAt = System.currentTimeMillis();
        }
    }

    private void maybeFlush() {
        Set<String> toFlush = null;
        synchronized (pendingLock) {
            if (!pendingPaths.isEmpty()
                    && System.currentTimeMillis() - lastEventAt >= DEBOUNCE_MILLIS) {
                toFlush = new HashSet<String>(pendingPaths);
                pendingPaths.clear();
            }
        }
        if (toFlush != null) {
            for (String p : toFlush) {
                try {
                    dispatch(p);
                } catch (Throwable t) {
                    log.warning("file change dispatch failed for " + p + ": " + t);
                }
            }
        }
    }

    /** 事件分类（04 §7 变更语义总表） */
    private void dispatch(String pathString) {
        String settings = settingsFile.toString().replace('\\', '/');
        if (pathString.equals(settings)) {
            log.fine("settings.json changed -> reload settings");
            listener.onSettingsChanged(settingsFile);
            return;
        }
        String registry = registryFile.toString().replace('\\', '/');
        if (pathString.equals(registry)) {
            log.fine("plugin-registry.json changed (M3: PluginService diff)");
            listener.onPluginRegistryChanged(registryFile);
            return;
        }
        String groups = groupsDir.toString().replace('\\', '/');
        if (pathString.startsWith(groups + "/")) {
            String rest = pathString.substring(groups.length() + 1);
            int slash = rest.indexOf('/');
            String group = slash > 0 ? rest.substring(0, slash) : rest;
            log.fine("group file changed: " + pathString + " (group=" + group + ")");
            listener.onGroupFileChanged(group, java.nio.file.Paths.get(pathString));
        }
        // 其余（state.json/logs/等）不反应
    }

    // ------------------------------------------------------------------
    // 兜底轮询通道（mtime+size）
    // ------------------------------------------------------------------

    private void snapshotFingerprints() {
        fingerprints.clear();
        for (String file : contractFiles()) {
            fingerprints.put(file, fingerprint(java.nio.file.Paths.get(file)));
        }
    }

    private Set<String> contractFiles() {
        Set<String> out = new HashSet<String>();
        out.add(settingsFile.toString().replace('\\', '/'));
        out.add(registryFile.toString().replace('\\', '/'));
        if (Files.isDirectory(groupsDir)) {
            try (DirectoryStream<Path> groupStream = Files.newDirectoryStream(groupsDir)) {
                for (Path group : groupStream) {
                    if (!Files.isDirectory(group)) {
                        continue;
                    }
                    try (DirectoryStream<Path> fileStream = Files.newDirectoryStream(group)) {
                        for (Path f : fileStream) {
                            String name = f.getFileName().toString();
                            if (ConfigFiles.isJsonConfig(name)
                                    && !ConfigFiles.isTempOrBackup(name)
                                    && Files.isRegularFile(f)) {
                                out.add(f.toString().replace('\\', '/'));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warning("contract file scan failed: " + e);
            }
        }
        return out;
    }

    private void pollFallback() {
        Map<String, String> current = new HashMap<String, String>();
        boolean changed = false;
        Set<String> files = contractFiles();
        for (String file : files) {
            String fp = fingerprint(java.nio.file.Paths.get(file));
            current.put(file, fp);
            String old = fingerprints.get(file);
            if (old != null && !old.equals(fp)) {
                addPending(java.nio.file.Paths.get(file));
                changed = true;
            }
        }
        // 已删除的契约文件也触发一次（防删文件丢事件）
        for (String old : fingerprints.keySet()) {
            if (!current.containsKey(old)) {
                addPending(java.nio.file.Paths.get(old));
                changed = true;
            }
        }
        fingerprints.keySet().retainAll(current.keySet());
        fingerprints.putAll(current);
        if (changed) {
            log.fine("fallback poll detected change(s)");
            // 由监听线程在下一个防抖窗口 flush；这里主动推一次以防监视线程空闲等待过长
            maybeFlush();
        }
    }

    private static String fingerprint(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (IOException e) {
            return "missing";
        }
    }
}
