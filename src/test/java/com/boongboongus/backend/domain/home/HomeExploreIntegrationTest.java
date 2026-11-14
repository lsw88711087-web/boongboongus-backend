package com.boongboongus.backend.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.drive.DriveLocationConsentLookup;
import com.boongboongus.backend.domain.drive.DriveRouteLookup;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.home.HomeDataSource.Couple;
import com.boongboongus.backend.domain.home.HomeDataSource.Member;
import com.boongboongus.backend.domain.home.HomeDataSource.PendingCard;
import com.boongboongus.backend.domain.home.HomeDataSource.Summary;
import com.boongboongus.backend.domain.home.HomeDataSource.User;
import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import com.boongboongus.backend.domain.home.PlaceCatalog.Place;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HomeExploreIntegrationTest.Fixtures.class)
class HomeExploreIntegrationTest {
    private static final String API = "/api/v1";
    private static final String COUPLE_ID = "home_test_couple";
    private static final String RECORDER = "home_test_recorder";
    private static final String PARTNER = "home_test_partner";
    private static final String SINGLE = "home_test_single";
    private static HttpServer weatherServer;

    @DynamicPropertySource
    static void configureWeather(DynamicPropertyRegistry properties) throws IOException {
        weatherServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        weatherServer.createContext("/getUltraSrtNcst", HomeExploreIntegrationTest::weather);
        weatherServer.start();
        properties.add("app.weather.kma.base-url", () -> "http://127.0.0.1:" + weatherServer.getAddress().getPort());
        properties.add("app.weather.kma.service-key", () -> "test%2Bkey%2F%3D");
    }

    @AfterAll
    static void stopWeatherServer() {
        if (weatherServer != null) {
            weatherServer.stop(0);
        }
    }

    private static void weather(HttpExchange exchange) throws IOException {
        var params = List.of(exchange.getRequestURI().getRawQuery().split("&"));
        if (!params.containsAll(List.of("nx=60", "ny=127", "serviceKey=test%2Bkey%2F%3D", "dataType=JSON"))
                || params.stream().noneMatch(param -> param.matches("base_date=[0-9]{8}"))
                || params.stream().noneMatch(param -> param.matches("base_time=[0-9]{2}00"))) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        byte[] json = """
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                  {"category":"T1H","obsrValue":"-2.5"},{"category":"PTY","obsrValue":"3"}
                ]}}}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.length);
        try (var output = exchange.getResponseBody()) {
            output.write(json);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    HardcodedPlaceCatalog hardcodedCatalog;

    @Test
    void hardcodedCatalogResolvesTheSamePlaceByCategoryAndIdAndPublishesItAsPopular() {
        var place = hardcodedCatalog.findByCategory(Category.NATURE).getFirst();
        assertThat(hardcodedCatalog.findById(place.id())).contains(place);
        assertThat(hardcodedCatalog.findById("unknown")).isEmpty();
        assertThat(hardcodedCatalog.findPopularDestinations()).containsExactly(place);
    }

    @Test
    void homeWithoutLocationShowsPartnersActiveDriveAndFiltersPendingCards() throws Exception {
        var prepared = mvc.perform(driveRequest("/drives", """
                {"driverUserId":"%s","passengerUserId":"%s","recorderUserId":"%s","routeId":"home_fixture_route"}
                """.formatted(RECORDER, PARTNER, RECORDER))).andExpect(status().isCreated()).andReturn();
        String driveId = data(prepared).path("id").asText();
        mvc.perform(driveRequest("/drives/" + driveId + "/start", """
                {"expectedVersion":1,"initialCoordinate":{"latitude":37.5,"longitude":127.0}}
                """)).andExpect(status().isOk());

        mvc.perform(get(API + "/home").principal(() -> PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.id").value(PARTNER))
                .andExpect(jsonPath("$.data.activeDriveId").value(driveId))
                .andExpect(jsonPath("$.data.context.weather").value((Object) null))
                .andExpect(jsonPath("$.data.context.temperatureC").value(nullValue()))
                .andExpect(jsonPath("$.data.context.weatherAvailable").value(false))
                .andExpect(jsonPath("$.data.context.trafficAvailable").value(false))
                .andExpect(jsonPath("$.data.pendingCards.length()").value(2))
                .andExpect(jsonPath("$.data.pendingCards[0].cardId").value("earlier"))
                .andExpect(jsonPath("$.data.pendingCards[1].cardId").value("later"));
        mvc.perform(get(API + "/drives/active").principal(() -> PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drive.id").value(driveId))
                .andExpect(jsonPath("$.data.drive.status").value("ACTIVE"));
        mvc.perform(get(API + "/home").principal(() -> SINGLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeDriveId").value((Object) null))
                .andExpect(jsonPath("$.data.pendingCards").isEmpty());
    }

    @Test
    void homeKeepsBaseDataWhenWeatherAndTrafficAreUnavailable() throws Exception {
        mvc.perform(get(API + "/home").principal(() -> SINGLE)
                .param("latitude", "37.5").param("longitude", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.id").value(SINGLE))
                .andExpect(jsonPath("$.data.context.temperatureC").value(nullValue()))
                .andExpect(jsonPath("$.data.context.weatherAvailable").value(false));
        mvc.perform(get(API + "/home").principal(() -> SINGLE).param("latitude", "37.5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/home")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void homeReturnsCelsiusTemperatureWhenAvailable() throws Exception {
        mvc.perform(get(API + "/home").principal(() -> SINGLE)
                .param("latitude", "37.5665").param("longitude", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.context.weather").value("눈"))
                .andExpect(jsonPath("$.data.context.temperatureC").value(-2.5))
                .andExpect(jsonPath("$.data.context.observedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.context.weatherAvailable").value(true));
    }

    @Test
    void refreshedRecommendationsLeadToTheSamePlaceDetail() throws Exception {
        mvc.perform(get(API + "/place-categories").principal(() -> SINGLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));
        JsonNode first = data(mvc.perform(get(API + "/places/recommendations")
                .principal(() -> SINGLE).param("category", "SEA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(7)).andReturn());
        JsonNode second = data(mvc.perform(get(API + "/places/recommendations")
                .principal(() -> SINGLE).param("category", "SEA")
                .param("refreshToken", first.path("refreshToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(7)).andReturn());
        String placeId = second.path("items").get(0).path("id").asText();
        assertThat(placeId).isNotEqualTo(first.path("items").get(0).path("id").asText());
        mvc.perform(get(API + "/places/{placeId}", placeId).principal(() -> SINGLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.place.id").value(placeId))
                .andExpect(jsonPath("$.data.place.distanceMeters").value((Object) null))
                .andExpect(jsonPath("$.data.previewRouteId").value((Object) null));
        mvc.perform(get(API + "/places/{placeId}", "missing").principal(() -> SINGLE))
                .andExpect(status().isNotFound());
    }

    @Test
    void popularDestinationsPreserveSourceOrderAndValidateTheCursor() throws Exception {
        JsonNode first = data(mvc.perform(get(API + "/routes/popular").principal(() -> SINGLE).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("place-8"))
                .andExpect(jsonPath("$.data.hasMore").value(true)).andReturn());
        mvc.perform(get(API + "/routes/popular").principal(() -> SINGLE).param("limit", "1")
                .param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("place-7"))
                .andExpect(jsonPath("$.data.nextCursor").value((Object) null));
        mvc.perform(get(API + "/routes/popular").principal(() -> SINGLE).param("cursor", "invalid-token"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private static MockHttpServletRequestBuilder driveRequest(String endpoint, String body) {
        return post(API + endpoint).principal(() -> RECORDER).header("X-Couple-Id", COUPLE_ID)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        HomeDataSource homeDataSource() {
            var members = List.of(new Member(RECORDER, "운전자", null, null, "KAKAO"),
                    new Member(PARTNER, "동승자", null, null, "KAKAO"));
            var couple = new Couple(COUPLE_ID, "CONNECTED", members, LocalDate.of(2026, 1, 1), 1, 0, 0, 1, "붕붕이");
            return userId -> {
                Instant now = Instant.now();
                var me = new User(userId, "테스트 회원", null, null, "KAKAO", true, null);
                var cards = List.of(pending("later", now.plusSeconds(7200)), pending("expired", now.minusSeconds(1)),
                        pending("earlier", now.plusSeconds(3600)));
                return Optional.of(new Summary(me, SINGLE.equals(userId) ? null : couple, 2, cards));
            };
        }

        @Bean
        CurrentCoupleLookup currentCoupleLookup() {
            return id -> RECORDER.equals(id) || PARTNER.equals(id)
                    ? Optional.of(new CurrentCoupleLookup.Couple(COUPLE_ID, RECORDER, PARTNER)) : Optional.empty();
        }

        @Bean
        @Primary
        DriveRouteLookup driveRouteLookup() {
            var origin = new DriveRouteLookup.Place(null, "출발지", "", new Coordinate(37.5, 127.0));
            var destination = new DriveRouteLookup.Place("place-1", "목적지", "", new Coordinate(37.6, 127.1));
            return (userId, coupleId, routeId) -> Optional.of(new DriveRouteLookup.Route(origin, destination));
        }

        @Bean
        DriveLocationConsentLookup driveLocationConsentLookup() {
            return RECORDER::equals;
        }

        @Bean
        @Primary
        PlaceCatalog placeCatalog() {
            var places = IntStream.rangeClosed(1, 8).mapToObj(i -> new Place("place-" + i, "장소 " + i, "서울",
                    "서울 예시 주소", new Coordinate(37.6, 127.1), null, Category.SEA, List.of(), List.of(),
                    null, null, null, List.of())).toList();
            return new PlaceCatalog() {
                @Override
                public List<Place> findByCategory(Category category) {
                    return places.stream().filter(place -> place.category() == category).toList();
                }

                @Override
                public List<Place> findPopularDestinations() {
                    return List.of(places.getLast(), places.get(6));
                }

                @Override
                public Optional<Place> findById(String placeId) {
                    return places.stream().filter(place -> placeId.equals(place.id())).findFirst();
                }
            };
        }

        private static PendingCard pending(String cardId, Instant expiry) {
            return new PendingCard(cardId, "drive-" + cardId, cardId, "UNWRITTEN", expiry, 0, false);
        }
    }
}
