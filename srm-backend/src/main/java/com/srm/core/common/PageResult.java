package com.srm.core.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应包装（docs/api-spec.md 第 0 节），
 * 对外字段为 list/total/page/page_size；page_size 显式 @JsonProperty，
 * 不依赖全局 SNAKE_CASE 配置也能保证前端契约映射。
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private long total;
    private int page;

    @JsonProperty("page_size")
    private int pageSize;
}
