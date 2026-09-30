package cn.wangwenzhu.ireader.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档标签持久化实体。
 */
@Getter
@Setter
@Table("tag")
public class TagEntity {
    @Id(keyType = KeyType.None)
    private String id;
    private String ownerId;
    private String name;
    private String normalizedName;
}
