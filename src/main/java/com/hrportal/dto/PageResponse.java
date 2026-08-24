package com.hrportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PageResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static <T> PageResponse<T> of(Page<T> springPage) {
        PageResponse<T> response = new PageResponse<>();
        response.content = springPage.getContent();
        response.page = springPage.getNumber();
        response.size = springPage.getSize();
        response.totalElements = springPage.getTotalElements();
        response.totalPages = springPage.getTotalPages();
        return response;
    }
}
