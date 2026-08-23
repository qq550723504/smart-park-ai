const __VLS_props = defineProps();
const nodeNames = {
    workflow: '工作流', classifyAlert: '告警分诊', collectParkContext: '收集上下文',
    retrieveKnowledge: '检索知识', diagnoseAlert: 'AI 诊断', riskGate: '风险判断',
    humanApproval: '人工审批', createWorkOrder: '创建工单', summarizeResult: '汇总结果',
};
const typeNames = {
    STARTED: '已启动', NODE_STARTED: '节点开始', TOOL_CALLED: '调用工具', NODE_COMPLETED: '节点完成',
    PAUSED: '等待审批', RESUMED: '恢复执行', COMPLETED: '已完成', FAILED: '执行失败',
};
function time(value) {
    return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value));
}
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
    ...{ class: "panel timeline-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['timeline-panel']} */ ;
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
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "live-indicator" },
});
/** @type {__VLS_StyleScopedClasses['live-indicator']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({});
if (__VLS_ctx.events.length) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "timeline" },
    });
    /** @type {__VLS_StyleScopedClasses['timeline']} */ ;
    for (const [event] of __VLS_vFor(([...__VLS_ctx.events].reverse()))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
            key: (event.eventId),
            ...{ class: "event-item" },
            ...{ class: (event.type.toLowerCase()) },
        });
        /** @type {__VLS_StyleScopedClasses['event-item']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "event-icon" },
        });
        /** @type {__VLS_StyleScopedClasses['event-icon']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "event-body" },
        });
        /** @type {__VLS_StyleScopedClasses['event-body']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
        (__VLS_ctx.typeNames[event.type] ?? event.type);
        __VLS_asFunctionalElement1(__VLS_intrinsics.time, __VLS_intrinsics.time)({});
        (__VLS_ctx.time(event.timestamp));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_ctx.nodeNames[event.node] ?? event.node);
        __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
        (event.sequence);
        (event.redactedSummary);
        // @ts-ignore
        [events, events, typeNames, time, nodeNames,];
    }
}
else {
    let __VLS_0;
    /** @ts-ignore @type { | typeof __VLS_components.elEmpty | typeof __VLS_components.ElEmpty | typeof __VLS_components['el-empty']} */
    elEmpty;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
        description: "启动工作流后，事件将在这里实时出现",
        imageSize: (80),
    }));
    const __VLS_2 = __VLS_1({
        description: "启动工作流后，事件将在这里实时出现",
        imageSize: (80),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({
    __typeProps: {},
});
export default {};
