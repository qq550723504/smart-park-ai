import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import AlertSelector from './components/AlertSelector.vue';
import WorkflowGraph from './components/WorkflowGraph.vue';
import EventTimeline from './components/EventTimeline.vue';
import { demoAlerts } from './types/workflow';
import { useWorkflow } from './composables/useWorkflow';
import './styles.css';
const selectedAlertId = ref(demoAlerts[0].id);
const reviewer = ref('');
const comment = ref('');
const { workflow, events, loading, approving, error, isTerminal, start, approve } = useWorkflow();
const selectedAlert = computed(() => demoAlerts.find((item) => item.id === selectedAlertId.value) ?? demoAlerts[0]);
const needsApproval = computed(() => workflow.value?.status === 'WAITING_APPROVAL');
const hasStarted = computed(() => Boolean(workflow.value));
function selectAlert(id) {
    selectedAlertId.value = id;
}
async function launch() {
    await start(selectedAlertId.value);
}
async function decide(decision) {
    if (!reviewer.value.trim() || !comment.value.trim()) {
        ElMessage.warning('请填写审批人和审批意见');
        return;
    }
    await approve({ decision, reviewer: reviewer.value, comment: comment.value });
    reviewer.value = '';
    comment.value = '';
}
function statusLabel(status) {
    return { RUNNING: '执行中', WAITING_APPROVAL: '待人工审批', COMPLETED: '已完成', REJECTED: '已拒绝', FAILED: '执行失败' }[status ?? ''] ?? '未启动';
}
function statusType(status) {
    return { RUNNING: 'primary', WAITING_APPROVAL: 'warning', COMPLETED: 'success', REJECTED: 'info', FAILED: 'danger' }[status ?? ''] ?? 'info';
}
function confidence(value) {
    return value == null ? '--' : `${Math.round(value * 100)}%`;
}
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "app-shell" },
});
/** @type {__VLS_StyleScopedClasses['app-shell']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.header, __VLS_intrinsics.header)({
    ...{ class: "topbar" },
});
/** @type {__VLS_StyleScopedClasses['topbar']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "brand-lockup" },
});
/** @type {__VLS_StyleScopedClasses['brand-lockup']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "brand-mark" },
});
/** @type {__VLS_StyleScopedClasses['brand-mark']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "brand-kicker" },
});
/** @type {__VLS_StyleScopedClasses['brand-kicker']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "system-status" },
});
/** @type {__VLS_StyleScopedClasses['system-status']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "status-pulse" },
});
/** @type {__VLS_StyleScopedClasses['status-pulse']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "divider" },
});
/** @type {__VLS_StyleScopedClasses['divider']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "muted" },
});
/** @type {__VLS_StyleScopedClasses['muted']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
    ...{ class: "main-content" },
});
/** @type {__VLS_StyleScopedClasses['main-content']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "hero-row" },
});
/** @type {__VLS_StyleScopedClasses['hero-row']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "eyebrow" },
});
/** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.br)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.em, __VLS_intrinsics.em)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
    ...{ class: "hero-copy" },
});
/** @type {__VLS_StyleScopedClasses['hero-copy']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "hero-metrics" },
});
/** @type {__VLS_StyleScopedClasses['hero-metrics']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
if (__VLS_ctx.error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "error-banner" },
    });
    /** @type {__VLS_StyleScopedClasses['error-banner']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_ctx.error);
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.error))
                    throw 0;
                return (__VLS_ctx.error = '');
                // @ts-ignore
                [error, error, error,];
            } },
        type: "button",
    });
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "dashboard-grid" },
});
/** @type {__VLS_StyleScopedClasses['dashboard-grid']} */ ;
const __VLS_0 = AlertSelector;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    ...{ 'onSelect': {} },
    ...{ 'onStart': {} },
    alerts: (__VLS_ctx.demoAlerts),
    selectedId: (__VLS_ctx.selectedAlertId),
    loading: (__VLS_ctx.loading),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onSelect': {} },
    ...{ 'onStart': {} },
    alerts: (__VLS_ctx.demoAlerts),
    selectedId: (__VLS_ctx.selectedAlertId),
    loading: (__VLS_ctx.loading),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_5;
const __VLS_6 = {
    /** @type {typeof __VLS_5.select} */
    onSelect: (__VLS_ctx.selectAlert),
};
const __VLS_7 = {
    /** @type {typeof __VLS_5.start} */
    onStart: (__VLS_ctx.launch),
};
var __VLS_3;
var __VLS_4;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel summary-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['summary-panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "section-heading" },
});
/** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "eyebrow" },
});
/** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
let __VLS_8;
/** @ts-ignore @type { | typeof __VLS_components.elTag | typeof __VLS_components.ElTag | typeof __VLS_components['el-tag'] | typeof __VLS_components.elTag | typeof __VLS_components.ElTag | typeof __VLS_components['el-tag']} */
elTag;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent1(__VLS_8, new __VLS_8({
    type: (__VLS_ctx.statusType(__VLS_ctx.workflow?.status)),
    effect: "dark",
    round: true,
}));
const __VLS_10 = __VLS_9({
    type: (__VLS_ctx.statusType(__VLS_ctx.workflow?.status)),
    effect: "dark",
    round: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
const { default: __VLS_13 } = __VLS_11.slots;
(__VLS_ctx.statusLabel(__VLS_ctx.workflow?.status));
// @ts-ignore
[demoAlerts, selectedAlertId, loading, selectAlert, launch, statusType, workflow, workflow, statusLabel,];
var __VLS_11;
if (__VLS_ctx.hasStarted) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "summary-content" },
    });
    /** @type {__VLS_StyleScopedClasses['summary-content']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "workflow-id" },
    });
    /** @type {__VLS_StyleScopedClasses['workflow-id']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.workflow?.workflowId);
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "summary-stats" },
    });
    /** @type {__VLS_StyleScopedClasses['summary-stats']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.workflow?.alertId);
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.workflow?.eventSequence);
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({
        ...{ class: (__VLS_ctx.workflow?.diagnosis?.riskLevel === 'HIGH' ? 'danger-text' : '') },
    });
    (__VLS_ctx.workflow?.diagnosis?.riskLevel ?? '分析中');
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.confidence(__VLS_ctx.workflow?.diagnosis?.confidence));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "selected-alert" },
    });
    /** @type {__VLS_StyleScopedClasses['selected-alert']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mini-icon" },
    });
    /** @type {__VLS_StyleScopedClasses['mini-icon']} */ ;
    (__VLS_ctx.selectedAlert.category.slice(0, 1));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.selectedAlert.title);
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_ctx.selectedAlert.device);
    (__VLS_ctx.selectedAlert.building);
}
else {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "empty-summary" },
    });
    /** @type {__VLS_StyleScopedClasses['empty-summary']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "empty-orbit" },
    });
    /** @type {__VLS_StyleScopedClasses['empty-orbit']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "lower-grid" },
});
/** @type {__VLS_StyleScopedClasses['lower-grid']} */ ;
const __VLS_14 = WorkflowGraph;
// @ts-ignore
const __VLS_15 = __VLS_asFunctionalComponent1(__VLS_14, new __VLS_14({
    workflow: (__VLS_ctx.workflow),
    events: (__VLS_ctx.events),
}));
const __VLS_16 = __VLS_15({
    workflow: (__VLS_ctx.workflow),
    events: (__VLS_ctx.events),
}, ...__VLS_functionalComponentArgsRest(__VLS_15));
const __VLS_19 = EventTimeline;
// @ts-ignore
const __VLS_20 = __VLS_asFunctionalComponent1(__VLS_19, new __VLS_19({
    events: (__VLS_ctx.events),
}));
const __VLS_21 = __VLS_20({
    events: (__VLS_ctx.events),
}, ...__VLS_functionalComponentArgsRest(__VLS_20));
if (__VLS_ctx.needsApproval) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "approval-panel panel" },
    });
    /** @type {__VLS_StyleScopedClasses['approval-panel']} */ ;
    /** @type {__VLS_StyleScopedClasses['panel']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "approval-accent" },
    });
    /** @type {__VLS_StyleScopedClasses['approval-accent']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "approval-copy" },
    });
    /** @type {__VLS_StyleScopedClasses['approval-copy']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "approval-facts" },
    });
    /** @type {__VLS_StyleScopedClasses['approval-facts']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.workflow?.diagnosis?.riskLevel);
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.confidence(__VLS_ctx.workflow?.diagnosis?.confidence));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_ctx.workflow?.diagnosis ? '已完成检索' : '分析中');
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "approval-form" },
    });
    /** @type {__VLS_StyleScopedClasses['approval-form']} */ ;
    let __VLS_24;
    /** @ts-ignore @type { | typeof __VLS_components.elInput | typeof __VLS_components.ElInput | typeof __VLS_components['el-input']} */
    elInput;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent1(__VLS_24, new __VLS_24({
        modelValue: (__VLS_ctx.reviewer),
        placeholder: "审批人姓名",
    }));
    const __VLS_26 = __VLS_25({
        modelValue: (__VLS_ctx.reviewer),
        placeholder: "审批人姓名",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    let __VLS_29;
    /** @ts-ignore @type { | typeof __VLS_components.elInput | typeof __VLS_components.ElInput | typeof __VLS_components['el-input']} */
    elInput;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent1(__VLS_29, new __VLS_29({
        modelValue: (__VLS_ctx.comment),
        type: "textarea",
        rows: (2),
        placeholder: "审批意见",
    }));
    const __VLS_31 = __VLS_30({
        modelValue: (__VLS_ctx.comment),
        type: "textarea",
        rows: (2),
        placeholder: "审批意见",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "approval-actions" },
    });
    /** @type {__VLS_StyleScopedClasses['approval-actions']} */ ;
    let __VLS_34;
    /** @ts-ignore @type { | typeof __VLS_components.elButton | typeof __VLS_components.ElButton | typeof __VLS_components['el-button'] | typeof __VLS_components.elButton | typeof __VLS_components.ElButton | typeof __VLS_components['el-button']} */
    elButton;
    // @ts-ignore
    const __VLS_35 = __VLS_asFunctionalComponent1(__VLS_34, new __VLS_34({
        ...{ 'onClick': {} },
        loading: (__VLS_ctx.approving),
    }));
    const __VLS_36 = __VLS_35({
        ...{ 'onClick': {} },
        loading: (__VLS_ctx.approving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_35));
    let __VLS_39;
    const __VLS_40 = {
        /** @type {typeof __VLS_39.click} */
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.needsApproval))
                throw 0;
            return (__VLS_ctx.decide('REJECT'));
            // @ts-ignore
            [workflow, workflow, workflow, workflow, workflow, workflow, workflow, workflow, workflow, workflow, hasStarted, confidence, confidence, selectedAlert, selectedAlert, selectedAlert, selectedAlert, events, events, needsApproval, reviewer, comment, approving, decide,];
        },
    };
    const { default: __VLS_41 } = __VLS_37.slots;
    // @ts-ignore
    [];
    var __VLS_37;
    var __VLS_38;
    let __VLS_42;
    /** @ts-ignore @type { | typeof __VLS_components.elButton | typeof __VLS_components.ElButton | typeof __VLS_components['el-button'] | typeof __VLS_components.elButton | typeof __VLS_components.ElButton | typeof __VLS_components['el-button']} */
    elButton;
    // @ts-ignore
    const __VLS_43 = __VLS_asFunctionalComponent1(__VLS_42, new __VLS_42({
        ...{ 'onClick': {} },
        type: "primary",
        loading: (__VLS_ctx.approving),
    }));
    const __VLS_44 = __VLS_43({
        ...{ 'onClick': {} },
        type: "primary",
        loading: (__VLS_ctx.approving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_43));
    let __VLS_47;
    const __VLS_48 = {
        /** @type {typeof __VLS_47.click} */
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.needsApproval))
                throw 0;
            return (__VLS_ctx.decide('APPROVE'));
            // @ts-ignore
            [approving, decide,];
        },
    };
    const { default: __VLS_49 } = __VLS_45.slots;
    // @ts-ignore
    [];
    var __VLS_45;
    var __VLS_46;
}
if (__VLS_ctx.isTerminal && __VLS_ctx.workflow) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "result-strip panel" },
    });
    /** @type {__VLS_StyleScopedClasses['result-strip']} */ ;
    /** @type {__VLS_StyleScopedClasses['panel']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "result-check" },
    });
    /** @type {__VLS_StyleScopedClasses['result-check']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    (__VLS_ctx.workflow.status === 'COMPLETED' ? '处置流程已完成' : __VLS_ctx.workflow.status === 'REJECTED' ? '处置流程已拒绝' : '处置流程未完成');
    if (__VLS_ctx.workflow.workOrder) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "result-order" },
        });
        /** @type {__VLS_StyleScopedClasses['result-order']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
        (__VLS_ctx.workflow.workOrder.id);
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "result-note" },
    });
    /** @type {__VLS_StyleScopedClasses['result-note']} */ ;
}
__VLS_asFunctionalElement1(__VLS_intrinsics.footer, __VLS_intrinsics.footer)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
// @ts-ignore
[workflow, workflow, workflow, workflow, workflow, isTerminal,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
