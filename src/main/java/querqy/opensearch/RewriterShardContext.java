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
import org.opensearch.ResourceNotFoundException;
import org.opensearch.action.get.GetResponse;
import org.opensearch.common.cache.Cache;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.InvalidTypeNameException;
import org.opensearch.transport.client.Client;
import querqy.opensearch.rewriterstore.LoadRewriterConfig;
import querqy.opensearch.rewriterstore.RewriterConfigMapping;
import querqy.rewrite.RewriteChain;
import querqy.rewrite.RewriterFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static querqy.opensearch.rewriterstore.Constants.QUERQY_INDEX_NAME;

public class RewriterShardContext {


    public static final Setting<TimeValue> CACHE_EXPIRE_AFTER_WRITE = Setting.timeSetting(
            "querqy.caches.rewriter.expire_after_write",
            TimeValue.timeValueNanos(0), // do not expire by default
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    public static final Setting<TimeValue> CACHE_EXPIRE_AFTER_READ = Setting.timeSetting(
            "querqy.caches.rewriter.expire_after_read",
            TimeValue.timeValueNanos(0), // do not expire by default
            TimeValue.timeValueNanos(0),
            Setting.Property.NodeScope);

    private static final Logger LOGGER = LogManager.getLogger(RewriterShardContext.class);

    final Cache<String, RewriterFactoryAndLogging> factories;
    final Client client;
    final Supplier<IndexShard> indexShardSupplier;

    /**
     * @param name A name for this context, used for logging
     * @param indexShardSupplier Supplies the shard to create rewriter factories for. It returns null for a context
     *                           that lives outside of a shard, for example on the coordinating node.
     */
    public RewriterShardContext(final String name, final Supplier<IndexShard> indexShardSupplier,
                                final Settings settings, final Client client) {
        this.indexShardSupplier = indexShardSupplier;
        this.client = client;
        factories = Caches.buildCache(CACHE_EXPIRE_AFTER_READ.get(settings), CACHE_EXPIRE_AFTER_WRITE.get(settings));
        LOGGER.info("Context loaded for {}", name);
    }

    public RewriteChainAndLogging getRewriteChain(final List<String> rewriterIds) {
        final List<RewriterFactory> rewriterFactories = new ArrayList<>(rewriterIds.size());
        final Set<String> loggingEnabledRewriters = new HashSet<>();

        for (final String id : rewriterIds) {

            final RewriterFactoryAndLogging factoryAndLogging = getFactory(id);
            rewriterFactories.add(factoryAndLogging.rewriterFactory);
            if (factoryAndLogging.loggingEnabled) {
                loggingEnabledRewriters.add(id);
            }

        }

        return new RewriteChainAndLogging(new RewriteChain(rewriterFactories), loggingEnabledRewriters);
    }

    /**
     * Gets a rewrite chain that can run without a shard. Rewriters that need a shard are left out of the chain.
     */
    public ShardIndependentRewriteChain getShardIndependentRewriteChain(final List<String> rewriterIds) {
        final List<RewriterFactory> rewriterFactories = new ArrayList<>(rewriterIds.size());
        final List<String> skippedRewriterIds = new ArrayList<>();

        for (final String id : rewriterIds) {
            final RewriterFactoryAndLogging factoryAndLogging = getFactory(id);
            if (factoryAndLogging.requiresShard) {
                skippedRewriterIds.add(id);
            } else {
                rewriterFactories.add(factoryAndLogging.rewriterFactory);
            }
        }

        return new ShardIndependentRewriteChain(new RewriteChain(rewriterFactories), skippedRewriterIds);
    }

    private RewriterFactoryAndLogging getFactory(final String rewriterId) {
        final RewriterFactoryAndLogging factoryAndLogging = factories.get(rewriterId);
        return factoryAndLogging != null ? factoryAndLogging : loadFactory(rewriterId, false);
    }

    public void clearRewriter(final String rewriterId) {
        factories.invalidate(rewriterId);
    }

    public void clearRewriters() {
        factories.invalidateAll();
    }

    public void reloadRewriter(final String rewriterId) {
        if (factories.get(rewriterId) != null) {
            loadFactory(rewriterId, true);
        }
    }

    public synchronized RewriterFactoryAndLogging loadFactory(final String rewriterId, final boolean forceLoad) {

        RewriterFactoryAndLogging factoryAndLogging = factories.get(rewriterId);

        if (forceLoad || (factoryAndLogging == null)) {

            final GetResponse response;

            try {
                response = client.prepareGet(QUERQY_INDEX_NAME, rewriterId).execute().get();
            } catch (InterruptedException | ExecutionException e) {
                throw new OpenSearchException("Could not load rewriter " + rewriterId, e);
            }

            final Map<String, Object> source = response.getSource();

            if (source == null) {
                throw new ResourceNotFoundException("Rewriter not found: " + rewriterId);
            }

            if (!"rewriter".equals(source.get(RewriterConfigMapping.PROP_TYPE))) {
                throw new InvalidTypeNameException("Not a rewriter: " + rewriterId);
            }

            final LoadRewriterConfig loadConfig = new LoadRewriterConfig(rewriterId, source);

            final Map<String, Object> infoLogging = loadConfig.getInfoLoggingConfig();
            final boolean loggingEnabled;
            if (infoLogging != null) {
                final Object sinksObj = infoLogging.get("sinks");
                if (sinksObj instanceof String) {
                    loggingEnabled = "log4j".equals(sinksObj);
                } else if (sinksObj instanceof final Collection<?> sinksCollection) {
                    loggingEnabled = (!sinksCollection.isEmpty()) && sinksCollection.contains("log4j");
                } else {
                    loggingEnabled = false;
                }
            } else {
                loggingEnabled = false;
            }

            final OpenSearchRewriterFactory openSearchRewriterFactory =
                    OpenSearchRewriterFactory.loadConfiguredInstance(loadConfig);
            final RewriterFactory factory = openSearchRewriterFactory.createRewriterFactory(indexShardSupplier.get());
            factoryAndLogging = new RewriterFactoryAndLogging(factory, loggingEnabled,
                    openSearchRewriterFactory.requiresShard());
            factories.put(rewriterId, factoryAndLogging);


        }

        return factoryAndLogging;

    }


    public static class RewriterFactoryAndLogging {
        public final RewriterFactory rewriterFactory;
        public final boolean loggingEnabled;
        public final boolean requiresShard;

        public RewriterFactoryAndLogging(final RewriterFactory rewriterFactory, final boolean loggingEnabled,
                                         final boolean requiresShard) {
            this.rewriterFactory = rewriterFactory;
            this.loggingEnabled = loggingEnabled;
            this.requiresShard = requiresShard;
        }
    }

    /**
     * @param rewriteChain The chain of the rewriters that can run without a shard
     * @param skippedRewriterIds The ids of the requested rewriters that were left out because they need a shard
     */
    public record ShardIndependentRewriteChain(RewriteChain rewriteChain, List<String> skippedRewriterIds) {}
}