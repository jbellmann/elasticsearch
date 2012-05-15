/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.indices.warmer.put;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.TransportSearchAction;
import org.elasticsearch.action.support.master.TransportMasterNodeOperationAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProcessedClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.BytesHolder;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.search.warmer.IndexWarmersMetaData;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Put warmer action.
 */
public class TransportPutWarmerAction extends TransportMasterNodeOperationAction<PutWarmerRequest, PutWarmerResponse> {

    private final TransportSearchAction searchAction;

    @Inject
    public TransportPutWarmerAction(Settings settings, TransportService transportService, ClusterService clusterService, ThreadPool threadPool,
                                    TransportSearchAction searchAction) {
        super(settings, transportService, clusterService, threadPool);
        this.searchAction = searchAction;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.MANAGEMENT;
    }

    @Override
    protected String transportAction() {
        return PutWarmerAction.NAME;
    }

    @Override
    protected PutWarmerRequest newRequest() {
        return new PutWarmerRequest();
    }

    @Override
    protected PutWarmerResponse newResponse() {
        return new PutWarmerResponse();
    }

    @Override
    protected ClusterBlockException checkBlock(PutWarmerRequest request, ClusterState state) {
        String[] concreteIndices = clusterService.state().metaData().concreteIndices(request.searchRequest().indices());
        return state.blocks().indicesBlockedException(ClusterBlockLevel.METADATA, concreteIndices);
    }

    @Override
    protected PutWarmerResponse masterOperation(final PutWarmerRequest request, ClusterState state) throws ElasticSearchException {

        // first execute the search request, see that its ok...
        SearchResponse searchResponse = searchAction.execute(request.searchRequest()).actionGet();
        // check no shards errors
        //TODO: better failure to raise...
        if (searchResponse.failedShards() > 0) {
            throw new ElasticSearchException("search failed with failed shards: " + Arrays.toString(searchResponse.shardFailures()));
        }

        // all is well, continue to the cluster service

        final AtomicReference<Throwable> failureRef = new AtomicReference<Throwable>();
        final CountDownLatch latch = new CountDownLatch(1);

        clusterService.submitStateUpdateTask("put_warmer [" + request.name() + "]", new ProcessedClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) {
                MetaData metaData = currentState.metaData();
                String[] concreteIndices = metaData.concreteIndices(request.searchRequest().indices());


                BytesHolder source = null;
                if (request.searchRequest().source() != null && request.searchRequest().source().length > 0) {
                    source = new BytesHolder(request.searchRequest().source(), request.searchRequest().sourceOffset(), request.searchRequest().sourceLength());
                } else if (request.searchRequest().extraSource() != null && request.searchRequest().extraSource().length > 0) {
                    source = new BytesHolder(request.searchRequest().extraSource(), request.searchRequest().extraSourceOffset(), request.searchRequest().extraSourceLength());
                }

                // now replace it on the metadata
                MetaData.Builder mdBuilder = MetaData.builder().metaData(currentState.metaData());

                for (String index : concreteIndices) {
                    IndexMetaData indexMetaData = metaData.index(index);
                    if (indexMetaData == null) {
                        throw new IndexMissingException(new Index(index));
                    }
                    IndexWarmersMetaData warmers = indexMetaData.custom(IndexWarmersMetaData.TYPE);
                    if (warmers == null) {
                        logger.info("[{}] putting warmer [{}]", index, request.name());
                        warmers = new IndexWarmersMetaData(new IndexWarmersMetaData.Entry(request.name(), request.searchRequest().types(), source));
                    } else {
                        boolean found = false;
                        List<IndexWarmersMetaData.Entry> entries = new ArrayList<IndexWarmersMetaData.Entry>(warmers.entries().size() + 1);
                        for (IndexWarmersMetaData.Entry entry : warmers.entries()) {
                            if (entry.name().equals(request.name())) {
                                found = true;
                                entries.add(new IndexWarmersMetaData.Entry(request.name(), request.searchRequest().types(), source));
                            } else {
                                entries.add(entry);
                            }
                        }
                        if (!found) {
                            logger.info("[{}] put warmer [{}]", index, request.name());
                            entries.add(new IndexWarmersMetaData.Entry(request.name(), request.searchRequest().types(), source));
                        } else {
                            logger.info("[{}] update warmer [{}]", index, request.name());
                        }
                        warmers = new IndexWarmersMetaData(entries.toArray(new IndexWarmersMetaData.Entry[entries.size()]));
                    }
                    IndexMetaData.Builder indexBuilder = IndexMetaData.newIndexMetaDataBuilder(indexMetaData).putCustom(IndexWarmersMetaData.TYPE, warmers);
                    mdBuilder.put(indexBuilder);
                }

                return ClusterState.builder().state(currentState).metaData(mdBuilder).build();
            }

            @Override
            public void clusterStateProcessed(ClusterState clusterState) {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            failureRef.set(e);
        }

        if (failureRef.get() != null) {
            if (failureRef.get() instanceof ElasticSearchException) {
                throw (ElasticSearchException) failureRef.get();
            } else {
                throw new ElasticSearchException(failureRef.get().getMessage(), failureRef.get());
            }
        }

        return new PutWarmerResponse(true);
    }
}