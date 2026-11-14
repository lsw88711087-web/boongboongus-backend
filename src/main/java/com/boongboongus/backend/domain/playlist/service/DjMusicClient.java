package com.boongboongus.backend.domain.playlist.service;

import com.boongboongus.backend.domain.playlist.config.TrackCatalog.Song;
import com.boongboongus.backend.domain.playlist.dto.PlaylistRequest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class DjMusicClient {
    private static final int MAX_CANDIDATES = 24;
    private final RestClient http;
    private final JsonMapper json;
    private final String groqKey;
    private final String model;
    private final String spotifyId;
    private final String spotifySecret;
    private String spotifyToken;
    private Instant tokenExpiry = Instant.EPOCH;

    DjMusicClient(JsonMapper json, @Value("${GROQ_API_KEY:}") String groqKey,
            @Value("${GROQ_DJ_MODEL:openai/gpt-oss-20b}") String model,
            @Value("${SPOTIFY_CLIENT_ID:}") String spotifyId,
            @Value("${SPOTIFY_CLIENT_SECRET:}") String spotifySecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.json = json;
        this.groqKey = groqKey;
        this.model = model;
        this.spotifyId = spotifyId;
        this.spotifySecret = spotifySecret;
    }

    boolean configured() {
        return !groqKey.isBlank() && !spotifyId.isBlank() && !spotifySecret.isBlank();
    }

    List<Song> recommend(PlaylistRequest request, int targetSeconds) {
        int count = Math.clamp((targetSeconds / 180L) + 4, 5, MAX_CANDIDATES);
        var completion = http.post().uri("https://api.groq.com/openai/v1/chat/completions")
                .headers(headers -> headers.setBearerAuth(groqKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "max_completion_tokens", 4096,
                        "response_format", Map.of("type", "json_object"), "messages", List.of(
                                Map.of("role", "system", "content", """
                                        Recommend real songs for a drive in the given moods, weather and genres.
                                        Return JSON with a tracks array in recommendation order.
                                        Each item must have title and artist strings. Use the primary artist only.
                                        Omit songs you are unsure about and avoid duplicates.
                                        """),
                                Map.of("role", "user", "content", "Suggest up to %d songs. Moods: %s. Weather: %s. Genres: %s."
                                        .formatted(count, request.moods(), request.weather(), request.genres())))))
                .retrieve().body(JsonNode.class);
        var candidates = json.readTree(completion.path("choices").path(0).path("message").path("content").asText())
                .path("tracks");
        var songs = new ArrayList<Song>();
        var seen = new HashSet<String>();
        int duration = 0;
        for (int index = 0; index < Math.min(count, candidates.size()); index++) {
            var candidate = candidates.path(index);
            String title = candidate.path("title").asText("").strip();
            String artist = candidate.path("artist").asText("").strip();
            if (title.isEmpty() || artist.isEmpty() || title.length() > 200 || artist.length() > 160) {
                continue;
            }
            Song song = search(title, artist);
            if (song != null && seen.add(song.id())) {
                songs.add(song);
                duration += song.durationSeconds();
                if (duration >= (long) targetSeconds + 300) {
                    break;
                }
            }
        }
        return List.copyOf(songs);
    }

    private Song search(String title, String artist) {
        String token = spotifyAccessToken();
        var response = http.get()
                .uri("https://api.spotify.com/v1/search?type=track&market=KR&limit=5&q={query}",
                        "track:" + title + " artist:" + artist)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve().body(JsonNode.class);
        for (JsonNode track : response.path("tracks").path("items")) {
            String name = track.path("name").asText("");
            if (!normalized(name).equals(normalized(title)) || !hasArtist(track, artist)) {
                continue;
            }
            String id = track.path("id").asText("");
            String uri = track.path("uri").asText("");
            String link = track.path("external_urls").path("spotify").asText("");
            int seconds = track.path("duration_ms").asInt() / 1000;
            if (id.isBlank() || uri.isBlank() || link.isBlank() || seconds <= 0 || !track.path("is_playable").asBoolean(true)) {
                continue;
            }
            String image = track.path("album").path("images").path(0).path("url").asText(null);
            var artists = new ArrayList<String>();
            track.path("artists").forEach(node -> artists.add(node.path("name").asText()));
            return new Song("trk_spotify_" + id, name, String.join(", ", artists), seconds,
                    List.of(), List.of(), List.of(), id, image, uri, link);
        }
        return null;
    }

    private static boolean hasArtist(JsonNode track, String artist) {
        String expected = normalized(artist);
        for (JsonNode actual : track.path("artists")) {
            if (expected.equals(normalized(actual.path("name").asText("")))) {
                return true;
            }
        }
        return false;
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip()
                .replaceAll("\\s+", " ");
    }

    private synchronized String spotifyAccessToken() {
        if (Instant.now().isBefore(tokenExpiry)) {
            return spotifyToken;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        var response = http.post().uri("https://accounts.spotify.com/api/token")
                .headers(headers -> headers.setBasicAuth(spotifyId, spotifySecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        String token = response.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("Spotify did not return an access token");
        }
        spotifyToken = token;
        tokenExpiry = Instant.now().plusSeconds(Math.max(0, response.path("expires_in").asLong() - 30));
        return token;
    }
}

