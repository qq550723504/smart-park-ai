export const demoAlerts = [
    {
        id: 'ALT-TEMP-001',
        title: '暖通机房温度持续升高',
        device: '暖通空调送风机组',
        building: 'A1 · 暖通机房',
        risk: 'LOW',
        category: '温度异常',
        description: '送风温度已超过舒适区阈值。',
    },
    {
        id: 'ALT-POWER-001',
        title: '主配电柜电压波动',
        device: '主配电柜',
        building: 'A2 · 配电间',
        risk: 'HIGH',
        category: '电力异常',
        description: '主配电柜检测到三相电压不稳定。',
    },
    {
        id: 'ALT-ENERGY-001',
        title: 'A2 楼宇能耗异常',
        device: 'A2 楼宇电能表',
        building: 'A2 · 能源管理',
        risk: 'HIGH',
        category: '能耗异常',
        description: '当前时段能耗比学习基线高出 38%。',
    },
];
