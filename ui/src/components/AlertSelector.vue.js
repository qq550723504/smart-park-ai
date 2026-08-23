const __VLS_props = defineProps();
const emit = defineEmits();
const __VLS_ctx = {
    ...{},
    ...{},
    ...{},
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel alert-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['alert-panel']} */ ;
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
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "count-badge" },
});
/** @type {__VLS_StyleScopedClasses['count-badge']} */ ;
(__VLS_ctx.alerts.length);
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "alert-list" },
});
/** @type {__VLS_StyleScopedClasses['alert-list']} */ ;
for (const [alert] of __VLS_vFor((__VLS_ctx.alerts))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return (__VLS_ctx.emit('select', alert.id));
                // @ts-ignore
                [alerts, alerts, emit,];
            } },
        key: (alert.id),
        ...{ class: "alert-card" },
        ...{ class: ({ active: alert.id === __VLS_ctx.selectedId }) },
        type: "button",
    });
    /** @type {__VLS_StyleScopedClasses['alert-card']} */ ;
    /** @type {__VLS_StyleScopedClasses['active']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "risk-mark" },
        ...{ class: (alert.risk.toLowerCase()) },
    });
    /** @type {__VLS_StyleScopedClasses['risk-mark']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "alert-content" },
    });
    /** @type {__VLS_StyleScopedClasses['alert-content']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "alert-title-row" },
    });
    /** @type {__VLS_StyleScopedClasses['alert-title-row']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (alert.title);
    let __VLS_0;
    /** @ts-ignore @type { | typeof __VLS_components.elTag | typeof __VLS_components.ElTag | typeof __VLS_components['el-tag'] | typeof __VLS_components.elTag | typeof __VLS_components.ElTag | typeof __VLS_components['el-tag']} */
    elTag;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
        type: (alert.risk === 'HIGH' ? 'danger' : 'success'),
        size: "small",
        effect: "dark",
    }));
    const __VLS_2 = __VLS_1({
        type: (alert.risk === 'HIGH' ? 'danger' : 'success'),
        size: "small",
        effect: "dark",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    const { default: __VLS_5 } = __VLS_3.slots;
    (alert.risk === 'HIGH' ? '高风险' : '低风险');
    // @ts-ignore
    [selectedId,];
    var __VLS_3;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "alert-meta" },
    });
    /** @type {__VLS_StyleScopedClasses['alert-meta']} */ ;
    (alert.id);
    (alert.building);
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "alert-description" },
    });
    /** @type {__VLS_StyleScopedClasses['alert-description']} */ ;
    (alert.description);
    // @ts-ignore
    [];
}
let __VLS_6;
/** @ts-ignore @type { | typeof __VLS_components.elButton | typeof __VLS_components.ElButton | typeof __VLS_components['el-button'] | typeof __VLS_components.elButton | typeof __VLS_components.ElButton | typeof __VLS_components['el-button']} */
elButton;
// @ts-ignore
const __VLS_7 = __VLS_asFunctionalComponent1(__VLS_6, new __VLS_6({
    ...{ 'onClick': {} },
    ...{ class: "start-button" },
    type: "primary",
    size: "large",
    loading: (__VLS_ctx.loading),
}));
const __VLS_8 = __VLS_7({
    ...{ 'onClick': {} },
    ...{ class: "start-button" },
    type: "primary",
    size: "large",
    loading: (__VLS_ctx.loading),
}, ...__VLS_functionalComponentArgsRest(__VLS_7));
let __VLS_11;
const __VLS_12 = {
    /** @type {typeof __VLS_11.click} */
    onClick: (...[$event]) => {
        return (__VLS_ctx.emit('start'));
        // @ts-ignore
        [emit, loading,];
    },
};
/** @type {__VLS_StyleScopedClasses['start-button']} */ ;
const { default: __VLS_13 } = __VLS_9.slots;
// @ts-ignore
[];
var __VLS_9;
var __VLS_10;
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
    ...{ class: "helper" },
});
/** @type {__VLS_StyleScopedClasses['helper']} */ ;
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({
    __typeEmits: {},
    __typeProps: {},
});
export default {};
