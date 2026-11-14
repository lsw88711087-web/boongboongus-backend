package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreException.badRequest;

import com.boongboongus.backend.domain.drive.DrivePreparationService;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.home.HomeContextLookup.Context;
import com.boongboongus.backend.domain.home.HomeDataSource.Couple;
import com.boongboongus.backend.domain.home.HomeDataSource.PendingCard;
import com.boongboongus.backend.domain.home.HomeDataSource.User;
import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import com.boongboongus.backend.domain.home.PlaceCatalog.Place;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
class HomeExploreService {
    private static final int RECOMMENDATION_LIMIT = 7;
    private static final String DEFAULT_MASCOT_NAME = "붕붕이";
    private final ObjectProvider<HomeDataSource> home;
    private final ObjectProvider<HomeContextLookup> context;
    private final ObjectProvider<PlaceCatalog> catalog;
    private final DrivePreparationService drives;
    private final FavoritePlaceStore favorites;
    private final KakaoPlaceClient kakaoPlaces;

    HomeExploreService(ObjectProvider<HomeDataSource> home, ObjectProvider<HomeContextLookup> context,
            ObjectProvider<PlaceCatalog> catalog, DrivePreparationService drives, FavoritePlaceStore favorites,
            KakaoPlaceClient kakaoPlaces) {
        this.home = home;
        this.context = context;
        this.catalog = catalog;
        this.drives = drives;
        this.favorites = favorites;
        this.kakaoPlaces = kakaoPlaces;
    }

    HomeView home(String userId, Coordinate location) {
        var data = requiredSource(home, "홈 계정 정보를 확인할 수 없습니다.").findForUser(userId)
                .filter(summary -> userId.equals(summary.me().id()))
                .orElseThrow(() -> notFound("계정 정보를 찾을 수 없습니다."));
        Couple couple = data.couple();
        if (couple == null || !"CONNECTED".equals(couple.status())
                || couple.members().stream().noneMatch(member -> userId.equals(member.id()))) {
            return new HomeView(data.me(), null, DEFAULT_MASCOT_NAME, data.unreadNotificationCount(),
                    null, List.of(), homeContext(location));
        }
        Instant now = Instant.now();
        List<PendingCard> pending = data.pendingCards().stream()
                .filter(card -> ("UNWRITTEN".equals(card.status()) || "DRAFT".equals(card.status()))
                        && now.isBefore(card.editableUntil()))
                .sorted(Comparator.comparing(PendingCard::editableUntil).thenComparing(PendingCard::cardId))
                .map(card -> new PendingCard(card.cardId(), card.driveId(), card.title(), card.status(),
                        card.editableUntil(), Duration.between(now, card.editableUntil()).toSeconds(),
                        card.hasDraftContent()))
                .toList();
        return new HomeView(data.me(), couple, couple.mascotName(), data.unreadNotificationCount(),
                drives.findActiveDriveId(couple.id()).orElse(null), pending, homeContext(location));
    }

    Recommendations recommendations(String userId, Category category, String refreshToken, Coordinate location) {
        PlaceCatalog source = requiredSource(catalog, "장소 추천 정보를 확인할 수 없습니다.");
        String prefix = "recommendations/" + category.name() + "/";
        String previous = decodeToken(refreshToken, prefix);
        List<Place> candidates = unique(source.findByCategory(category).stream()
                .filter(place -> category == place.category())
                .sorted(Comparator.comparing(Place::id)).toList());
        int size = candidates.size();
        int start = size <= RECOMMENDATION_LIMIT ? 0 : indexAfter(candidates, previous, false) % size;
        var items = new ArrayList<PlaceSummary>();
        for (int offset = 0; offset < Math.min(size, RECOMMENDATION_LIMIT); offset++) {
            items.add(summarize(source, userId, candidates.get((start + offset) % size), location));
        }
        String lastId = items.isEmpty() ? "" : items.getLast().id();
        return new Recommendations(category, items, encodeToken(prefix + lastId), Instant.now());
    }

    PopularPage popular(String userId, String cursor, int limit, Coordinate location) {
        PlaceCatalog source = requiredSource(catalog, "인기 목적지 정보를 확인할 수 없습니다.");
        String prefix = "popular/";
        String previous = decodeToken(cursor, prefix);
        List<Place> ranked = unique(source.findPopularDestinations());
        int start = indexAfter(ranked, previous, true);
        int end = start + Math.min(limit, ranked.size() - start);
        List<PlaceSummary> items = ranked.subList(start, end).stream()
                .map(place -> summarize(source, userId, place, location)).toList();
        boolean hasMore = end < ranked.size();
        return new PopularPage(items, hasMore ? encodeToken(prefix + items.getLast().id()) : null, hasMore);
    }

    PlaceDetail detail(String userId, String placeId, Coordinate location) {
        PlaceCatalog source = requiredSource(catalog, "장소 상세 정보를 확인할 수 없습니다.");
        Place place = source.findById(placeId).orElseThrow(() -> notFound("장소를 찾을 수 없습니다."));
        return new PlaceDetail(summarize(source, userId, place, location), place.photos(), place.introduction(),
                parkingInfo(place), place.recommendedVisitTime(), place.mainRoads(), null);
    }

    private String parkingInfo(Place place) {
        if (place.parkingInfo() != null && !place.parkingInfo().isBlank()) {
            return place.parkingInfo();
        }
        try {
            return kakaoPlaces.nearestParking(place.coordinate())
                    .map(parking -> parking.distance() != null && parking.distance().matches("[0-9]+")
                            ? parking.name() + " · 목적지에서 " + parking.distance() + "m"
                            : parking.name())
                    .orElse(null);
        } catch (HomeExploreException exception) {
            return null;
        }
    }

    private Context homeContext(Coordinate location) {
        if (location == null) {
            return Context.unavailable();
        }
        HomeContextLookup source = context.getIfAvailable();
        if (source == null) {
            return Context.unavailable();
        }
        try {
            return source.find(location);
        } catch (RestClientException exception) {
            return Context.unavailable();
        }
    }

    PlaceSummary summarize(PlaceCatalog source, String userId, Place place, Coordinate location) {
        return new PlaceSummary(place.id(), place.name(), place.region(), place.address(), place.coordinate(),
                place.thumbnailUrl(), place.category(), place.hashtags(), null,
                location == null ? null : distanceMeters(location, place.coordinate()),
                favorites.contains(userId, place.id()) || source.isFavorite(userId, place.id()));
    }

    private static List<Place> unique(List<Place> places) {
        var byId = new LinkedHashMap<String, Place>();
        for (Place place : places) {
            byId.putIfAbsent(place.id(), place);
        }
        return List.copyOf(byId.values());
    }

    private static int indexAfter(List<Place> places, String previousId, boolean rejectMissing) {
        if (previousId == null || previousId.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < places.size(); index++) {
            if (previousId.equals(places.get(index).id())) {
                return index + 1;
            }
        }
        if (rejectMissing) {
            throw badRequest("유효한 cursor를 보내 주세요.");
        }
        return 0;
    }

    static String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeToken(String token, String prefix) {
        if (token == null) {
            return null;
        }
        if (token.isEmpty() || token.length() > 200) {
            throw badRequest("조회 토큰을 확인해 주세요.");
        }
        String value;
        try {
            value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw badRequest("조회 토큰을 확인해 주세요.");
        }
        if (!value.startsWith(prefix) || (value.length() == prefix.length() && "popular/".equals(prefix))) {
            throw badRequest("조회 토큰을 확인해 주세요.");
        }
        return value.substring(prefix.length());
    }

    private static double distanceMeters(Coordinate origin, Coordinate destination) {
        double deltaLat = Math.toRadians(destination.latitude() - origin.latitude());
        double deltaLon = Math.toRadians(destination.longitude() - origin.longitude());
        double sinLat = Math.sin(deltaLat / 2);
        double sinLon = Math.sin(deltaLon / 2);
        double angle = sinLat * sinLat + Math.cos(Math.toRadians(origin.latitude()))
                * Math.cos(Math.toRadians(destination.latitude())) * sinLon * sinLon;
        return 12_742_000 * Math.asin(Math.sqrt(Math.clamp(angle, 0, 1)));
    }

    static <T> T requiredSource(ObjectProvider<T> provider, String message) {
        T source = provider.getIfAvailable();
        if (source == null) {
            throw new HomeExploreException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
        }
        return source;
    }

    static HomeExploreException notFound(String message) {
        return new HomeExploreException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record HomeView(User me, Couple couple, String mascotName, int unreadNotificationCount, String activeDriveId,
            List<PendingCard> pendingCards, Context context) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PlaceSummary(String id, String name, String region, String address, Coordinate coordinate,
            String thumbnailUrl, Category category, List<String> hashtags, Integer estimatedDurationSeconds,
            Double distanceMeters, boolean isFavorite) {
    }

    record Recommendations(Category category, List<PlaceSummary> items, String refreshToken, Instant generatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PopularPage(List<PlaceSummary> items, String nextCursor, boolean hasMore) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PlaceDetail(PlaceSummary place, List<String> photos, String introduction, String parkingInfo,
            String recommendedVisitTime, List<String> mainRoads, String previewRouteId) {
    }
}
