package com.example.smartpark.web;

import com.example.smartpark.showcase.ShowcaseScenario;
import com.example.smartpark.showcase.ShowcaseScenarioCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/showcase")
public final class ShowcaseScenarioController {

    private final ShowcaseScenarioCatalog catalog;
    private final Clock clock;

    public ShowcaseScenarioController(
            ShowcaseScenarioCatalog catalog,
            @Qualifier("showcaseClock") Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    @GetMapping("/scenarios")
    public ShowcaseScenarioCatalogResponse scenarios() {
        Instant capturedAt = clock.instant();
        return new ShowcaseScenarioCatalogResponse(capturedAt, catalog.scenarios(capturedAt));
    }
}

record ShowcaseScenarioCatalogResponse(Instant capturedAt, List<ShowcaseScenario> scenarios) { }
