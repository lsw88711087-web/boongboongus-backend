package com.boongboongus.backend.domain.playlist.service;

import com.boongboongus.backend.common.api.ErrorResponse.ErrorDetail;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.appconfig.AppConfigResponse;
import com.boongboongus.backend.domain.drive.DrivePreparationService;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.job.dto.JobResponse.Kind;
import com.boongboongus.backend.domain.job.service.JobService;
import com.boongboongus.backend.domain.playlist.config.TrackCatalog;
import com.boongboongus.backend.domain.playlist.config.TrackCatalog.Song;
import com.boongboongus.backend.domain.playlist.dto.PlaylistRequest;
import com.boongboongus.backend.domain.playlist.dto.PlaylistResponse;
import com.boongboongus.backend.domain.playlist.dto.PlaylistTracksPatch;
import com.boongboongus.backend.domain.playlist.dto.PlaylistResponse.Track;
import com.boongboongus.backend.domain.playlist.repository.PlaylistRepository;
import com.boongboongus.backend.domain.playlist.repository.PlaylistRepository.Pending;
import com.boongboongus.backend.domain.playlist.dto.PlaylistLibraryResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlaylistService {
    private static final Logger log = LoggerFactory.getLogger(PlaylistService.class);
    private final AppConfigResponse options;
    private final TrackCatalog catalog;
    private final DjMusicClient music;
    private final DrivePreparationService drives;
    private final PlaylistRepository playlists;
    private final JobService jobs;
    private final IdempotentWrite idempotency;
    private final TransactionTemplate transaction;

    PlaylistService(AppConfigResponse options, TrackCatalog catalog, DjMusicClient music, DrivePreparationService drives,
            PlaylistRepository playlists, JobService jobs, IdempotentWrite idempotency, TransactionTemplate transaction) {
        this.options = options;
        this.catalog = catalog;
        this.music = music;
        this.drives = drives;
        this.playlists = playlists;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.transaction = transaction;
    }

    public JobResponse request(String userId, String key, PlaylistRequest input) {
        var genres = input.genres() == null ? List.<String>of() : input.genres();
        var request = new PlaylistRequest(input.driveId(), input.moods(), input.weather(), genres);
        validateOptions(request.moods(), options.moods(), options.limits().moodMaxCount());
        validateOptions(genres, options.genres(), options.limits().genreMaxCount());
        if (!options.weatherOptions().contains(request.weather())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
        }

        return idempotency.run(userId, key, List.of("create-playlist", request), JobResponse.class, () -> {
            if (request.driveId() != null && !drives.canAccessPlaylistDrive(userId, request.driveId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            if (!music.configured() && catalog.tracks().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            JobResponse job = jobs.enqueue(userId, Kind.PLAYLIST_GENERATION);
            playlists.queue(job.id(), request);
            return job;
        });
    }

    public PlaylistResponse get(String userId, String playlistId) {
        return withBookmark(accessible(userId, playlistId), userId);
    }

    private PlaylistResponse accessible(String userId, String playlistId) {
        return playlists.find(playlistId).filter(playlist -> userId.equals(playlist.owner())
                || (playlist.driveId() != null && drives.canAccessPlaylistDrive(userId, playlist.driveId())))
                .map(PlaylistRepository.Stored::result)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public void bookmark(String userId, String playlistId) {
        transaction.executeWithoutResult(status -> {
            accessible(userId, playlistId);
            playlists.bookmark(userId, playlistId);
        });
    }

    public void removeBookmark(String userId, String playlistId) {
        playlists.removeBookmark(userId, playlistId);
    }

    public PlaylistLibraryResponse library(String userId, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        OffsetDateTime savedAt = null;
        String playlistId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 512) {
                    throw new IllegalArgumentException();
                }
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split("\\|", -1);
                if (parts.length != 3 || !userId.equals(parts[0]) || parts[2].isBlank() || parts[2].length() > 80) {
                    throw new IllegalArgumentException();
                }
                savedAt = OffsetDateTime.parse(parts[1]);
                playlistId = parts[2];
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        var rows = playlists.bookmarks(userId, savedAt, playlistId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        var items = page.stream().map(row -> {
            var playlist = row.playlist();
            String thumbnail = playlist.tracks().stream().map(Track::albumImageUrl)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return new PlaylistLibraryResponse.Item(playlist.id(), playlist.title(), playlist.trackCount(),
                    playlist.durationSeconds(), thumbnail, row.driveId(), row.driveTitle());
        }).toList();
        String nextCursor = null;
        if (hasMore) {
            var last = page.getLast();
            String marker = userId + "|" + last.savedAt() + "|" + last.playlist().id();
            nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(marker.getBytes(StandardCharsets.UTF_8));
        }
        return new PlaylistLibraryResponse(items, nextCursor, hasMore, playlists.bookmarkCount(userId));
    }

    private PlaylistResponse withBookmark(PlaylistResponse playlist, String userId) {
        return new PlaylistResponse(playlist.id(), playlist.title(), playlist.moods(), playlist.weather(),
                playlist.genres(), playlist.targetDurationSeconds(), playlist.tracks(), playlist.trackCount(),
                playlist.durationSeconds(), playlists.bookmarked(userId, playlist.id()), playlist.createdAt(),
                playlist.version());
    }

    public PlaylistResponse editTracks(String userId, String playlistId, PlaylistTracksPatch request) {
        return transaction.execute(status -> {
            var stored = playlists.lock(playlistId)
                    .filter(playlist -> playlist.driveId() == null ? userId.equals(playlist.owner())
                            : drives.canAccessPlaylistDrive(userId, playlist.driveId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var current = stored.result();
            if (current.version() != request.expectedVersion()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }

            var existing = new java.util.HashMap<String, Track>();
            current.tracks().forEach(track -> existing.put(track.id(), track));
            var tracks = new ArrayList<Track>();
            int duration = 0;
            for (String id : request.trackIds()) {
                Track track = existing.remove(id);
                if (track == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TRACK_SET");
                }
                tracks.add(new Track(track.id(), track.providerTrackId(), track.title(), track.artist(),
                        track.durationSeconds(), track.albumImageUrl(), track.providerUri(), track.externalUrl(),
                        tracks.size()));
                duration += track.durationSeconds();
            }
            var updated = new PlaylistResponse(current.id(), current.title(), current.moods(), current.weather(),
                    current.genres(), current.targetDurationSeconds(), List.copyOf(tracks), tracks.size(), duration,
                    current.savedByMe(), current.createdAt(), current.version() + 1);
            playlists.update(updated);
            return withBookmark(updated, userId);
        });
    }

    @Scheduled(fixedDelay = 2000)
    void generatePending() {
        for (Pending pending : playlists.pending()) {
            if (jobs.start(pending.jobId())) {
                generate(pending);
            }
        }
    }

    private void generate(Pending pending) {
        try {
            var request = pending.request();
            int target = request.driveId() == null ? 3600 : drives.playlistDuration(pending.owner(), request.driveId());
            var result = recommend(request, target);
            transaction.executeWithoutResult(status -> {
                if (jobs.succeed(pending.jobId(), result.id())) {
                    playlists.save(pending.owner(), request.driveId(), result);
                }
            });
        } catch (NoMatchingTracks exception) {
            jobs.fail(pending.jobId(), new ErrorDetail("RESOURCE_NOT_FOUND",
                    "조건에 맞는 곡을 찾지 못했습니다.", List.of(), false));
        } catch (org.springframework.web.client.RestClientException exception) {
            log.warn("External music request failed for {} ({})",
                    pending.jobId(), exception.getClass().getSimpleName());
            jobs.fail(pending.jobId(), new ErrorDetail("SERVICE_UNAVAILABLE",
                    "음악 추천 서비스에 연결하지 못했습니다.", List.of(), true));
        } catch (ResponseStatusException exception) {
            boolean retryable = exception.getStatusCode().is5xxServerError();
            jobs.fail(pending.jobId(), new ErrorDetail(retryable ? "SERVICE_UNAVAILABLE" : "RESOURCE_NOT_FOUND",
                    "추천에 필요한 주행 정보를 확인할 수 없습니다.", List.of(), retryable));
        } catch (RuntimeException exception) {
            log.error("Playlist generation failed for {}", pending.jobId(), exception);
            jobs.fail(pending.jobId(), new ErrorDetail("SERVICE_UNAVAILABLE",
                    "플레이리스트를 생성하지 못했습니다.", List.of(), true));
        }
    }

    private PlaylistResponse recommend(PlaylistRequest request, int target) {
        var ranked = music.configured() ? music.recommend(request, target) : catalog.tracks().stream().sorted(
                Comparator.<Song>comparingInt(song -> score(song, request)).reversed().thenComparing(Song::id)).toList();
        if (ranked.isEmpty()) {
            throw new NoMatchingTracks();
        }
        List<Track> tracks = new ArrayList<>();
        int duration = 0;
        for (Song song : ranked) {
            if (!tracks.isEmpty() && song.durationSeconds() > target - duration) {
                continue;
            }
            tracks.add(new Track(song.id(), song.providerTrackId(), song.title(), song.artist(),
                    song.durationSeconds(), song.albumImageUrl(), song.providerUri(), song.externalUrl(), tracks.size()));
            duration += song.durationSeconds();
        }
        return new PlaylistResponse("ply_" + UUID.randomUUID(), "드라이브 추천 플레이리스트", request.moods(),
                request.weather(), request.genres(), target, List.copyOf(tracks), tracks.size(), duration,
                false, Instant.now(), 1);
    }

    private static final class NoMatchingTracks extends RuntimeException {}

    private static int score(Song song, PlaylistRequest request) {
        return 3 * (int) request.moods().stream().filter(song.moods()::contains).count()
                + 2 * (int) request.genres().stream().filter(song.genres()::contains).count()
                + (song.weather().contains(request.weather()) ? 1 : 0);
    }

    private static void validateOptions(List<String> input, List<String> allowed, int max) {
        if (input.size() > max || new HashSet<>(input).size() != input.size() || !allowed.containsAll(input)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
}
