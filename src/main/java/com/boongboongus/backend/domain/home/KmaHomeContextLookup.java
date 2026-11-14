package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
class KmaHomeContextLookup implements HomeContextLookup {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BASE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BASE_TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter FORECAST_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT);

    // Lambert conformal conic projection for the KMA 5 km forecast grid.
    private static final double EARTH_RADIUS_IN_GRID_UNITS = 6371.00877 / 5;
    private static final double FIRST_PARALLEL = Math.toRadians(30);
    private static final double SECOND_PARALLEL = Math.toRadians(60);
    private static final double CONE = Math.log(Math.cos(FIRST_PARALLEL) / Math.cos(SECOND_PARALLEL))
            / Math.log(projectionTangent(SECOND_PARALLEL) / projectionTangent(FIRST_PARALLEL));
    private static final double SCALE = Math.cos(FIRST_PARALLEL)
            * Math.pow(projectionTangent(FIRST_PARALLEL), CONE) / CONE;
    private static final double ORIGIN_RADIUS = projectedRadius(Math.toRadians(38));

    private final RestClient http;
    private final URI baseUrl;
    private final String serviceKey;

    KmaHomeContextLookup(@Value("${app.weather.kma.base-url}") URI baseUrl,
            @Value("${app.weather.kma.service-key:}") String serviceKey) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        requests.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder().requestFactory(requests).build();
        this.baseUrl = baseUrl;
        this.serviceKey = UriUtils.decode(serviceKey.strip(), StandardCharsets.UTF_8);
    }

    @Override
    public Context find(Coordinate location) {
        if (serviceKey.isEmpty()) {
            return Context.unavailable();
        }
        Grid grid = Grid.from(location);
        if (grid.x() < 1 || grid.x() > 149 || grid.y() < 1 || grid.y() > 253) {
            return Context.unavailable();
        }

        ZonedDateTime now = ZonedDateTime.now(KST);
        ZonedDateTime observationTime = now.minusMinutes(10).truncatedTo(ChronoUnit.HOURS);
        String weather = null;
        boolean noPrecipitation = false;
        Double temperature = null;
        for (Observation observation : fetch("/getUltraSrtNcst", grid, observationTime, 20)) {
            if (observation == null || observation.category() == null || observation.obsrValue() == null) {
                continue;
            }
            switch (observation.category()) {
                case "PTY" -> {
                    noPrecipitation = "0".equals(observation.obsrValue());
                    weather = switch (observation.obsrValue()) {
                        case "1" -> "비";
                        case "2" -> "비/눈";
                        case "3" -> "눈";
                        case "5" -> "빗방울";
                        case "6" -> "빗방울/눈날림";
                        case "7" -> "눈날림";
                        default -> null;
                    };
                }
                case "T1H" -> temperature = temperature(observation.obsrValue());
                default -> { }
            }
        }
        if (weather == null && (noPrecipitation || temperature != null)) {
            weather = forecastSky(grid, now);
        }
        if (weather == null && temperature == null) {
            return Context.unavailable();
        }
        return new Context(weather, temperature, null, null, 5000, true, false, observationTime.toInstant());
    }

    private String forecastSky(Grid grid, ZonedDateTime now) {
        // The point forecast is issued at :30. Allow 15 minutes for the public response to be published.
        ZonedDateTime issuedAt = now.minusMinutes(45).truncatedTo(ChronoUnit.HOURS).plusMinutes(30);
        List<Observation> forecasts;
        try {
            forecasts = fetch("/getUltraSrtFcst", grid, issuedAt, 100);
        } catch (RestClientException exception) {
            return null;
        }
        String weather = null;
        Duration closest = Duration.ofHours(1).plusNanos(1);
        for (Observation forecast : forecasts) {
            if (forecast == null || !"SKY".equals(forecast.category()) || forecast.fcstValue() == null
                    || forecast.fcstDate() == null || forecast.fcstTime() == null) {
                continue;
            }
            String label = switch (forecast.fcstValue()) {
                case "1" -> "맑음";
                case "3" -> "구름 많음";
                case "4" -> "흐림";
                default -> null;
            };
            if (label == null) {
                continue;
            }
            try {
                var at = LocalDateTime.parse(forecast.fcstDate() + forecast.fcstTime(), FORECAST_TIME);
                Duration offset = Duration.between(now.toLocalDateTime(), at).abs();
                if (offset.compareTo(closest) < 0) {
                    weather = label;
                    closest = offset;
                }
            } catch (DateTimeParseException exception) {
                // Skip entries without a valid forecast time.
            }
        }
        return weather;
    }

    private List<Observation> fetch(String path, Grid grid, ZonedDateTime base, int rows) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl).path(path)
                .queryParam("serviceKey", "{key}").queryParam("dataType", "JSON")
                .queryParam("pageNo", 1).queryParam("numOfRows", rows)
                .queryParam("base_date", BASE_DATE.format(base))
                .queryParam("base_time", BASE_TIME.format(base))
                .queryParam("nx", grid.x()).queryParam("ny", grid.y())
                .encode().buildAndExpand(serviceKey).toUri();
        Envelope envelope = http.get().uri(uri).retrieve().body(Envelope.class);
        Response response = envelope == null ? null : envelope.response();
        if (response == null || response.header() == null || !"00".equals(response.header().resultCode())
                || response.body() == null || response.body().items() == null
                || response.body().items().item() == null) {
            return List.of();
        }
        return response.body().items().item();
    }

    private static Double temperature(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed > -99 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double projectionTangent(double latitude) {
        return Math.tan(Math.PI / 4 + latitude / 2);
    }

    private static double projectedRadius(double latitude) {
        return EARTH_RADIUS_IN_GRID_UNITS * SCALE / Math.pow(projectionTangent(latitude), CONE);
    }

    private record Grid(long x, long y) {
        static Grid from(Coordinate location) {
            double radius = projectedRadius(Math.toRadians(location.latitude()));
            double angle = Math.IEEEremainder(Math.toRadians(location.longitude() - 126), 2 * Math.PI) * CONE;
            return new Grid(Math.round(radius * Math.sin(angle) + 43),
                    Math.round(ORIGIN_RADIUS - radius * Math.cos(angle) + 136));
        }
    }

    private record Envelope(Response response) { }
    private record Response(Header header, Body body) { }
    private record Header(String resultCode) { }
    private record Body(Items items) { }
    private record Items(List<Observation> item) { }
    private record Observation(String category, String obsrValue, String fcstDate, String fcstTime, String fcstValue) { }
}
