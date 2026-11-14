package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreService.notFound;
import static com.boongboongus.backend.domain.home.HomeExploreService.requiredSource;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.drive.DriveRouteLookup;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class PlaceRouteService implements DriveRouteLookup {
    private static final Duration PREVIEW_WINDOW = Duration.ofMinutes(30);
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IdempotentWrite idempotency;
    private final KakaoDirectionsClient kakao;
    private final ObjectProvider<PlaceCatalog> catalogs;
    private final ObjectProvider<CurrentCoupleLookup> couples;

    PlaceRouteService(JdbcClient jdbc, JsonMapper json, IdempotentWrite idempotency, KakaoDirectionsClient kakao,
            ObjectProvider<PlaceCatalog> catalogs, ObjectProvider<CurrentCoupleLookup> couples) {
        this.jdbc = jdbc;
        this.json = json;
        this.idempotency = idempotency;
        this.kakao = kakao;
        this.catalogs = catalogs;
        this.couples = couples;
    }

    RouteView create(String userId, String key, PlaceRouteController.RouteRequest request) {
        Input input = new Input(point(request.origin()), point(request.destination()),
                request.waypoints() == null ? List.of() : request.waypoints().stream().map(this::point).toList(),
                request.preference() == null ? Preference.RECOMMENDED : request.preference());
        String coupleId = coupleId(userId);
        var firstResult = new AtomicReference<KakaoDirectionsClient.Directions>();
        String id = idempotency.run(userId, key, List.of("create-route", request), String.class, () -> {
            firstResult.set(kakao.find(input));
            String routeId = "rte_" + UUID.randomUUID();
            OffsetDateTime expiry = Instant.now().plus(PREVIEW_WINDOW).truncatedTo(ChronoUnit.MICROS)
                    .atOffset(ZoneOffset.UTC);
            jdbc.sql("""
                    INSERT INTO place_routes (id, creator_id, couple_id, route_input, expires_at)
                    VALUES (:id, :user, :couple, :input, :expires)
                    """).param("id", routeId).param("user", userId).param("couple", coupleId)
                    .param("input", json.writeValueAsString(input)).param("expires", expiry).update();
            return routeId;
        });
        return view(accessible(userId, coupleId, id), firstResult.get());
    }

    RouteView get(String userId, String routeId) {
        return view(accessible(userId, coupleId(userId), routeId), null);
    }

    @Override
    public Optional<Route> findAccessible(String userId, String coupleId, String routeId) {
        return lookup(routeId).filter(row -> row.expiresAt().isAfter(Instant.now()))
                .filter(row -> userId.equals(row.creatorId()) || coupleId.equals(row.coupleId()))
                .map(row -> new Route(row.input().origin(), row.input().destination()));
    }

    @Override
    public Optional<LiveRoute> findLiveForDrive(String userId, String coupleId, String routeId) {
        try {
            StoredRoute route = accessible(userId, coupleId, routeId);
            var directions = kakao.find(route.input());
            return Optional.of(new LiveRoute(routeId, directions.polyline(), directions.roads()));
        } catch (HomeExploreException exception) {
            throw new LiveRouteUnavailable();
        }
    }

    @Override
    public int estimatePlaylistDuration(Place origin, Place destination) {
        try {
            return kakao.find(new Input(origin, destination, List.of(), Preference.RECOMMENDED)).durationSeconds();
        } catch (HomeExploreException exception) {
            throw new LiveRouteUnavailable();
        }
    }

    private StoredRoute accessible(String userId, String coupleId, String routeId) {
        StoredRoute row = lookup(routeId).orElseThrow(() -> notFound("경로를 찾을 수 없습니다."));
        boolean attached = coupleId != null && jdbc.sql("""
                SELECT COUNT(*) FROM drives WHERE route_id = :route AND couple_id = :couple AND status <> 'CANCELLED'
                """).param("route", routeId).param("couple", coupleId).query(Long.class).single() > 0;
        if (!attached && !userId.equals(row.creatorId()) && (coupleId == null || !coupleId.equals(row.coupleId()))) {
            throw notFound("경로를 찾을 수 없습니다.");
        }
        if (!attached && !row.expiresAt().isAfter(Instant.now())) {
            throw new HomeExploreException(HttpStatus.GONE, "ROUTE_EXPIRED", "경로를 다시 검색해 주세요.");
        }
        return row;
    }

    private Optional<StoredRoute> lookup(String routeId) {
        return jdbc.sql("SELECT creator_id, couple_id, route_input, expires_at FROM place_routes WHERE id = :id")
                .param("id", routeId).query((row, index) -> new StoredRoute(routeId, row.getString("creator_id"),
                        row.getString("couple_id"), json.readValue(row.getString("route_input"), Input.class),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant())).optional();
    }

    private RouteView view(StoredRoute row, KakaoDirectionsClient.Directions calculated) {
        Input input = row.input();
        var directions = calculated == null ? kakao.find(input) : calculated;
        return new RouteView(row.id(), input.origin(), input.destination(), input.waypoints(),
                directions.polyline(), directions.distanceMeters(), directions.durationSeconds(),
                Instant.now().plusSeconds(directions.durationSeconds()), row.expiresAt(), "KAKAO", List.of());
    }

    private Place point(PlaceRouteController.Endpoint point) {
        String id = point.placeId();
        if (id == null) {
            return new Place(null, point.name(), point.address(),
                    HomeExploreController.coordinate(point.coordinate().latitude(), point.coordinate().longitude()));
        }
        PlaceCatalog.Place place = requiredSource(catalogs, "장소 정보를 확인할 수 없습니다.").findById(id)
                .orElseThrow(() -> notFound("저장 가능한 장소를 찾을 수 없습니다."));
        return new Place(place.id(), place.name(), place.address(), place.coordinate());
    }

    private String coupleId(String userId) {
        CurrentCoupleLookup provider = couples.getIfAvailable();
        if (provider == null) {
            return null;
        }
        return provider.findCurrent(userId)
                .filter(couple -> userId.equals(couple.firstMemberId()) || userId.equals(couple.secondMemberId()))
                .map(CurrentCoupleLookup.Couple::id).orElse(null);
    }

    enum Preference {
        RECOMMENDED("RECOMMEND"), FASTEST("TIME"), SHORTEST("DISTANCE");

        final String providerValue;

        Preference(String providerValue) {
            this.providerValue = providerValue;
        }
    }

    record Input(Place origin, Place destination, List<Place> waypoints, Preference preference) {
    }

    private record StoredRoute(String id, String creatorId, String coupleId, Input input, Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record RouteView(String id, Place origin, Place destination, List<Place> waypoints, String polyline,
            int distanceMeters, int durationSeconds, Instant estimatedArrivalAt, Instant expiresAt, String provider,
            List<String> warnings) {
    }
}
