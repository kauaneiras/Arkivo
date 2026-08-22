package com.labdms.application.dto.response;

import java.util.List;

/**
 * Generic paginated response wrapper.
 *
 * @param <T> the type of content in the page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> of(List<T> content, int page, int size,
                                          long totalElements, int totalPages) {
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1
        );
    }
}
