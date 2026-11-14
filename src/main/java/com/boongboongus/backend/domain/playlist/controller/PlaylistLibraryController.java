package com.boongboongus.backend.domain.playlist.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.playlist.dto.PlaylistLibraryResponse;
import com.boongboongus.backend.domain.playlist.service.PlaylistService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/playlists")
class PlaylistLibraryController {
    private final PlaylistService playlists;

    PlaylistLibraryController(PlaylistService playlists) {
        this.playlists = playlists;
    }

    @GetMapping
    ApiResponse<PlaylistLibraryResponse> list(Principal principal, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(playlists.library(principal.getName(), cursor, limit));
    }

    @PutMapping("/{playlistId}")
    ApiResponse<Accepted> save(Principal principal, @PathVariable String playlistId) {
        validateId(playlistId);
        playlists.bookmark(principal.getName(), playlistId);
        return ApiResponse.success(new Accepted(true));
    }

    @DeleteMapping("/{playlistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(Principal principal, @PathVariable String playlistId) {
        validateId(playlistId);
        playlists.removeBookmark(principal.getName(), playlistId);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> rejected(ResponseStatusException exception) {
        boolean missing = exception.getStatusCode() == HttpStatus.NOT_FOUND;
        return ResponseEntity.status(exception.getStatusCode()).body(ErrorResponse.of(
                missing ? "RESOURCE_NOT_FOUND" : "BAD_REQUEST",
                missing ? "플레이리스트를 찾을 수 없습니다." : "요청값을 확인해 주세요.", List.of()));
    }

    private static void validateId(String id) {
        if (id.isBlank() || id.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    record Accepted(boolean accepted) {}
}
