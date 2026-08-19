package com.equipmock.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-5 断言测试：扫描构建产物 target/equip-mock-agent.jar——
 * <ul>
 *   <li>不得出现未 relocate 的 {@code net/bytebuddy/**} 与 {@code com/google/gson/**} 条目
 *       （02 §7；org/pf4j/** 按规则允许保留）；</li>
 *   <li>MANIFEST 关键项：Premain-Class / Boot-Class-Path / Can-Retransform-Classes /
 *       Implementation-Version。</li>
 * </ul>
 * jar 在 test 阶段通常尚未生成（shade 绑定 package 阶段），不存在时 skip；
 * scripts/m1-verify.sh 会在完整构建后对最终 jar 再扫一遍。
 */
class AgentJarContentTest {

    private static final Path AGENT_JAR = Paths.get("target", "equip-mock-agent.jar");

    private static Path assumeJarExists() {
        Assumptions.assumeTrue(Files.exists(AGENT_JAR),
                "target/equip-mock-agent.jar 不存在（当前构建阶段尚未打包），跳过");
        return AGENT_JAR;
    }

    @Test
    void noUnrelocatedThirdPartyEntries() throws IOException {
        Path jar = assumeJarExists();
        List<String> violations = new ArrayList<String>();
        boolean hasShadedByteBuddy = false;
        boolean hasPf4j = false;
        boolean hasPluginApi = false;
        JarFile zip = new JarFile(jar.toFile());
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("net/bytebuddy/") || name.startsWith("com/google/gson/")) {
                    violations.add(name);
                }
                if (name.startsWith("io/equipmock/shaded/bytebuddy/")) {
                    hasShadedByteBuddy = true;
                }
                if (name.startsWith("org/pf4j/")) {
                    hasPf4j = true;
                }
                if (name.startsWith("com/equipmock/api/")) {
                    hasPluginApi = true;
                }
            }
        } finally {
            zip.close();
        }
        assertTrue(violations.isEmpty(),
                "agent jar 含未 relocate 的三方包条目（前 10 个）: "
                        + violations.subList(0, Math.min(10, violations.size())));
        // 正向断言：relocate 后的 byte-buddy、未 relocate 的 pf4j、平台 plugin-api 均在
        assertTrue(hasShadedByteBuddy, "agent jar 缺少 io/equipmock/shaded/bytebuddy/**（relocate 产物）");
        assertTrue(hasPf4j, "agent jar 缺少 org/pf4j/**（按 02 §7 只合并不 relocate）");
        assertTrue(hasPluginApi, "agent jar 缺少 com/equipmock/api/**（插件契约）");
    }

    @Test
    void manifestHasAgentEntries() throws IOException {
        Path jar = assumeJarExists();
        JarFile zip = new JarFile(jar.toFile());
        try {
            Attributes attrs = zip.getManifest().getMainAttributes();
            assertEquals("com.equipmock.agent.AgentPremain",
                    attrs.getValue("Premain-Class"));
            assertEquals("equip-mock-bootstrap.jar", attrs.getValue("Boot-Class-Path"));
            assertEquals("true", attrs.getValue("Can-Retransform-Classes"));
            String version = attrs.getValue("Implementation-Version");
            assertTrue(version != null && version.length() > 0,
                    "Implementation-Version 缺失");
        } finally {
            zip.close();
        }
    }
}
