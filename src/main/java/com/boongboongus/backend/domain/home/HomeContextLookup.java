package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Provides the available weather, traffic, and theme for the location supplied by the app. */
public interface HomeContextLookup {
    Context find(Coordinate location);

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Context(String weather, Double temperatureC, String traffic, String recommendedTheme, int radiusMeters,
            boolean weatherAvailable, boolean trafficAvailable, Instant observedAt) {
        static Context unavailable() {
            return new Context(null, null, null, null, 5000, false, false, null);
        }
    }
}
