/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.session;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.sql.SqlIllegalArgumentException;
import org.elasticsearch.xpack.sql.analysis.analyzer.Analyzer;
import org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer;
import org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer.PreAnalysis;
import org.elasticsearch.xpack.sql.analysis.catalog.Catalog;
import org.elasticsearch.xpack.sql.analysis.catalog.IndexResolver;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.FunctionRegistry;
import org.elasticsearch.xpack.sql.optimizer.Optimizer;
import org.elasticsearch.xpack.sql.parser.SqlParser;
import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.sql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.sql.planner.Planner;
import org.elasticsearch.xpack.sql.rule.RuleExecutor;

import java.util.function.Function;

import static org.elasticsearch.action.ActionListener.wrap;

public class SqlSession {

    private final Client client;

    private final SqlParser parser;
    private final FunctionRegistry functionRegistry;
    private final IndexResolver indexResolver;
    private final PreAnalyzer preAnalyzer;
    private final Analyzer analyzer;
    private final Optimizer optimizer;
    private final Planner planner;

    private Configuration settings;

    public static class SessionContext {
        
        public final Configuration configuration;
        public final Catalog catalog;

        SessionContext(Configuration configuration, Catalog catalog) {
            this.configuration = configuration;
            this.catalog = catalog;
        }
    }
    
    // thread-local used for sharing settings across the plan compilation
    // Currently this is used during:
    // 1. parsing - to set the TZ in date time functions (if they are used)
    // 2. analysis - to compute the Catalog and share it across the rules
    // Might be used in
    // 3. Optimization - to pass in configs around plan hints/settings
    // 4. Folding/mapping - same as above

    // TODO investigate removing
    static final ThreadLocal<SessionContext> CURRENT_CONTEXT = new ThreadLocal<SessionContext>() {
        @Override
        public String toString() {
            return "SQL SessionContext";
        }
    };

    public SqlSession(SqlSession other) {
        this(other.settings, other.client, other.functionRegistry, other.parser, other.indexResolver, 
                other.preAnalyzer, other.analyzer, other.optimizer,other.planner);
    }

    public SqlSession(Configuration settings, Client client, FunctionRegistry functionRegistry,
            SqlParser parser,
            IndexResolver indexResolver,
            PreAnalyzer preAnalyzer,
            Analyzer analyzer,
            Optimizer optimizer,
            Planner planner) {
        this.client = client;
        this.functionRegistry = functionRegistry;

        this.parser = parser;
        this.indexResolver = indexResolver;
        this.preAnalyzer = preAnalyzer;
        this.analyzer = analyzer;
        this.optimizer = optimizer;
        this.planner = planner;

        this.settings = settings;
    }

    public static SessionContext currentContext() {
        SessionContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            throw new SqlIllegalArgumentException("Context is accessible only during the session");
        }
        return ctx;
    }

    public FunctionRegistry functionRegistry() {
        return functionRegistry;
    }

    public Client client() {
        return client;
    }

    public Planner planner() {
        return planner;
    }

    public IndexResolver indexResolver() {
        return indexResolver;
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    public Optimizer optimizer() {
        return optimizer;
    }

    public Expression expression(String expression) {
        return parser.createExpression(expression);
    }

    private LogicalPlan doParse(String sql) {
        try {
            // NB: it's okay for the catalog to be empty - parsing only cares about the configuration
            CURRENT_CONTEXT.set(new SessionContext(settings, Catalog.EMPTY));
            return parser.createStatement(sql);
        } finally {
            CURRENT_CONTEXT.remove();
        }
    }

    public void analyzedPlan(LogicalPlan parsed, boolean verify, ActionListener<LogicalPlan> listener) {
        if (parsed.analyzed()) {
            listener.onResponse(parsed);
            return;
        }

        preAnalyze(parsed, c -> {
            try {
                CURRENT_CONTEXT.set(new SessionContext(settings, c));
                return verify ? analyzer.verify(analyzer.analyze(parsed)) : analyzer.analyze(parsed);
            } finally {
                CURRENT_CONTEXT.remove();
            }
        }, listener);
    }

    public void debugAnalyzedPlan(LogicalPlan parsed, ActionListener<RuleExecutor<LogicalPlan>.ExecutionInfo> listener) {
        if (parsed.analyzed()) {
            listener.onResponse(null);
            return;
        }

        preAnalyze(parsed, c -> {
            try {
                CURRENT_CONTEXT.set(new SessionContext(settings, c));
                return analyzer.debugAnalyze(parsed);
            } finally {
                CURRENT_CONTEXT.remove();
            }
        }, listener);
    }

    private <T> void preAnalyze(LogicalPlan parsed, Function<Catalog, T> action, ActionListener<T> listener) {
        PreAnalysis preAnalysis = preAnalyzer.preAnalyze(parsed);
        if (preAnalysis.indices.size() > 1) {
            listener.onFailure(new SqlIllegalArgumentException("Queries with multiple indices are not supported"));
            return;
        }
        //TODO why do we have a list if we only support one single element? Seems like it's the wrong data structure?
        if (preAnalysis.indices.size() == 1) {
            indexResolver.asCatalog(preAnalysis.indices.get(0),
                    wrap(c -> listener.onResponse(action.apply(c)), listener::onFailure));
        } else {
            try {
                listener.onResponse(action.apply(Catalog.EMPTY));
            } catch (Exception ex) {
                listener.onFailure(ex);
            }
        }
    }

    public void optimizedPlan(LogicalPlan verified, ActionListener<LogicalPlan> listener) {
        analyzedPlan(verified, true, wrap(v -> listener.onResponse(optimizer.optimize(v)), listener::onFailure));
    }

    public void physicalPlan(LogicalPlan optimized, boolean verify, ActionListener<PhysicalPlan> listener) {
        optimizedPlan(optimized, wrap(o -> listener.onResponse(planner.plan(o, verify)), listener::onFailure));
    }

    public void sql(String sql, ActionListener<SchemaRowSet> listener) {
        sqlExecutable(sql, wrap(e -> e.execute(this, listener), listener::onFailure));
    }

    public void sqlExecutable(String sql, ActionListener<PhysicalPlan> listener) {
        try {
            physicalPlan(doParse(sql), true, listener);
        } catch (Exception ex) {
            listener.onFailure(ex);
        }
    }

    public Configuration settings() {
        return settings;
    }
}