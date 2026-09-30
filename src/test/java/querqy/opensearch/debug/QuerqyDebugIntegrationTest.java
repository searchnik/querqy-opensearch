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

import org.opensearch.action.search.PutSearchPipelineRequest;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import org.junit.After;
import querqy.opensearch.QuerqyPlugin;
import querqy.opensearch.QuerqyProcessor;
import querqy.opensearch.query.MatchingQuery;
import querqy.opensearch.query.QuerqyQueryBuilder;
import querqy.opensearch.query.Rewriter;
import querqy.opensearch.rewriter.ReplaceRewriterFactory;
import querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory;
import querqy.opensearch.rewriter.WordBreakCompoundRewriterFactory;
import querqy.opensearch.rewriterstore.PutRewriterAction;
import querqy.opensearch.rewriterstore.PutRewriterRequest;
import querqy.rewriter.commonrules.WhiteSpaceQuerqyParserFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

public class QuerqyDebugIntegrationTest extends OpenSearchSingleNodeTestCase {

    private static final String INDEX_NAME = "test_index";
    private static final String PIPELINE = "querqy-debug";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(QuerqyPlugin.class);
    }

    @After
    public void deleteRewriterIndex() {
        try {
            client().admin().indices().prepareDelete(QUERQY_INDEX_NAME).get();
        } catch (final IndexNotFoundException e) {
            // Ignore
        }
    }

    public void testRewriteChainOfTwoRewriters() throws Exception {
        setUpIndexAndPipeline();
        putReplaceRewriter("replace1", "rr => k");
        putCommonRulesRewriter("common_rules", "k =>\nSYNONYM: c\n@_log: \"msg1\"");

        final SearchResponse response = search(querqyQuery("a rr", "replace1", "common_rules"));

        assertEquals(2L, response.getHits().getTotalHits().value());
        assertEquals(Map.of("queries", List.of(Map.of(
                        "query", "a rr",
                        "rewriters", List.of("replace1", "common_rules"),
                        "rewrite_chain", List.of(
                                Map.of("rewriter_id", "replace1", "actions", List.of(Map.of(
                                        "message", "rr => k",
                                        "match", Map.of("term", "rr", "type", "exact"),
                                        "instructions", List.of(Map.of("type", "replace", "value", "k"))))),
                                Map.of("rewriter_id", "common_rules", "actions", List.of(Map.of(
                                        "message", "msg1",
                                        "match", Map.of("term", "k", "type", "exact"),
                                        "instructions", List.of(Map.of("type", "synonym", "value", "c"))))))))),
                debugInfo(response));

        final Map<String, Object> json = XContentHelper.convertToMap(
                XContentHelper.toXContent(response, XContentType.JSON, false), false, XContentType.JSON).v2();
        assertEquals(debugInfo(response), ((Map<?, ?>) json.get("ext")).get("querqy"));
    }

    public void testRewritersThatDoNotApplyAreNotInTheChain() throws Exception {
        setUpIndexAndPipeline();
        putCommonRulesRewriter("common_rules", "k =>\nSYNONYM: c");

        final Map<String, Object> query = singleQueryDebugInfo(search(querqyQuery("a b", "common_rules")));
        assertEquals(List.of(), query.get("rewrite_chain"));
    }

    public void testQuerqyQueryNestedInBoolQuery() throws Exception {
        setUpIndexAndPipeline();
        putCommonRulesRewriter("common_rules", "k =>\nSYNONYM: c");

        final SearchResponse response = search(QueryBuilders.boolQuery()
                .should(querqyQuery("k", "common_rules"))
                .filter(QueryBuilders.matchAllQuery()));

        final Map<String, Object> query = singleQueryDebugInfo(response);
        assertEquals("k", query.get("query"));
        // without @_log, the message defaults to the rule id
        assertEquals(List.of(Map.of("rewriter_id", "common_rules", "actions", List.of(Map.of(
                        "message", "k#0",
                        "match", Map.of("term", "k", "type", "exact"),
                        "instructions", List.of(Map.of("type", "synonym", "value", "c")))))),
                query.get("rewrite_chain"));
    }

    public void testResponseWithoutQuerqyQueryIsUnchanged() throws Exception {
        setUpIndexAndPipeline();

        final SearchResponse response = search(QueryBuilders.matchAllQuery());
        assertEquals(2L, response.getHits().getTotalHits().value());
        assertTrue(response.getInternalResponse().getSearchExtBuilders().isEmpty());
    }

    public void testRewriterThatNeedsShardIsSkipped() throws Exception {
        setUpIndexAndPipeline();
        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest("word_break", Map.of(
                "class", WordBreakCompoundRewriterFactory.class.getName(),
                "config", Map.of("dictionaryField", "field1")))).get();
        putCommonRulesRewriter("common_rules", "k =>\nSYNONYM: c");

        final Map<String, Object> query = singleQueryDebugInfo(search(querqyQuery("k", "word_break",
                "common_rules")));

        assertEquals(List.of("word_break"), query.get("skipped_rewriters"));
        assertEquals(1, ((List<?>) query.get("rewrite_chain")).size());
    }

    public void testDebugInfoReflectsUpdatedRewriter() throws Exception {
        setUpIndexAndPipeline();
        putCommonRulesRewriter("common_rules", "k =>\nSYNONYM: c\n@_log: \"before\"");
        assertEquals("before", firstActionMessage(search(querqyQuery("k", "common_rules"))));

        putCommonRulesRewriter("common_rules", "k =>\nSYNONYM: c\n@_log: \"after\"");
        assertEquals("after", firstActionMessage(search(querqyQuery("k", "common_rules"))));
    }

    private Object firstActionMessage(final SearchResponse response) {
        final List<?> chain = (List<?>) singleQueryDebugInfo(response).get("rewrite_chain");
        final List<?> actions = (List<?>) ((Map<?, ?>) chain.get(0)).get("actions");
        return ((Map<?, ?>) actions.get(0)).get("message");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> singleQueryDebugInfo(final SearchResponse response) {
        final List<Map<String, Object>> queries = (List<Map<String, Object>>) debugInfo(response).get("queries");
        assertEquals(1, queries.size());
        return queries.get(0);
    }

    private static Map<String, Object> debugInfo(final SearchResponse response) {
        return response.getInternalResponse().getSearchExtBuilders().stream()
                .filter(ext -> ext instanceof QuerqyDebugSearchExt)
                .map(ext -> ((QuerqyDebugSearchExt) ext).getDebugInfo())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Querqy debug info in response"));
    }

    private QuerqyQueryBuilder querqyQuery(final String queryString, final String... rewriterIds) {
        final QuerqyQueryBuilder querqyQuery = new QuerqyQueryBuilder(getInstanceFromNode(QuerqyProcessor.class));
        querqyQuery.setRewriters(Arrays.stream(rewriterIds).map(Rewriter::new).toList());
        querqyQuery.setMatchingQuery(new MatchingQuery(queryString));
        querqyQuery.setQueryFieldsAndBoostings(Arrays.asList("field1", "field2"));
        querqyQuery.setMinimumShouldMatch("1");
        return querqyQuery;
    }

    private SearchResponse search(final org.opensearch.index.query.QueryBuilder query) throws Exception {
        final SearchRequestBuilder searchRequestBuilder = client().prepareSearch(INDEX_NAME);
        searchRequestBuilder.setQuery(query);
        return client().search(searchRequestBuilder.request().pipeline(PIPELINE)).get();
    }

    private void putCommonRulesRewriter(final String id, final String rules) throws Exception {
        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest(id, Map.of(
                "class", SimpleCommonRulesRewriterFactory.class.getName(),
                "config", Map.of(
                        "rules", rules,
                        "ignoreCase", true,
                        "querqyParser", WhiteSpaceQuerqyParserFactory.class.getName())))).get();
    }

    private void putReplaceRewriter(final String id, final String rules) throws Exception {
        client().execute(PutRewriterAction.INSTANCE, new PutRewriterRequest(id, Map.of(
                "class", ReplaceRewriterFactory.class.getName(),
                "config", Map.of("rules", rules)))).get();
    }

    private void setUpIndexAndPipeline() throws Exception {
        client().admin().indices().prepareCreate(INDEX_NAME).get();
        client().prepareIndex(INDEX_NAME)
                .setSource("field1", "a b", "field2", "a c")
                .get();
        client().prepareIndex(INDEX_NAME)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field1", "b c")
                .get();
        client().admin().cluster().putSearchPipeline(new PutSearchPipelineRequest(PIPELINE,
                new BytesArray("{\"response_processors\":[{\"" + QuerqyDebugResponseProcessor.TYPE + "\":{}}]}"),
                MediaTypeRegistry.JSON)).get();
    }
}
