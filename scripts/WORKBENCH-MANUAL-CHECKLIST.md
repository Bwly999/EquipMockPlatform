# 工作台手测清单（M7-3 归档）

自动化已覆盖：104 个 Vitest（校验器/store/组件/主进程纯函数/真实临时目录 fs）、`pnpm build` + no-CDN 断言、GUI 冒烟 3 轮。以下为无法脚本化、需人工过一遍的项目（对应 07 M5-1/M6 验收）。建议配合 agent 发布包（`target/release/equip-mock/`）联调。

## A. 基础（M5）
- [ ] `pnpm dev`（或安装包启动）打开主窗口，标题"EquipMock 工作台"，中文界面
- [ ] 首次空态：选择/初始化 equip-mock 主目录（指向发布包目录）后进入三页外壳
- [ ] 单实例锁：二次启动聚焦已有窗口而非新开
- [ ] 顶栏：home 路径显示与切换、全局 Mock 开关、agent 心跳灯（启动 demo-host 后变绿）

## B. 配置页（M6）
- [ ] 组树：default/fault-sim 可见；一键切换生效组（demo-host 运行中输出 ≤2s 变化）；●生效组标记正确
- [ ] 组管理：新建/复制/重命名（settings 引用同步）/删除（生效组禁删）
- [ ] 小分组：cabinet 打开为方法卡片；表单⇄JSON 双模式切换无损；Ctrl+S 保存（改值后 demo-host 输出 ≤2s 变化）
- [ ] 校验拦截：Monaco 模式故意写坏（如 matchType 缺 args、非法正则）→ 保存被拒且错误定位；不落盘
- [ ] FULL_MATCH 参数行/PATTERN_MATCH 正则行（非法红框）/action 三编辑器/规则拖动排序
- [ ] 未保存切走：丢弃/保存后继续/取消 三选弹窗

## C. 插件页（M6，配合 agent 运行）
- [ ] 发布包预登记两示例插件：状态徽章 STARTED
- [ ] 导入新插件 jar：缺 Plugin-Id 拦截；正常导入后徽章 STARTED（retransform 生效）
- [ ] 启用开关：停用后 demo-host 对应调用回真实/配置值，再启用恢复
- [ ] 移除（含/不含删 jar）

## D. 状态页（M6）
- [ ] 卡片：版本/pid/生效组/mockEnabled/instrumentedClasses/needsRestart
- [ ] lastError：手写坏 json 后出现，点击跳转对应小分组
- [ ] 日志尾部自动滚动（可暂停）；agent 关闭后进入未运行态文案

## E. 工作台↔agent 联动（组合验收说明）
自动化等价证据：e2e-check.sh 用例 1–10 已证明"文件契约变更 → agent ≤2.5s 热生效"；工作台写盘走同一原子写协议且经 storeFs 真实目录测试。上表 B/C 的联动项为 GUI 侧最后人工确认。
