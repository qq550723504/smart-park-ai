package com.example.smartpark.securityincident;

import java.util.List;

public record SecurityIncidentPage(List<SecurityIncident> items, int total) {
    public SecurityIncidentPage {
        items = List.copyOf(items);
        if (total < items.size()) throw new IllegalArgumentException("total must include returned items");
    }
}
