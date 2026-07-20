package com.example.interviewreader.exportpkg;

import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.entity.DocumentTagEntity;
import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.example.interviewreader.persistence.entity.TagEntity;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentTagMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.TagMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPackageExportServiceTests {
    @Test
    void tagLoadingUsesTwoQueriesRegardlessOfTagCount() {
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var document = new DocumentEntity();
        document.setCode("java");
        document.setTitle("Java 面试题");
        var version = new DocumentVersionEntity();
        version.setVersionNo(1);
        version.setSourceType("PDF");
        version.setSchemaVersion("1.0");
        version.setLanguage("zh-CN");
        version.setMetadata("{}");
        var linkQueries = new AtomicInteger();
        var tagQueries = new AtomicInteger();

        var service = new DocumentPackageExportService(
                mapper(DocumentMapper.class, method -> document),
                mapper(DocumentVersionMapper.class, method -> version),
                mapper(ContentNodeMapper.class, method -> List.of()),
                mapper(ContentBlockMapper.class, method -> List.of()),
                mapper(AssetMapper.class, method -> List.of()),
                mapper(DocumentTagMapper.class, method -> {
                    linkQueries.incrementAndGet();
                    return List.of(link("tag-1"), link("tag-2"), link("tag-3"));
                }),
                mapper(TagMapper.class, method -> {
                    tagQueries.incrementAndGet();
                    return List.of(tag("tag-1", "并发"), tag("tag-2", "JVM"), tag("tag-3", "集合"));
                }),
                new ObjectMapper());

        var exported = service.exportJsonPackage(documentId, versionId);

        assertThat(exported.document().tags()).containsExactly("JVM", "并发", "集合");
        assertThat(linkQueries).hasValue(1);
        assertThat(tagQueries).hasValue(1);
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, Function<String, Object> invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "TestProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    return invocation.apply(method.getName());
                });
    }

    private DocumentTagEntity link(String tagId) {
        var link = new DocumentTagEntity();
        link.setTagId(tagId);
        return link;
    }

    private TagEntity tag(String id, String name) {
        var tag = new TagEntity();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }
}
