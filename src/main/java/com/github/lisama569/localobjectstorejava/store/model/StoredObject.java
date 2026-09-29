package com.github.lisama569.localobjectstorejava.store.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 一个对象的元数据：归属者与版本历史（新版本在列表尾部）。 */
public class StoredObject implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final String owner;
    private final List<ObjectVersion> versions = new ArrayList<>();

    public StoredObject(String key, String owner) {
        this.key = key;
        this.owner = owner;
    }

    public String key() { return key; }
    public String owner() { return owner; }
    public List<ObjectVersion> versions() { return versions; }

    /** 当前可见版本（最后一个版本；可能是删除标记）。 */
    public ObjectVersion current() {
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }
}
