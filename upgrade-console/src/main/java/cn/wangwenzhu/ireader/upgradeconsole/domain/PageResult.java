package cn.wangwenzhu.ireader.upgradeconsole.domain;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total, boolean hasNext) {
    public PageResult(List<T> items, int page, int size, long total) {
        this(items, page, size, total, (long) page * size < total);
    }
}