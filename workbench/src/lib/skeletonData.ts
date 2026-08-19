/**
 * 骨架初始化示例数据（06 §2：创建 home = 04 §1 结构 + 示例 default 组）。
 * default/cabinet.json 与 docs/04-file-contract.md §3 的完整示例一致。
 */
import type { PluginRegistryDoc, Settings, SubGroupDoc } from './types'

export const SKELETON_SETTINGS: Settings = {
  $schema: 'equipmock/settings@1',
  activeGroup: 'default',
  mockEnabled: true,
}

export const SKELETON_REGISTRY: PluginRegistryDoc = {
  $schema: 'equipmock/plugin-registry@1',
  plugins: [],
}

/** 04 §3 完整示例 */
export const SKELETON_DEFAULT_CABINET: SubGroupDoc = {
  $schema: 'equipmock/subgroup@1',
  name: 'cabinet',
  description: '机柜电源相关 Mock',
  mocks: [
    {
      class: 'com.equip.demo.PowerDevice',
      method: 'readStatus',
      signature: '(ILjava/lang/String;)I',
      description: '读取通道状态',
      enabled: true,
      defaultAction: { type: 'VALUE', value: 0 },
      rules: [
        {
          matchType: 'FULL_MATCH',
          description: '通道1返回满量程',
          args: [1, 'CH1'],
          action: { type: 'VALUE', value: 5 },
        },
        {
          matchType: 'PATTERN_MATCH',
          description: '异常通道模拟超时',
          argsPattern: ['\\d+', 'CH(9[0-9])'],
          action: {
            type: 'THROW',
            exception: 'java.io.IOException',
            message: 'device timeout',
          },
        },
      ],
    },
    {
      class: 'com.equip.demo.PowerDevice',
      method: 'powerOn',
      enabled: true,
      rules: [],
      defaultAction: { type: 'VOID' },
    },
  ],
}

export const SKELETON_DEFAULT_RADAR: SubGroupDoc = {
  $schema: 'equipmock/subgroup@1',
  name: 'radar',
  description: '雷达相关 Mock（简单示例）',
  mocks: [
    {
      class: 'com.equip.demo.RadarDevice',
      method: 'getScanSpeed',
      signature: '()D',
      description: '扫描速度',
      enabled: true,
      defaultAction: { type: 'VALUE', value: 12.5 },
      rules: [],
    },
    {
      class: 'com.equip.demo.RadarDevice',
      method: 'selfCheck',
      enabled: true,
      rules: [],
      defaultAction: { type: 'VOID' },
    },
  ],
}

export const SKELETON_FAULT_CABINET: SubGroupDoc = {
  $schema: 'equipmock/subgroup@1',
  name: 'cabinet',
  description: '机柜电源相关 Mock（故障注入预设）',
  mocks: [
    {
      class: 'com.equip.demo.PowerDevice',
      method: 'readStatus',
      signature: '(ILjava/lang/String;)I',
      description: '读取通道状态——全部返回故障码',
      enabled: true,
      defaultAction: { type: 'VALUE', value: -1 },
      rules: [
        {
          matchType: 'FULL_MATCH',
          description: '通道1 保持正常，便于对照',
          args: [1, 'CH1'],
          action: { type: 'VALUE', value: 5 },
        },
      ],
    },
    {
      class: 'com.equip.demo.PowerDevice',
      method: 'powerOn',
      enabled: true,
      rules: [],
      defaultAction: { type: 'VOID' },
    },
  ],
}

export const SKELETON_FAULT_RADAR: SubGroupDoc = {
  $schema: 'equipmock/subgroup@1',
  name: 'radar',
  description: '雷达相关 Mock（故障注入预设）',
  mocks: [
    {
      class: 'com.equip.demo.RadarDevice',
      method: 'getScanSpeed',
      signature: '()D',
      enabled: true,
      defaultAction: { type: 'VALUE', value: 0 },
      rules: [],
    },
    {
      class: 'com.equip.demo.RadarDevice',
      method: 'selfCheck',
      enabled: true,
      rules: [
        {
          matchType: 'FULL_MATCH',
          description: '自检直接抛通信故障',
          args: [],
          action: {
            type: 'THROW',
            exception: 'java.io.IOException',
            message: 'fault-sim: radar offline',
          },
        },
      ],
      defaultAction: { type: 'VOID' },
    },
  ],
}

/** 骨架组内容表：组目录名 → 小分组文件名 → 文档 */
export const SKELETON_GROUPS: Record<string, Record<string, SubGroupDoc>> = {
  default: {
    'cabinet.json': SKELETON_DEFAULT_CABINET,
    'radar.json': SKELETON_DEFAULT_RADAR,
  },
  'fault-sim': {
    'cabinet.json': SKELETON_FAULT_CABINET,
    'radar.json': SKELETON_FAULT_RADAR,
  },
}
