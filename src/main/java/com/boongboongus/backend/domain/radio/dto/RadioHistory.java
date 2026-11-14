package com.boongboongus.backend.domain.radio.dto;

import java.util.List;

public record RadioHistory(List<RadioEpisode> items, String nextCursor, boolean hasMore) {}
