package com.boongboongus.backend.domain.drive;

import java.util.List;
import java.util.Optional;

/** Finds an unexpired route the authenticated user can attach to their current couple's drive. */
public interface DriveRouteLookup {
    Optional<Route> findAccessible(String userId, String coupleId, String routeId);

    default Optional<LiveRoute> findLiveForDrive(String userId, String coupleId, String routeId) {
        return Optional.empty();
    }

    default int estimatePlaylistDuration(Place origin, Place destination) {
        throw new LiveRouteUnavailable();
    }

    final class LiveRouteUnavailable extends RuntimeException {
    }

    record LiveRoute(String routeId, String polyline, List<Road> roads) {
    }

    record Road(String name, List<Coordinate> vertices) {
    }

    record Route(Place origin, Place destination) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    record Place(String placeId, String name, String address, Coordinate coordinate) {
    }

    record Coordinate(double latitude, double longitude) {
    }
}
