/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Querqy for OpenSearch Contributors
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

package querqy.opensearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchException;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexService;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.indices.IndicesService;
import org.opensearch.transport.client.Client;
import querqy.opensearch.RewriterShardContext.ShardIndependentRewriteChain;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class RewriterShardContexts implements IndexEventListener {

    private static final Logger LOGGER = LogManager.getLogger(RewriterShardContexts.class);


    private final Map<ShardId, RewriterShardContext> shardContexts;

    private IndicesService indicesService;
    private Settings settings;

    /**
     * A context that is not bound to a shard. It serves requests that run on the coordinating node, like the
     * rewrite debug output.
     */
    private volatile RewriterShardContext shardIndependentContext;

    public RewriterShardContexts(final Settings settings) {
        this.settings = settings;
        shardContexts = new ConcurrentHashMap<>();
    }

    public RewriteChainAndLogging getRewriteChain(final List<String> rewriterIds,
                                                  final QueryShardContext context) {

        final ShardId shardId = new ShardId(context.getFullyQualifiedIndex(), context.getShardId());
        RewriterShardContext shardContext = shardContexts.get(shardId);

        if (shardContext == null) {
            shardContext = loadShardContext(shardId, context);
        }

        return shardContext.getRewriteChain(rewriterIds);
    }

    public ShardIndependentRewriteChain getShardIndependentRewriteChain(final List<String> rewriterIds,
                                                                       final Client client) {
        RewriterShardContext context = shardIndependentContext;
        if (context == null) {
            context = loadShardIndependentContext(client);
        }
        return context.getShardIndependentRewriteChain(rewriterIds);
    }

    private synchronized RewriterShardContext loadShardIndependentContext(final Client client) {
        if (shardIndependentContext == null) {
            shardIndependentContext = new RewriterShardContext("shard-independent rewriters", () -> null,
                    settings, client);
        }
        return shardIndependentContext;
    }

    protected synchronized RewriterShardContext loadShardContext(final ShardId shardId,
                                                                 final QueryShardContext context) {
        RewriterShardContext shardContext = shardContexts.get(shardId);

        if (shardContext == null) {
            final IndexService indexService = indicesService.indexService(shardId.getIndex());
            shardContext = new RewriterShardContext("shard " + shardId + " " + shardId.getIndex(),
                    () -> indexService.getShard(shardId.id()), settings, context.getClient());
            shardContexts.put(shardId, shardContext);
        }

        return shardContext;
    }

    public synchronized void reloadRewriter(final String rewriterId) {
        allContexts().forEach(ctx -> {
            try {
                ctx.reloadRewriter(rewriterId);
            } catch (final Exception e) {
                LOGGER.error("Error reloading rewriter " + rewriterId, e);
                throw new OpenSearchException("Could not reload rewriter " + rewriterId, e);
            }
        });
    }

    public void clearRewriter(final String rewriterId) {
        allContexts().forEach(ctx -> ctx.clearRewriter(rewriterId));
    }

    public void clearRewriters() {
        allContexts().forEach(RewriterShardContext::clearRewriters);
    }

    private Stream<RewriterShardContext> allContexts() {
        return Stream.concat(shardContexts.values().stream(), Stream.ofNullable(shardIndependentContext));
    }

    @Override
    public synchronized void shardRoutingChanged(final IndexShard indexShard, final ShardRouting oldRouting,
                                                 final ShardRouting newRouting) {
        shardContexts.remove(indexShard.shardId());
    }

    @Override
    public synchronized void afterIndexShardClosed(final ShardId shardId, final IndexShard indexShard, final Settings indexSettings) {
        shardContexts.remove(shardId);
    }

    @Inject
    public void setIndicesService(final IndicesService indicesService) {
        this.indicesService = indicesService;
    }

}