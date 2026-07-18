package com.sjherp.domain.memory;

import java.util.List;

/** 派生向量索引端口；删除仅作用于可重建索引，不删除业务真源。 */
public interface VectorIndex {

    void ensureCollection(VectorCollectionSpec spec);

    void upsert(VectorPoint point);

    void delete(long memoryEntryId);

    List<VectorMatch> search(VectorQuery query);
}
