package com.videopilot.media.application;

import java.util.List;

public record MediaAssetPage(
        List<MediaAssetView> data,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
