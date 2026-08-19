package com.equip.demo;

/**
 * 非目标类（M1-6）：未在路由表登记，验证 agent 不误伤无关类（不织入、原样执行）。
 */
public class UnrelatedService {

    public String hello() {
        return "real-hello";
    }
}
