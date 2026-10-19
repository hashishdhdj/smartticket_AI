package com.smartticket.vo;

import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

/**
 * 分页出参。
 *
 * <p>为什么不直接返回 MyBatis-Plus 的 {@code Page}：它会把 {@code orders}、
 * {@code optimizeCountSql}、{@code searchCount} 等内部字段一并序列化给前端，
 * 既污染契约也可能随框架升级变化。这里只暴露前端真正需要的 5 个字段。</p>
 *
 * @param <T> 记录类型
 */
@Getter
@ToString
public class PageVO<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private final List<T> records;

    /** 总记录数 */
    private final long total;

    /** 当前页码，从 1 开始 */
    private final long current;

    /** 每页条数 */
    private final long size;

    /** 总页数（由 total 与 size 推导，避免前端各算各的） */
    private final long pages;

    private PageVO(List<T> records, long total, long current, long size) {
        this.records = records == null ? List.of() : List.copyOf(records);
        this.total = total;
        this.current = current;
        this.size = size;
        this.pages = size <= 0 ? 0 : (total + size - 1) / size;
    }

    public static <T> PageVO<T> of(List<T> records, long total, long current, long size) {
        return new PageVO<>(records, total, current, size);
    }

}
