package com.boongboongus.backend.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.drive.DriveLocationConsentLookup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlaceSearchRouteIntegrationTest.Fixtures.class)
class PlaceSearchRouteIntegrationTest {
    private static final String API = "/api/v1";
    private static final String HISTORY = API + "/me/search-history";
    private static final String ROUTES = API + "/routes";
    private static final String DRIVER = "route_driver";
    private static final String PARTNER = "route_partner";
    private static final String COUPLE = "route_couple";
    private static final String ORIGIN = """
            {"placeId":null,"name":"직접 입력한 출발지","address":"","coordinate":{"latitude":37.5,"longitude":127.0}}
            """;
    private static final String DESTINATION = """
            {"placeId":"plc_seoul_forest","name":"클라이언트 예시","address":"","coordinate":{"latitude":0,"longitude":0}}
            """;
    private static final AtomicInteger DIRECTION_CALLS = new AtomicInteger();
    private static final AtomicReference<String> PARKING_RESPONSE = new AtomicReference<>();
    private static HttpServer server;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/local/search/keyword.json", PlaceSearchRouteIntegrationTest::keyword);
        server.createContext("/v2/local/search/category.json", PlaceSearchRouteIntegrationTest::parking);
        server.createContext("/v2/local/geo/coord2address.json", PlaceSearchRouteIntegrationTest::address);
        server.createContext("/v1/directions", PlaceSearchRouteIntegrationTest::directions);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add("app.maps.kakao.local-base-url", () -> url);
        registry.add("app.maps.kakao.mobility-base-url", () -> url);
        registry.add("app.maps.kakao.local-key", () -> "fake-local-test-key");
        registry.add("app.maps.kakao.mobility-key", () -> "fake-mobility-test-key");
    }

    @AfterAll
    static void closeServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchUsesLivePagesAndExposesCatalogSaveEligibilityWithoutSavingHistory() throws Exception {
        String user = UUID.randomUUID().toString();
        var first = mvc.perform(get(API + "/places").principal(() -> user).param("q", "서울숲")
                .param("limit", "16").param("latitude", "37.5").param("longitude", "127"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(16))
                .andExpect(jsonPath("$.data.items[0].id").value("plc_seoul_forest"))
                .andExpect(jsonPath("$.data.items[0].canSave").value(true))
                .andExpect(jsonPath("$.data.items[1].category").value((Object) null))
                .andExpect(jsonPath("$.data.items[1].distanceMeters").value(500))
                .andExpect(jsonPath("$.data.items[1].canSave").value(false))
                .andExpect(jsonPath("$.data.hasMore").value(true)).andReturn();
        String cursor = data(first).path("nextCursor").asText();
        mvc.perform(get(API + "/places").principal(() -> user).param("q", "서울숲").param("cursor", cursor)
                .param("latitude", "37.5").param("longitude", "127"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].name").value("실시간 장소 16"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get(API + "/places").principal(() -> user).param("q", "다른 검색어").param("cursor", cursor)
                .param("latitude", "37.5").param("longitude", "127"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/places").principal(() -> user).param("q", "down"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.retryable").value(true));
        mvc.perform(get(HISTORY).principal(() -> user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void historyNormalizesLimitsReplaysAndOnlyDeletesForOwner() throws Exception {
        String user = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        var first = save(user, key, "Seoul  Forest");
        String id = first.path("id").asText();
        assertThat(save(user, key, "Seoul  Forest")).isEqualTo(first);
        mvc.perform(post(HISTORY).principal(() -> user).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"Other\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        for (int index = 0; index < 29; index++) {
            save(user, UUID.randomUUID().toString(), "Place " + index);
        }
        assertThat(save(user, UUID.randomUUID().toString(), "seoul forest").path("id").asText()).isEqualTo(id);
        save(user, UUID.randomUUID().toString(), "Latest");
        mvc.perform(get(HISTORY).principal(() -> user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(30))
                .andExpect(jsonPath("$.data[0].query").value("Latest"))
                .andExpect(jsonPath("$.data[1].id").value(id));
        mvc.perform(delete(HISTORY + "/" + id).principal(() -> "another_account")).andExpect(status().isNotFound());
        mvc.perform(delete(HISTORY + "/" + id).principal(() -> user)).andExpect(status().isNoContent());
    }

    @Test
    void reverseGeocodeDistinguishesEmptyAddressFromUpstreamFailure() throws Exception {
        String path = API + "/locations/reverse-geocode";
        mvc.perform(get(path).principal(() -> DRIVER).param("latitude", "37.5").param("longitude", "127"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.address").value("서울 성동구 뚝섬로 273"));
        mvc.perform(get(path).principal(() -> DRIVER).param("latitude", "0").param("longitude", "127"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.address").value((Object) null));
        mvc.perform(get(path).principal(() -> DRIVER).param("latitude", "-1").param("longitude", "127"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void placeDetailShowsNearbyParkingAndStillLoadsWhenParkingIsUnavailable() throws Exception {
        String path = API + "/places/plc_seoul_forest";
        PARKING_RESPONSE.set("""
                {"documents":[{"place_name":"서울숲 인근 주차장","distance":"200"}]}
                """);
        mvc.perform(get(path).principal(() -> DRIVER).param("latitude", "37.5").param("longitude", "127"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parkingInfo").value("서울숲 인근 주차장 · 목적지에서 200m"));

        PARKING_RESPONSE.set("{\"documents\":[]}");
        mvc.perform(get(path).principal(() -> DRIVER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parkingInfo").value((Object) null));

        PARKING_RESPONSE.set(null);
        mvc.perform(get(path).principal(() -> DRIVER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.place.id").value("plc_seoul_forest"))
                .andExpect(jsonPath("$.data.parkingInfo").value((Object) null));
    }

    @Test
    void liveRouteCanBeAttachedAndStartedButAnExpiredUnusedRouteCannotBeRead() throws Exception {
        String request = "{\"origin\":" + ORIGIN + ",\"destination\":" + DESTINATION + ",\"preference\":\"FASTEST\"}";
        String key = UUID.randomUUID().toString();
        int before = DIRECTION_CALLS.get();
        String id = data(mvc.perform(post(ROUTES).principal(() -> DRIVER).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.destination.name").value("서울숲"))
                .andExpect(jsonPath("$.data.polyline").isNotEmpty())
                .andExpect(jsonPath("$.data.provider").value("KAKAO")).andReturn()).path("id").asText();
        mvc.perform(post(ROUTES).principal(() -> DRIVER).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get(ROUTES + "/" + id).principal(() -> "third_party")).andExpect(status().isNotFound());
        mvc.perform(get(ROUTES + "/" + id).principal(() -> PARTNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(DIRECTION_CALLS.get() - before).isEqualTo(3);
        String stored = jdbc.sql("SELECT route_input FROM place_routes WHERE id = :id").param("id", id)
                .query(String.class).single();
        assertThat(stored).doesNotContain("polyline", "distanceMeters", "durationSeconds");

        String drive = data(mvc.perform(post(API + "/drives").principal(() -> DRIVER)
                .header("X-Couple-Id", COUPLE).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"driverUserId":"%s","passengerUserId":"%s","recorderUserId":"%s","routeId":"%s"}
                        """.formatted(DRIVER, PARTNER, DRIVER, id)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.destination.name").value("서울숲"))
                .andReturn()).path("id").asText();
        mvc.perform(post(API + "/drives/" + drive + "/start").principal(() -> DRIVER)
                .header("X-Couple-Id", COUPLE).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"initialCoordinate\":{\"latitude\":37.5,\"longitude\":127}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        jdbc.sql("UPDATE place_routes SET expires_at = '2020-01-01T00:00:00Z' WHERE id = :id")
                .param("id", id).update();
        mvc.perform(get(ROUTES + "/" + id).principal(() -> PARTNER)).andExpect(status().isOk());

        String unused = data(mvc.perform(post(ROUTES).principal(() -> DRIVER)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isCreated()).andReturn()).path("id").asText();
        jdbc.sql("UPDATE place_routes SET expires_at = '2020-01-01T00:00:00Z' WHERE id = :id")
                .param("id", unused).update();
        mvc.perform(get(ROUTES + "/" + unused).principal(() -> DRIVER)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("ROUTE_EXPIRED"));
        mvc.perform(get(ROUTES + "/" + unused).principal(() -> "third_party")).andExpect(status().isNotFound());
    }

    private JsonNode save(String user, String key, String query) throws Exception {
        return data(mvc.perform(post(HISTORY).principal(() -> user).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("query", query))))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode data(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static void keyword(HttpExchange exchange) throws IOException {
        String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        if (!"KakaoAK fake-local-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                || !query.contains("size=15") || query.contains("query=down")) {
            respond(exchange, 503, "{}");
            return;
        }
        boolean first = query.contains("page=1");
        String documents = IntStream.rangeClosed(first ? 1 : 16, first ? 15 : 16).mapToObj(index -> """
                {"id":"%d","place_name":"실시간 장소 %d","x":"127.1","y":"37.6","distance":"500",
                "road_address_name":"서울 성동구 연무장길","address_name":"","category_group_code":"CE7"}
                """.formatted(index, index)).collect(Collectors.joining(","));
        respond(exchange, 200, "{\"meta\":{\"is_end\":" + !first + "},\"documents\":[" + documents + "]}");
    }

    private static void address(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query.contains("y=-1")) {
            respond(exchange, 503, "{}");
        } else if (query.contains("y=0")) {
            respond(exchange, 200, "{\"documents\":[]}");
        } else {
            respond(exchange, 200, """
                    {"documents":[{"road_address":{"address_name":"서울 성동구 뚝섬로 273",
                    "region_1depth_name":"서울","region_2depth_name":"성동구"},"address":null}]}
                    """);
        }
    }

    private static void parking(HttpExchange exchange) throws IOException {
        var parameters = List.of(exchange.getRequestURI().getRawQuery().split("&"));
        if (!"GET".equals(exchange.getRequestMethod())
                || !"KakaoAK fake-local-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                || !parameters.containsAll(List.of("category_group_code=PK6", "x=127.0417982", "y=37.5430717",
                        "radius=500", "sort=distance", "size=1"))) {
            respond(exchange, 400, "{}");
            return;
        }
        String body = PARKING_RESPONSE.get();
        respond(exchange, body == null ? 503 : 200, body == null ? "{}" : body);
    }

    private static void directions(HttpExchange exchange) throws IOException {
        DIRECTION_CALLS.incrementAndGet();
        String query = exchange.getRequestURI().getRawQuery();
        if (!"KakaoAK fake-mobility-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                || !query.contains("priority=TIME") || !query.contains("summary=false")
                || !query.contains("destination=127.0417982,37.5430717")) {
            respond(exchange, 503, "{}");
            return;
        }
        respond(exchange, 200, """
                {"routes":[{"result_code":0,"summary":{"distance":5100,"duration":680},
                "sections":[{"roads":[{"vertexes":[127.0,37.5,127.0417982,37.5430717]}]}]}]}
                """);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        CurrentCoupleLookup currentCoupleLookup() {
            return user -> DRIVER.equals(user) || PARTNER.equals(user)
                    ? Optional.of(new CurrentCoupleLookup.Couple(COUPLE, DRIVER, PARTNER)) : Optional.empty();
        }

        @Bean
        DriveLocationConsentLookup locationConsent() {
            return DRIVER::equals;
        }
    }
}
