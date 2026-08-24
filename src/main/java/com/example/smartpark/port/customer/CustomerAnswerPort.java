package com.example.smartpark.port.customer;

import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;

import java.util.List;

@FunctionalInterface
public interface CustomerAnswerPort {
    CustomerAnswer answer(String question, String intent, List<KnowledgeMatch> evidence);
}
