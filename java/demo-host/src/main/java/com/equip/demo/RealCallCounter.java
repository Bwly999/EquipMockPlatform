package com.equip.demo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实方法执行计数器（M1-6）：验证 VOID Mock 吞掉真实调用（打点为 0）。
 */
public final class RealCallCounter {

    private static final AtomicInteger READ_STATUS = new AtomicInteger();
    private static final AtomicInteger POWER_ON = new AtomicInteger();
    private static final AtomicInteger SEND = new AtomicInteger();

    private RealCallCounter() {
    }

    /** 真实 readStatus 执行次数 */
    public static int readStatusCount() {
        return READ_STATUS.get();
    }

    /** 真实 powerOn 执行次数 */
    public static int powerOnCount() {
        return POWER_ON.get();
    }

    /** 真实 send 执行次数 */
    public static int sendCount() {
        return SEND.get();
    }

    static void onReadStatus() {
        READ_STATUS.incrementAndGet();
    }

    static void onPowerOn() {
        POWER_ON.incrementAndGet();
    }

    static void onSend() {
        SEND.incrementAndGet();
    }
}
