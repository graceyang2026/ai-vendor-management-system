package com.srm.core.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应包装（docs/api-spec.md 第 0 节），
 * 经全局 SNAKE_CASE 序列化后对外字段为 list/total/page/page_size。
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private long total;
    private int page;
    private int pageSize;
}
