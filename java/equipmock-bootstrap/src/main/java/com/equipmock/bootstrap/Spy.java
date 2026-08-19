package com.equipmock.bootstrap;

/**
 * 插桩代码与 agent 之间的桥接类（02 §1）。
 *
 * <p>本类随 equip-mock-bootstrap.jar 挂到 bootstrap classloader（Boot-Class-Path /
 * appendToBootstrapClassLoaderSearch），因此宿主应用任意类加载器中的插桩代码与 agent
 * 侧看到的是同一个类实例，可实现跨类加载器的双向静态调用（D5）。
 *
 * <p>二进制契约一旦发布不得变更：插桩字节码、agent、插件三方共享本类。
 */
public final class Spy {

    /** premain 时由 agent 反射注入；volatile 保证对宿主线程立即可见 */
    public static volatile ISpyHandler HANDLER;

    /** 无参调用的共享空参数数组（减少插桩路径分配；只读使用，调用方不得修改） */
    public static Object[] NO_ARGS = new Object[0];

    private Spy() {
    }

    /**
     * 插桩代码的唯一入口。
     *
     * @return {@link MockResult}；null = agent 未就绪或放行真实方法
     */
    public static Object/* MockResult */ mock(String className, String methodName,
                                              String descriptor, Object self, Object[] args) {
        ISpyHandler h = HANDLER;
        if (h == null) {
            return null; // agent 未就绪：安全放行（02 §5.3）
        }
        return h.mock(className, methodName, descriptor, self, args);
    }
}
