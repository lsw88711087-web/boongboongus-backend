package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class KakaoPlaceClient {
    static final int KEYWORD_PAGE_SIZE = 15;
    static final int MAX_PAGE = 45;
    private static final int PARKING_RADIUS_METERS = 500;
    private final RestClient http;
    private final URI baseUrl;
    private final String apiKey;

    KakaoPlaceClient(@Value("${app.maps.kakao.local-base-url}") URI baseUrl,
            @Value("${app.maps.kakao.local-key:}") String apiKey) {
        this.http = RestClient.builder().requestFactory(requestFactory()).build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    KeywordPage search(String query, Coordinate coordinate, int page) {
        KeywordPage result = get("/v2/local/search/keyword.json", KeywordPage.class, uri -> {
            uri.queryParam("query", "{query}").queryParam("size", KEYWORD_PAGE_SIZE).queryParam("page", page);
            if (coordinate != null) {
                uri.queryParam("x", coordinate.longitude()).queryParam("y", coordinate.latitude());
            }
        }, query);
        if (result.meta() == null || result.documents() == null) {
            throw unavailable();
        }
        return result;
    }

    Optional<Address> address(Coordinate coordinate) {
        AddressPage result = get("/v2/local/geo/coord2address.json", AddressPage.class,
                uri -> uri.queryParam("x", coordinate.longitude()).queryParam("y", coordinate.latitude()));
        if (result.documents() == null) {
            throw unavailable();
        }
        return result.documents().stream().findFirst();
    }

    Optional<KeywordPlace> nearestParking(Coordinate destination) {
        KeywordPage result = get("/v2/local/search/category.json", KeywordPage.class, uri -> uri
                .queryParam("category_group_code", "PK6")
                .queryParam("x", destination.longitude()).queryParam("y", destination.latitude())
                .queryParam("radius", PARKING_RADIUS_METERS).queryParam("sort", "distance").queryParam("size", 1));
        if (result.documents() == null) {
            throw unavailable();
        }
        return result.documents().stream()
                .filter(place -> place != null && place.name() != null && !place.name().isBlank())
                .findFirst();
    }

    private <T> T get(String path, Class<T> type, Consumer<UriComponentsBuilder> parameters, Object... values) {
        if (apiKey.isBlank()) {
            throw unavailable();
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(baseUrl).path(path);
        parameters.accept(uri);
        try {
            T result = http.get().uri(uri.encode().buildAndExpand(values).toUri())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey).retrieve().body(type);
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(4));
        return factory;
    }

    private static HomeExploreException unavailable() {
        return new HomeExploreException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "카카오맵 장소 정보를 사용할 수 없습니다.");
    }

    record KeywordPage(KeywordMeta meta, List<KeywordPlace> documents) {
    }

    record KeywordMeta(@JsonProperty("is_end") boolean end) {
    }

    record KeywordPlace(String id, @JsonProperty("place_name") String name,
            @JsonProperty("address_name") String address, @JsonProperty("road_address_name") String roadAddress,
            @JsonProperty("category_group_code") String categoryGroup, String x, String y, String distance) {
    }

    record AddressPage(List<Address> documents) {
    }

    record Address(AddressData address, @JsonProperty("road_address") AddressData roadAddress) {
    }

    record AddressData(@JsonProperty("address_name") String name,
            @JsonProperty("region_1depth_name") String region1, @JsonProperty("region_2depth_name") String region2) {
    }
}
