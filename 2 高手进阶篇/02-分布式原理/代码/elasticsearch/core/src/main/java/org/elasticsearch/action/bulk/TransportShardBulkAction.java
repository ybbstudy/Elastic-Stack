/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.action.bulk;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.replication.ReplicationOperation;
import org.elasticsearch.action.support.TransportActions;
import org.elasticsearch.action.support.replication.ReplicationResponse.ShardInfo;
import org.elasticsearch.action.support.replication.TransportWriteAction;
import org.elasticsearch.action.update.UpdateHelper;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.Mapping;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.seqno.SequenceNumbersService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Performs shard-level bulk (index, delete or update) operations */
public class TransportShardBulkAction extends TransportWriteAction<BulkShardRequest, BulkShardRequest, BulkShardResponse> {

    public static final String ACTION_NAME = BulkAction.NAME + "[s]";

    private static final Logger logger = ESLoggerFactory.getLogger(TransportShardBulkAction.class);

    private final UpdateHelper updateHelper;
    private final MappingUpdatedAction mappingUpdatedAction;

    @Inject
    public TransportShardBulkAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                    IndicesService indicesService, ThreadPool threadPool, ShardStateAction shardStateAction,
                                    MappingUpdatedAction mappingUpdatedAction, UpdateHelper updateHelper, ActionFilters actionFilters,
                                    IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ACTION_NAME, transportService, clusterService, indicesService, threadPool, shardStateAction, actionFilters,
            indexNameExpressionResolver, BulkShardRequest::new, BulkShardRequest::new, ThreadPool.Names.BULK);
        this.updateHelper = updateHelper;
        this.mappingUpdatedAction = mappingUpdatedAction;
    }

    @Override
    protected TransportRequestOptions transportOptions() {
        return BulkAction.INSTANCE.transportOptions(settings);
    }

    @Override
    protected BulkShardResponse newResponseInstance() {
        return new BulkShardResponse();
    }

    @Override
    protected boolean resolveIndex() {
        return false;
    }

    @Override
    public WritePrimaryResult<BulkShardRequest, BulkShardResponse> shardOperationOnPrimary(
            BulkShardRequest request, IndexShard primary) throws Exception {
        final IndexMetaData metaData = primary.indexSettings().getIndexMetaData();
        Translog.Location location = null;
        final MappingUpdatePerformer mappingUpdater = new ConcreteMappingUpdatePerformer();
        for (int requestIndex = 0; requestIndex < request.items().length; requestIndex++) {
            location = executeBulkItemRequest(metaData, primary, request, location, requestIndex,
                    updateHelper, threadPool::absoluteTimeInMillis, mappingUpdater);
        }
        BulkItemResponse[] responses = new BulkItemResponse[request.items().length];
        BulkItemRequest[] items = request.items();
        for (int i = 0; i < items.length; i++) {
            responses[i] = items[i].getPrimaryResponse();
        }
        BulkShardResponse response = new BulkShardResponse(request.shardId(), responses);
        return new WritePrimaryResult<>(request, response, location, null, primary, logger);
    }


    private static BulkItemResultHolder executeIndexRequest(final IndexRequest indexRequest,
                                                            final BulkItemRequest bulkItemRequest,
                                                            final IndexShard primary,
                                                            final MappingUpdatePerformer mappingUpdater) throws Exception {
        Engine.IndexResult indexResult = executeIndexRequestOnPrimary(indexRequest, primary, mappingUpdater);
        if (indexResult.hasFailure()) {
            return new BulkItemResultHolder(null, indexResult, bulkItemRequest);
        } else {
            IndexResponse response = new IndexResponse(primary.shardId(), indexRequest.type(), indexRequest.id(),
                    indexResult.getSeqNo(), indexResult.getVersion(), indexResult.isCreated());
            return new BulkItemResultHolder(response, indexResult, bulkItemRequest);
        }
    }

    private static BulkItemResultHolder executeDeleteRequest(final DeleteRequest deleteRequest,
                                                             final BulkItemRequest bulkItemRequest,
                                                             final IndexShard primary) throws IOException {
        Engine.DeleteResult deleteResult = executeDeleteRequestOnPrimary(deleteRequest, primary);
        if (deleteResult.hasFailure()) {
            return new BulkItemResultHolder(null, deleteResult, bulkItemRequest);
        } else {
            DeleteResponse response = new DeleteResponse(primary.shardId(), deleteRequest.type(), deleteRequest.id(),
                    deleteResult.getSeqNo(), deleteResult.getVersion(), deleteResult.isFound());
            return new BulkItemResultHolder(response, deleteResult, bulkItemRequest);
        }
    }

    // Visible for unit testing
    static Translog.Location updateReplicaRequest(BulkItemResultHolder bulkItemResult,
                                                  final DocWriteRequest.OpType opType,
                                                  final Translog.Location originalLocation,
                                                  BulkShardRequest request) {
        final Engine.Result operationResult = bulkItemResult.operationResult;
        final DocWriteResponse response = bulkItemResult.response;
        final BulkItemRequest replicaRequest = bulkItemResult.replicaRequest;

        if (operationResult == null) { // in case of noop update operation
            assert response.getResult() == DocWriteResponse.Result.NOOP : "only noop updates can have a null operation";
            replicaRequest.setPrimaryResponse(new BulkItemResponse(replicaRequest.id(), opType, response));
            return originalLocation;

        } else if (operationResult.hasFailure() == false) {
            BulkItemResponse primaryResponse = new BulkItemResponse(replicaRequest.id(), opType, response);
            replicaRequest.setPrimaryResponse(primaryResponse);
            // set a blank ShardInfo so we can safely send it to the replicas. We won't use it in the real response though.
            primaryResponse.getResponse().setShardInfo(new ShardInfo());
            // The operation was successful, advance the translog
            return locationToSync(originalLocation, operationResult.getTranslogLocation());

        } else {
            DocWriteRequest docWriteRequest = replicaRequest.request();
            Exception failure = operationResult.getFailure();
            if (isConflictException(failure)) {
                logger.trace((Supplier<?>) () -> new ParameterizedMessage("{} failed to execute bulk item ({}) {}",
                    request.shardId(), docWriteRequest.opType().getLowercase(), request), failure);
            } else {
                logger.debug((Supplier<?>) () -> new ParameterizedMessage("{} failed to execute bulk item ({}) {}",
                    request.shardId(), docWriteRequest.opType().getLowercase(), request), failure);
            }


            // if it's a conflict failure, and we already executed the request on a primary (and we execute it
            // again, due to primary relocation and only processing up to N bulk items when the shard gets closed)
            // then just use the response we got from the failed execution
            if (replicaRequest.getPrimaryResponse() == null || isConflictException(failure) == false) {
                replicaRequest.setPrimaryResponse(
                        new BulkItemResponse(replicaRequest.id(), docWriteRequest.opType(),
                                // Make sure to use request.indox() here, if you
                                // use docWriteRequest.index() it will use the
                                // concrete index instead of an alias if used!
                                new BulkItemResponse.Failure(request.index(), docWriteRequest.type(), docWriteRequest.id(), failure)));
            }
            return originalLocation;
        }
    }

    /** Executes bulk item requests and handles request execution exceptions */
    static Translog.Location executeBulkItemRequest(IndexMetaData metaData, IndexShard primary,
                                                    BulkShardRequest request, Translog.Location location,
                                                    int requestIndex, UpdateHelper updateHelper,
                                                    LongSupplier nowInMillisSupplier,
                                                    final MappingUpdatePerformer  mappingUpdater) throws Exception {
        final DocWriteRequest itemRequest = request.items()[requestIndex].request();
        final DocWriteRequest.OpType opType = itemRequest.opType();
        final BulkItemResultHolder responseHolder;
        switch (itemRequest.opType()) {
            case CREATE:
            case INDEX:
                responseHolder = executeIndexRequest((IndexRequest) itemRequest,
                        request.items()[requestIndex], primary, mappingUpdater);
                break;
            case UPDATE:
                responseHolder = executeUpdateRequest((UpdateRequest) itemRequest, primary, metaData, request,
                        requestIndex, updateHelper, nowInMillisSupplier, mappingUpdater);
                break;
            case DELETE:
                responseHolder = executeDeleteRequest((DeleteRequest) itemRequest, request.items()[requestIndex], primary);
                break;
            default: throw new IllegalStateException("unexpected opType [" + itemRequest.opType() + "] found");
        }

        final BulkItemRequest replicaRequest = responseHolder.replicaRequest;

        // update the bulk item request because update request execution can mutate the bulk item request
        request.items()[requestIndex] = replicaRequest;

        // Modify the replica request, if needed, and return a new translog location
        location = updateReplicaRequest(responseHolder, opType, location, request);

        assert replicaRequest.getPrimaryResponse() != null : "replica request must have a primary response";
        return location;
    }

    private static boolean isConflictException(final Exception e) {
        return ExceptionsHelper.unwrapCause(e) instanceof VersionConflictEngineException;
    }

    /**
     * Executes update request, delegating to a index or delete operation after translation,
     * handles retries on version conflict and constructs update response
     * NOTE: reassigns bulk item request at <code>requestIndex</code> for replicas to
     * execute translated update request (NOOP update is an exception). NOOP updates are
     * indicated by returning a <code>null</code> operation in {@link BulkItemResultHolder}
     * */
    private static BulkItemResultHolder executeUpdateRequest(UpdateRequest updateRequest, IndexShard primary,
                                                             IndexMetaData metaData, BulkShardRequest request,
                                                             int requestIndex, UpdateHelper updateHelper,
                                                             LongSupplier nowInMillis,
                                                             final MappingUpdatePerformer mappingUpdater) throws Exception {
        Engine.Result updateOperationResult = null;
        UpdateResponse updateResponse = null;
        BulkItemRequest replicaRequest = request.items()[requestIndex];
        int maxAttempts = updateRequest.retryOnConflict();
        for (int attemptCount = 0; attemptCount <= maxAttempts; attemptCount++) {
            final UpdateHelper.Result translate;
            // translate update request
            try {
                translate = updateHelper.prepare(updateRequest, primary, nowInMillis);
            } catch (Exception failure) {
                // we may fail translating a update to index or delete operation
                // we use index result to communicate failure while translating update request
                updateOperationResult = new Engine.IndexResult(failure, updateRequest.version(), SequenceNumbersService.UNASSIGNED_SEQ_NO);
                break; // out of retry loop
            }
            // execute translated update request
            switch (translate.getResponseResult()) {
                case CREATED:
                case UPDATED:
                    IndexRequest indexRequest = translate.action();
                    MappingMetaData mappingMd = metaData.mappingOrDefault(indexRequest.type());
                    indexRequest.process(mappingMd, request.index());
                    updateOperationResult = executeIndexRequestOnPrimary(indexRequest, primary, mappingUpdater);
                    break;
                case DELETED:
                    DeleteRequest deleteRequest = translate.action();
                    updateOperationResult = executeDeleteRequestOnPrimary(deleteRequest, primary);
                    break;
                case NOOP:
                    primary.noopUpdate(updateRequest.type());
                    break;
                default: throw new IllegalStateException("Illegal update operation " + translate.getResponseResult());
            }
            if (updateOperationResult == null) {
                // this is a noop operation
                updateResponse = translate.action();
                break; // out of retry loop
            } else if (updateOperationResult.hasFailure() == false) {
                // enrich update response and
                // set translated update (index/delete) request for replica execution in bulk items
                switch (updateOperationResult.getOperationType()) {
                    case INDEX:
                        IndexRequest updateIndexRequest = translate.action();
                        final IndexResponse indexResponse = new IndexResponse(primary.shardId(),
                            updateIndexRequest.type(), updateIndexRequest.id(), updateOperationResult.getSeqNo(),
                            updateOperationResult.getVersion(), ((Engine.IndexResult) updateOperationResult).isCreated());
                        BytesReference indexSourceAsBytes = updateIndexRequest.source();
                        updateResponse = new UpdateResponse(indexResponse.getShardInfo(),
                            indexResponse.getShardId(), indexResponse.getType(), indexResponse.getId(), indexResponse.getSeqNo(),
                            indexResponse.getVersion(), indexResponse.getResult());
                        if ((updateRequest.fetchSource() != null && updateRequest.fetchSource().fetchSource()) ||
                            (updateRequest.fields() != null && updateRequest.fields().length > 0)) {
                            Tuple<XContentType, Map<String, Object>> sourceAndContent =
                                XContentHelper.convertToMap(indexSourceAsBytes, true, updateIndexRequest.getContentType());
                            updateResponse.setGetResult(updateHelper.extractGetResult(updateRequest, request.index(),
                                indexResponse.getVersion(), sourceAndContent.v2(), sourceAndContent.v1(), indexSourceAsBytes));
                        }
                        // set translated request as replica request
                        replicaRequest = new BulkItemRequest(request.items()[requestIndex].id(), updateIndexRequest);
                        break;
                    case DELETE:
                        DeleteRequest updateDeleteRequest = translate.action();
                        DeleteResponse deleteResponse = new DeleteResponse(primary.shardId(),
                            updateDeleteRequest.type(), updateDeleteRequest.id(), updateOperationResult.getSeqNo(),
                            updateOperationResult.getVersion(), ((Engine.DeleteResult) updateOperationResult).isFound());
                        updateResponse = new UpdateResponse(deleteResponse.getShardInfo(),
                            deleteResponse.getShardId(), deleteResponse.getType(), deleteResponse.getId(), deleteResponse.getSeqNo(),
                            deleteResponse.getVersion(), deleteResponse.getResult());
                        updateResponse.setGetResult(updateHelper.extractGetResult(updateRequest,
                            request.index(), deleteResponse.getVersion(), translate.updatedSourceAsMap(),
                            translate.updateSourceContentType(), null));
                        // set translated request as replica request
                        replicaRequest = new BulkItemRequest(request.items()[requestIndex].id(), updateDeleteRequest);
                        break;
                }
                assert updateOperationResult.getSeqNo() != SequenceNumbersService.UNASSIGNED_SEQ_NO;
                // successful operation
                break; // out of retry loop
            } else if (updateOperationResult.getFailure() instanceof VersionConflictEngineException == false) {
                // not a version conflict exception
                break; // out of retry loop
            }
        }
        return new BulkItemResultHolder(updateResponse, updateOperationResult, replicaRequest);
    }

    static boolean shouldExecuteReplicaItem(final BulkItemRequest request, final int index) {
        final BulkItemResponse primaryResponse = request.getPrimaryResponse();
        assert primaryResponse != null : "expected primary response to be set for item [" + index + "] request ["+ request.request() +"]";
        return primaryResponse.isFailed() == false &&
                primaryResponse.getResponse().getResult() != DocWriteResponse.Result.NOOP;
    }

    @Override
    public WriteReplicaResult<BulkShardRequest> shardOperationOnReplica(BulkShardRequest request, IndexShard replica) throws Exception {
        Translog.Location location = null;
        for (int i = 0; i < request.items().length; i++) {
            BulkItemRequest item = request.items()[i];
            if (shouldExecuteReplicaItem(item, i)) {
                DocWriteRequest docWriteRequest = item.request();
                DocWriteResponse primaryResponse = item.getPrimaryResponse().getResponse();
                final Engine.Result operationResult;
                try {
                    switch (docWriteRequest.opType()) {
                        case CREATE:
                        case INDEX:
                            operationResult = executeIndexRequestOnReplica(primaryResponse, (IndexRequest) docWriteRequest, replica);
                            break;
                        case DELETE:
                            operationResult = executeDeleteRequestOnReplica(primaryResponse, (DeleteRequest) docWriteRequest, replica);
                            break;
                        default:
                            throw new IllegalStateException("Unexpected request operation type on replica: "
                                + docWriteRequest.opType().getLowercase());
                    }
                    if (operationResult.hasFailure()) {
                        // check if any transient write operation failures should be bubbled up
                        Exception failure = operationResult.getFailure();
                        assert failure instanceof VersionConflictEngineException
                            || failure instanceof MapperParsingException
                            : "expected any one of [version conflict, mapper parsing, engine closed, index shard closed]" +
                            " failures. got " + failure;
                        if (!TransportActions.isShardNotAvailableException(failure)) {
                            throw failure;
                        }
                    } else {
                        location = locationToSync(location, operationResult.getTranslogLocation());
                    }
                } catch (Exception e) {
                    // if its not an ignore replica failure, we need to make sure to bubble up the failure
                    // so we will fail the shard
                    if (!TransportActions.isShardNotAvailableException(e)) {
                        throw e;
                    }
                }
            }
        }
        return new WriteReplicaResult<>(request, location, null, replica, logger);
    }

    private static Translog.Location locationToSync(Translog.Location current, Translog.Location next) {
        /* here we are moving forward in the translog with each operation. Under the hood
         * this might cross translog files which is ok since from the user perspective
         * the translog is like a tape where only the highest location needs to be fsynced
         * in order to sync all previous locations even though they are not in the same file.
         * When the translog rolls over files the previous file is fsynced on after closing if needed.*/
        assert next != null : "next operation can't be null";
        assert current == null || current.compareTo(next) < 0 : "translog locations are not increasing";
        return next;
    }

    /**
     * Execute the given {@link IndexRequest} on a replica shard, throwing a
     * {@link RetryOnReplicaException} if the operation needs to be re-tried.
     */
    public static Engine.IndexResult executeIndexRequestOnReplica(DocWriteResponse primaryResponse, IndexRequest request, IndexShard replica) throws IOException {
        final ShardId shardId = replica.shardId();
        SourceToParse sourceToParse =
            SourceToParse.source(SourceToParse.Origin.REPLICA, shardId.getIndexName(), request.type(), request.id(), request.source(),
                request.getContentType()).routing(request.routing()).parent(request.parent());

        final Engine.Index operation;
        final long version = primaryResponse.getVersion();
        final VersionType versionType = request.versionType().versionTypeForReplicationAndRecovery();
        assert versionType.validateVersionForWrites(version);
        final long seqNo = primaryResponse.getSeqNo();
        try {
            operation = replica.prepareIndexOnReplica(sourceToParse, seqNo, version, versionType, request.getAutoGeneratedTimestamp(), request.isRetry());
        } catch (MapperParsingException e) {
            return new Engine.IndexResult(e, version, seqNo);
        }
        Mapping update = operation.parsedDoc().dynamicMappingsUpdate();
        if (update != null) {
            throw new RetryOnReplicaException(shardId, "Mappings are not available on the replica yet, triggered update: " + update);
        }
        return replica.index(operation);
    }

    /** Utility method to prepare an index operation on primary shards */
    static Engine.Index prepareIndexOperationOnPrimary(IndexRequest request, IndexShard primary) {
        SourceToParse sourceToParse =
            SourceToParse.source(SourceToParse.Origin.PRIMARY, request.index(), request.type(), request.id(), request.source(),
                request.getContentType()).routing(request.routing()).parent(request.parent());
        return primary.prepareIndexOnPrimary(sourceToParse, request.version(), request.versionType(), request.getAutoGeneratedTimestamp(), request.isRetry());
    }

    /** Executes index operation on primary shard after updates mapping if dynamic mappings are found */
    public static Engine.IndexResult executeIndexRequestOnPrimary(IndexRequest request, IndexShard primary,
                                                                  MappingUpdatePerformer mappingUpdater) throws Exception {
        MappingUpdatePerformer.MappingUpdateResult result = mappingUpdater.updateMappingsIfNeeded(primary, request);
        if (result.isFailed()) {
            return new Engine.IndexResult(result.failure, request.version());
        }
        return primary.index(result.operation);
    }

    private static Engine.DeleteResult executeDeleteRequestOnPrimary(DeleteRequest request, IndexShard primary) throws IOException {
        final Engine.Delete delete = primary.prepareDeleteOnPrimary(request.type(), request.id(), request.version(), request.versionType());
        return primary.delete(delete);
    }

    private static Engine.DeleteResult executeDeleteRequestOnReplica(DocWriteResponse primaryResponse, DeleteRequest request, IndexShard replica) throws IOException {
        final VersionType versionType = request.versionType().versionTypeForReplicationAndRecovery();
        final long version = primaryResponse.getVersion();
        assert versionType.validateVersionForWrites(version);
        final Engine.Delete delete = replica.prepareDeleteOnReplica(request.type(), request.id(),
                primaryResponse.getSeqNo(), request.primaryTerm(), version, versionType);
        return replica.delete(delete);
    }

    class ConcreteMappingUpdatePerformer implements MappingUpdatePerformer {

        @Nullable
        public MappingUpdateResult updateMappingsIfNeeded(IndexShard primary, IndexRequest request) throws Exception {
            Engine.Index operation;
            try {
                operation = prepareIndexOperationOnPrimary(request, primary);
            } catch (MapperParsingException | IllegalArgumentException e) {
                return new MappingUpdateResult(e);
            }
            final Mapping update = operation.parsedDoc().dynamicMappingsUpdate();
            final ShardId shardId = primary.shardId();
            if (update != null) {
                // can throw timeout exception when updating mappings or ISE for attempting to update default mappings
                // which are bubbled up
                try {
                    mappingUpdatedAction.updateMappingOnMaster(shardId.getIndex(), request.type(), update);
                } catch (IllegalArgumentException e) {
                    // throws IAE on conflicts merging dynamic mappings
                    return new MappingUpdateResult(e);
                }
                try {
                    operation = prepareIndexOperationOnPrimary(request, primary);
                } catch (MapperParsingException | IllegalArgumentException e) {
                    return new MappingUpdateResult(e);
                }
                if (operation.parsedDoc().dynamicMappingsUpdate() != null) {
                    throw new ReplicationOperation.RetryOnPrimaryException(shardId,
                            "Dynamic mappings are not available on the node that holds the primary yet");
                }
            }
            return new MappingUpdateResult(operation);
        }
    }
}
