package com.equip.demo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实方法执行计数器（M1-6）：验证 VOID Mock 吞掉真实调用（打点为 0）。
 */
public final class RealCallCounter {

    private static final AtomicInteger READ_STATUS = new AtomicInteger();
    private static final AtomicInteger POWER_ON = new AtomicInteger();
    private static final AtomicInteger SEND = new AtomicInteger();
    // M4-3 增量：RadarServo 打点（不影响既有三个计数的行为）
    private static final AtomicInteger GET_AZIMUTH = new AtomicInteger();
    private static final AtomicInteger TRACK = new AtomicInteger();

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

    /** 真实 getAzimuth 执行次数（M4-3，RadarServo） */
    public static int getAzimuthCount() {
        return GET_AZIMUTH.get();
    }

    /** 真实 track 执行次数（M4-3，RadarServo） */
    public static int trackCount() {
        return TRACK.get();
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

    static void onGetAzimuth() {
        GET_AZIMUTH.incrementAndGet();
    }

    static void onTrack() {
        TRACK.incrementAndGet();
    }
}
