package com.equipmock.agent.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 单测用最小插件 jar 构建器：manifest（Plugin-Id/Version/Requires）+
 * META-INF/extensions.idx（pf4j LegacyExtensionFinder 的扩展索引）+
 * 从 test-classes 拷入的 handler class 文件（改包名不需要——插件类加载器
 * 从 jar 加载，注解/接口经 parent 解析，无冲突）。
 */
final class FixturePluginJar {

    private FixturePluginJar() {
    }

    /**
     * 构建最小插件 jar。
     *
     * @param requires Plugin-Requires 值；null=不写该属性
     * @param handlerFqcns extensions.idx 内容 + 需拷入的 handler 类（测试类编译产物）
     */
    static Path build(Path pluginsDir, String fileName, String pluginId, String version,
                      String requires, String... handlerFqcns) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0");
        attrs.putValue("Plugin-Id", pluginId);
        attrs.putValue("Plugin-Version", version);
        if (requires != null) {
            attrs.putValue("Plugin-Requires", requires);
        }
        Path jar = pluginsDir.resolve(fileName);
        JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
        try {
            StringBuilder idx = new StringBuilder();
            idx.append("# fixture extensions\n");
            for (String fqcn : handlerFqcns) {
                idx.append(fqcn).append('\n');
                writeClass(out, fqcn);
            }
            writeEntry(out, "META-INF/extensions.idx",
                    idx.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return jar;
    }

    private static void writeClass(JarOutputStream out, String fqcn) throws IOException {
        String resource = "/" + fqcn.replace('.', '/') + ".class";
        InputStream in = FixturePluginJar.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("test class not found on classpath: " + resource);
        }
        try {
            java.io.ByteArrayOutputStream buffer =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            writeEntry(out, fqcn.replace('.', '/') + ".class", buffer.toByteArray());
        } finally {
            in.close();
        }
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] bytes)
            throws IOException {
        out.putNextEntry(new java.util.jar.JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
