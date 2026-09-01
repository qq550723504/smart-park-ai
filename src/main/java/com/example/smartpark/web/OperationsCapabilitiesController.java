package com.example.smartpark.web;

import com.example.smartpark.operations.OperationsCapabilitiesService;
import com.example.smartpark.operations.OperationsCapabilitiesSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsCapabilitiesController {

    private final OperationsCapabilitiesService capabilities;

    public OperationsCapabilitiesController(OperationsCapabilitiesService capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping("/capabilities")
    public OperationsCapabilitiesSnapshot capabilities() {
        return capabilities.snapshot();
    }
}
