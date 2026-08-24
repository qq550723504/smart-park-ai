package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.port.customer.CustomerAnswerPort;

import java.util.List;

public final class MockCustomerAnswerAdapter implements CustomerAnswerPort {
    @Override
    public CustomerAnswer answer(String question, String intent, List<KnowledgeMatch> evidence) {
        return switch (intent) {
            case "PARKING" -> new CustomerAnswer("访客车辆可在园区入口完成登记后进入访客停车区，具体收费与开放时段请以园区停车指引为准。", false, CustomerAnswer.Reason.SUPPORTED, evidence.stream().map(KnowledgeMatch::documentId).toList());
            case "VISITOR" -> new CustomerAnswer("访客需由园区联系人提前预约，到访时按门岗指引核验预约信息；客服不会在对话中索取身份证原始资料。", false, CustomerAnswer.Reason.SUPPORTED, evidence.stream().map(KnowledgeMatch::documentId).toList());
            case "ENERGY" -> new CustomerAnswer("可以查询公共区域的能耗趋势和节能建议；涉及租户账单或设备控制时需要人工核验权限。", false, CustomerAnswer.Reason.SUPPORTED, evidence.stream().map(KnowledgeMatch::documentId).toList());
            default -> new CustomerAnswer("当前知识库没有足够信息，已转人工客服处理。", true, CustomerAnswer.Reason.INSUFFICIENT_EVIDENCE, List.of());
        };
    }
}
