import { computed } from 'vue';
import { VueFlow, Position } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';
const props = defineProps();
const definitions = [
    ['classifyAlert', '告警分诊', 30, 130],
    ['collectParkContext', '收集园区上下文', 230, 130],
    ['retrieveKnowledge', '检索知识库', 450, 130],
    ['diagnoseAlert', 'AI 告警诊断', 650, 130],
    ['riskGate', '风险判断', 850, 130],
    ['humanApproval', '人工审批', 850, 280],
    ['createWorkOrder', '创建工单', 1070, 130],
    ['summarizeResult', '汇总结果', 1270, 130],
];
function nodeStatus(id) {
    const related = props.events.filter((event) => event.node === id);
    if (related.some((event) => event.type === 'FAILED'))
        return 'failed';
    if (related.some((event) => event.type === 'PAUSED')) {
        return props.workflow?.status === 'WAITING_APPROVAL' ? 'waiting' : 'completed';
    }
    if (related.some((event) => event.type === 'NODE_COMPLETED'))
        return 'completed';
    if (related.some((event) => event.type === 'NODE_STARTED'))
        return 'running';
    return 'pending';
}
const nodes = computed(() => definitions.map(([id, label, x, y]) => ({
    id,
    position: { x, y },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: { label, status: nodeStatus(id) },
    class: `workflow-node is-${nodeStatus(id)}`,
    draggable: false,
})));
const edges = [
    ['classifyAlert', 'collectParkContext'],
    ['collectParkContext', 'retrieveKnowledge'],
    ['retrieveKnowledge', 'diagnoseAlert'],
    ['diagnoseAlert', 'riskGate'],
    ['riskGate', 'createWorkOrder'],
    ['riskGate', 'humanApproval'],
    ['humanApproval', 'createWorkOrder'],
    ['humanApproval', 'summarizeResult'],
    ['createWorkOrder', 'summarizeResult'],
].map(([source, target], index) => ({ id: `e${index}`, source, target, animated: true }));
const __VLS_ctx = {
    ...{},
    ...{},
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel graph-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['graph-panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "section-heading compact" },
});
/** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
/** @type {__VLS_StyleScopedClasses['compact']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "eyebrow" },
});
/** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "graph-legend" },
});
/** @type {__VLS_StyleScopedClasses['graph-legend']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
    ...{ class: "dot pending" },
});
/** @type {__VLS_StyleScopedClasses['dot']} */ ;
/** @type {__VLS_StyleScopedClasses['pending']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
    ...{ class: "dot running" },
});
/** @type {__VLS_StyleScopedClasses['dot']} */ ;
/** @type {__VLS_StyleScopedClasses['running']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
    ...{ class: "dot completed" },
});
/** @type {__VLS_StyleScopedClasses['dot']} */ ;
/** @type {__VLS_StyleScopedClasses['completed']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
    ...{ class: "dot waiting" },
});
/** @type {__VLS_StyleScopedClasses['dot']} */ ;
/** @type {__VLS_StyleScopedClasses['waiting']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "flow-canvas" },
});
/** @type {__VLS_StyleScopedClasses['flow-canvas']} */ ;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.VueFlow | typeof __VLS_components.VueFlow} */
VueFlow;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    nodes: (__VLS_ctx.nodes),
    edges: (__VLS_ctx.edges),
    fitViewOnInit: (true),
    minZoom: (0.45),
    maxZoom: (1.3),
}));
const __VLS_2 = __VLS_1({
    nodes: (__VLS_ctx.nodes),
    edges: (__VLS_ctx.edges),
    fitViewOnInit: (true),
    minZoom: (0.45),
    maxZoom: (1.3),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
const { default: __VLS_5 } = __VLS_3.slots;
let __VLS_6;
/** @ts-ignore @type { | typeof __VLS_components.Background} */
Background;
// @ts-ignore
const __VLS_7 = __VLS_asFunctionalComponent1(__VLS_6, new __VLS_6({
    patternColor: "#d6e4e8",
    gap: (22),
}));
const __VLS_8 = __VLS_7({
    patternColor: "#d6e4e8",
    gap: (22),
}, ...__VLS_functionalComponentArgsRest(__VLS_7));
let __VLS_11;
/** @ts-ignore @type { | typeof __VLS_components.Controls} */
Controls;
// @ts-ignore
const __VLS_12 = __VLS_asFunctionalComponent1(__VLS_11, new __VLS_11({
    showInteractive: (false),
}));
const __VLS_13 = __VLS_12({
    showInteractive: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_12));
{
    const { 'node-default': __VLS_16 } = __VLS_3.slots;
    const [{ data }] = __VLS_vSlot(__VLS_16);
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "node-inner" },
    });
    /** @type {__VLS_StyleScopedClasses['node-inner']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "node-indicator" },
    });
    /** @type {__VLS_StyleScopedClasses['node-indicator']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (data.label);
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    ({ pending: '等待执行', running: '正在执行', completed: '执行完成', waiting: '等待操作员', failed: '执行失败' }[data.status]);
    // @ts-ignore
    [nodes, edges,];
}
// @ts-ignore
[];
var __VLS_3;
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({
    __typeProps: {},
});
export default {};
