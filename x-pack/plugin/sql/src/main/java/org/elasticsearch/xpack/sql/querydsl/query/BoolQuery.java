/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.querydsl.query;

import java.util.Objects;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.sort.NestedSortBuilder;
import org.elasticsearch.xpack.sql.tree.Location;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;

/**
 * Query representing boolean AND or boolean OR.
 */
public class BoolQuery extends Query {
    /**
     * {@code true} for boolean {@code AND}, {@code false} for boolean {@code OR}.
     */
    private final boolean isAnd;
    private final Query left;
    private final Query right;

    public BoolQuery(Location location, boolean isAnd, Query left, Query right) {
        super(location);
        if (left == null) {
            throw new IllegalArgumentException("left is required");
        }
        if (right == null) {
            throw new IllegalArgumentException("right is required");
        }
        this.isAnd = isAnd;
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean containsNestedField(String path, String field) {
        return left.containsNestedField(path, field) || right.containsNestedField(path, field);
    }

    @Override
    public Query addNestedField(String path, String field, boolean hasDocValues) {
        Query rewrittenLeft = left.addNestedField(path, field, hasDocValues);
        Query rewrittenRight = right.addNestedField(path, field, hasDocValues);
        if (rewrittenLeft == left && rewrittenRight == right) {
            return this;
        }
        return new BoolQuery(location(), isAnd, rewrittenLeft, rewrittenRight);
    }

    @Override
    public void enrichNestedSort(NestedSortBuilder sort) {
        left.enrichNestedSort(sort);
        right.enrichNestedSort(sort);
    }

    @Override
    public QueryBuilder asBuilder() {
        BoolQueryBuilder boolQuery = boolQuery();
        if (isAnd) {
            // TODO are we throwing out score by using filter?
            boolQuery.filter(left.asBuilder());
            boolQuery.filter(right.asBuilder());
        } else {
            boolQuery.should(left.asBuilder());
            boolQuery.should(right.asBuilder());
        }
        return boolQuery;
    }

    boolean isAnd() {
        return isAnd;
    }

    Query left() {
        return left;
    }

    Query right() {
        return right;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), isAnd, left, right);
    }

    @Override
    public boolean equals(Object obj) {
        if (false == super.equals(obj)) {
            return false;
        }
        BoolQuery other = (BoolQuery) obj;
        return isAnd == other.isAnd
            && left.equals(other.left)
            && right.equals(other.right);
    }

    @Override
    protected String innerToString() {
        return left + (isAnd ? " AND " : " OR ") + right;
    }
}
