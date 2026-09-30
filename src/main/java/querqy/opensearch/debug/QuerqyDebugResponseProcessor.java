/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Querqy for OpenSearch Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package querqy.opensearch.debug;

import org.apache.lucene.search.BooleanClause;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilderVisitor;
import org.opensearch.search.SearchExtBuilder;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.PipelineProcessingContext;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchResponseProcessor;
import org.opensearch.search.profile.SearchProfileShardResults;
import org.opensearch.transport.client.Client;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.QuerqyProcessor.RewriteDebugInfo;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.rewrite.logging.ActionLog;
import querqy.rewrite.logging.InstructionLog;
import querqy.rewrite.logging.MatchLog;
import querqy.rewrite.logging.RewriteChainLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p>A search response processor that adds the Querqy rewrite log to the <code>ext</code> section of the response,
 * similar to the Querqy debug output in Solr.</p>
 *
 * <p>The rewriting happens on the shards, which cannot return it with the response. This processor therefore runs
 * the rewrite chain of every <code>querqy</code> query in the request again on the coordinating node. Rewriters
 * that need a shard (word break) cannot run there. They are listed as skipped.</p>
 */
public class QuerqyDebugResponseProcessor extends AbstractProcessor implements SearchResponseProcessor {

    public static final String TYPE = "querqy_debug";

    private final QuerqyProcessor querqyProcessor;
    private final Client client;
    private final Consumer<Runnable> executor;

    public QuerqyDebugResponseProcessor(final String tag, final String description, final boolean ignoreFailure,
                                        final QuerqyProcessor querqyProcessor, final Client client,
                                        final Consumer<Runnable> executor) {
        super(tag, description, ignoreFailure);
        this.querqyProcessor = querqyProcessor;
        this.client = client;
        this.executor = executor;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SearchResponse processResponse(final SearchRequest request, final SearchResponse response) {
        final List<QuerqyQueryBuilder> querqyQueries = findQuerqyQueries(request);
        return querqyQueries.isEmpty() ? response : addDebugInfo(response, querqyQueries);
    }

    @Override
    public void processResponseAsync(final SearchRequest request, final SearchResponse response,
                                     final PipelineProcessingContext requestContext,
                                     final ActionListener<SearchResponse> responseListener) {
        final List<QuerqyQueryBuilder> querqyQueries = findQuerqyQueries(request);
        if (querqyQueries.isEmpty()) {
            responseListener.onResponse(response);
            return;
        }
        // Loading the rewriters blocks, which must not happen on a transport thread
        executor.accept(() -> {
            try {
                responseListener.onResponse(addDebugInfo(response, querqyQueries));
            } catch (final Exception e) {
                responseListener.onFailure(e);
            }
        });
    }

    static List<QuerqyQueryBuilder> findQuerqyQueries(final SearchRequest request) {
        if (request.source() == null || request.source().query() == null) {
            return Collections.emptyList();
        }
        final List<QuerqyQueryBuilder> querqyQueries = new ArrayList<>();
        request.source().query().visit(new QueryBuilderVisitor() {
            @Override
            public void accept(final QueryBuilder qb) {
                if (qb instanceof QuerqyQueryBuilder querqyQueryBuilder) {
                    querqyQueries.add(querqyQueryBuilder);
                }
            }

            @Override
            public QueryBuilderVisitor getChildVisitor(final BooleanClause.Occur occur) {
                return this;
            }
        });
        return querqyQueries;
    }

    SearchResponse addDebugInfo(final SearchResponse response, final List<QuerqyQueryBuilder> querqyQueries) {

        final List<Map<String, Object>> queries = new ArrayList<>(querqyQueries.size());
        for (final QuerqyQueryBuilder querqyQuery : querqyQueries) {
            queries.add(toDebugInfo(querqyQuery, querqyProcessor.rewriteForDebugging(querqyQuery, client)));
        }

        final SearchResponseSections sections = response.getInternalResponse();
        final List<SearchExtBuilder> extBuilders = new ArrayList<>(sections.getSearchExtBuilders());
        extBuilders.add(new QuerqyDebugSearchExt(Map.of("queries", queries)));

        final Map<String, ?> profile = sections.profile();
        final SearchResponseSections newSections = new SearchResponseSections(
                sections.hits(),
                sections.aggregations(),
                sections.suggest(),
                sections.timedOut(),
                sections.terminatedEarly(),
                profile == null ? null : new SearchProfileShardResults(sections.profile()),
                sections.getNumReducePhases(),
                extBuilders,
                sections.getProcessorResult());

        return new SearchResponse(
                newSections,
                response.getScrollId(),
                response.getTotalShards(),
                response.getSuccessfulShards(),
                response.getSkippedShards(),
                response.getTook().millis(),
                response.getPhaseTook(),
                response.getShardFailures(),
                response.getClusters(),
                response.pointInTimeId());
    }

    static Map<String, Object> toDebugInfo(final QuerqyQueryBuilder querqyQuery, final RewriteDebugInfo rewriteInfo) {
        final Map<String, Object> info = new LinkedHashMap<>();
        info.put("query", querqyQuery.getMatchingQuery().getQueryString());

        final List<Rewriter> rewriters = querqyQuery.getRewriters();
        info.put("rewriters", rewriters == null
                ? Collections.emptyList()
                : rewriters.stream().map(Rewriter::getName).toList());

        final List<Map<String, Object>> chain = new ArrayList<>();
        final RewriteChainLog log = rewriteInfo.rewriteChainLog();
        if (log != null) {
            for (final RewriteChainLog.RewriteLogEntry entry : log.getRewriteChain()) {
                final Map<String, Object> entryInfo = new LinkedHashMap<>();
                entryInfo.put("rewriter_id", entry.getRewriterId());
                final List<ActionLog> actions = entry.getActions();
                entryInfo.put("actions", actions == null
                        ? Collections.emptyList()
                        : actions.stream().map(QuerqyDebugResponseProcessor::toDebugInfo).toList());
                chain.add(entryInfo);
            }
        }
        info.put("rewrite_chain", chain);

        if (!rewriteInfo.skippedRewriterIds().isEmpty()) {
            info.put("skipped_rewriters", rewriteInfo.skippedRewriterIds());
        }
        return info;
    }

    static Map<String, Object> toDebugInfo(final ActionLog action) {
        final Map<String, Object> info = new LinkedHashMap<>();
        putIfNotEmpty(info, "message", action.getMessage());

        final MatchLog match = action.getMatch();
        if (match != null) {
            final Map<String, Object> matchInfo = new LinkedHashMap<>();
            putIfNotEmpty(matchInfo, "term", match.getTerm());
            putIfNotEmpty(matchInfo, "type", match.getType());
            info.put("match", matchInfo);
        }

        final List<InstructionLog> instructions = action.getInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            info.put("instructions", instructions.stream().map(instruction -> {
                final Map<String, Object> instructionInfo = new LinkedHashMap<>();
                putIfNotEmpty(instructionInfo, "type", instruction.getType());
                if (instruction.getParam() != null) {
                    instructionInfo.put("param", String.valueOf(instruction.getParam()));
                }
                putIfNotEmpty(instructionInfo, "value", instruction.getValue());
                return instructionInfo;
            }).toList());
        }
        return info;
    }

    private static void putIfNotEmpty(final Map<String, Object> map, final String key, final String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    public static class Factory implements Processor.Factory<SearchResponseProcessor> {

        private final QuerqyProcessor querqyProcessor;
        private final Client client;
        private final Consumer<Runnable> executor;

        public Factory(final QuerqyProcessor querqyProcessor, final Client client, final Consumer<Runnable> executor) {
            this.querqyProcessor = querqyProcessor;
            this.client = client;
            this.executor = executor;
        }

        @Override
        public SearchResponseProcessor create(final Map<String, Processor.Factory<SearchResponseProcessor>> factories,
                                              final String tag, final String description,
                                              final boolean ignoreFailure, final Map<String, Object> config,
                                              final PipelineContext pipelineContext) {
            return new QuerqyDebugResponseProcessor(tag, description, ignoreFailure, querqyProcessor, client,
                    executor);
        }
    }
}
