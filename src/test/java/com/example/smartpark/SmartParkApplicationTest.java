package com.example.smartpark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
                "spring.ai.dashscope.api-key=test-key",
                "spring.ai.dashscope.chat.options.model=qwen-plus"
        })
class SmartParkApplicationTest {

    @Test
    void applicationContextLoads() {
    }
}
