package com.instaclustr.esop.impl.backup;

import javax.validation.constraints.Min;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.services.s3.model.MetadataDirective;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.instaclustr.esop.impl.DatabaseEntities;
import com.instaclustr.esop.impl.DatabaseEntities.DatabaseEntitiesDeserializer;
import com.instaclustr.esop.impl.DatabaseEntities.DatabaseEntitiesSerializer;
import com.instaclustr.esop.impl.GatheringOperationCoordinatorException;
import com.instaclustr.esop.impl.ProxySettings;
import com.instaclustr.esop.impl.StorageLocation;
import com.instaclustr.measure.DataRate;
import com.instaclustr.measure.Time;
import com.instaclustr.operations.Operation;
import com.instaclustr.operations.OperationCoordinator;
import com.instaclustr.operations.OperationFailureException;
import com.instaclustr.operations.ResultGatherer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupOperation extends Operation<BackupOperationRequest> implements Cloneable {

    private static final Logger logger = LoggerFactory.getLogger(BackupOperation.class);

    private final OperationCoordinator<BackupOperationRequest> coordinator;

    @AssistedInject
    public BackupOperation(Optional<OperationCoordinator<BackupOperationRequest>> coordinator,
                           @Assisted final BackupOperationRequest request) {
        super(request);

        if (!coordinator.isPresent()) {
            throw new OperationFailureException("There is no operation coordinator.");
        }

        this.coordinator = coordinator.get();
    }

    public BackupOperation(final BackupOperationRequest request) {
        super(request);
        this.coordinator = null;
        this.type = "backup";
    }

    private AtomicBoolean closeOperation = new AtomicBoolean(false);

    // this constructor is not meant to be instantiated manually
    // and it fulfills the purpose of deserialisation from JSON string to an Operation object, currently just for testing purposes
    @JsonCreator
    private BackupOperation(@JsonProperty("type") final String type,
                            @JsonProperty("id") final UUID id,
                            @JsonProperty("creationTime") final Instant creationTime,
                            @JsonProperty("state") final State state,
                            @JsonProperty("failureCause") final Throwable failureCause,
                            @JsonProperty("progress") final float progress,
                            @JsonProperty("startTime") final Instant startTime,
                            @JsonProperty("storageLocation") final StorageLocation storageLocation,
                            @JsonProperty("duration") final Time duration,
                            @JsonProperty("bandwidth") final DataRate bandwidth,
                            @JsonProperty("concurrentConnections") final Integer concurrentConnections,
                            @JsonProperty("metadataDirective") final MetadataDirective metadataDirective,
                            @JsonProperty("cassandraDirectory") final Path cassandraDirectory,
                            @JsonProperty("entities")
                            @JsonSerialize(using = DatabaseEntitiesSerializer.class)
                            @JsonDeserialize(using = DatabaseEntitiesDeserializer.class) final DatabaseEntities entities,
                            @JsonProperty("snapshotTag") final String snapshotTag,
                            @JsonProperty("k8sNamespace") final String k8sNamespace,
                            @JsonProperty("k8sSecretName") final String k8sBackupSecretName,
                            @JsonProperty("globalRequest") final boolean globalRequest,
                            @JsonProperty("dc") final String dc,
                            @JsonProperty("timeout") @Min(1) final Integer timeout,
                            @JsonProperty("insecure") final boolean insecure,
                            @JsonProperty("createMissingBucket") final boolean createMissingBucket,
                            @JsonProperty("skipBucketVerification") final boolean skipBucketVerification,
                            @JsonProperty("schemaVersion") final String schemaVersion,
                            @JsonProperty("uploadClusterTopology") final boolean uploadClusterTopology,
                            @JsonProperty("proxySettings") final ProxySettings proxySettings) {
        super(type, id, creationTime, state, failureCause, progress, startTime, new BackupOperationRequest(type,
                                                                                                           storageLocation,
                                                                                                           duration,
                                                                                                           bandwidth,
                                                                                                           concurrentConnections,
                                                                                                           metadataDirective,
                                                                                                           cassandraDirectory,
                                                                                                           entities,
                                                                                                           snapshotTag,
                                                                                                           k8sNamespace,
                                                                                                           k8sBackupSecretName,
                                                                                                           globalRequest,
                                                                                                           dc,
                                                                                                           timeout,
                                                                                                           insecure,
                                                                                                           createMissingBucket,
                                                                                                           skipBucketVerification,
                                                                                                           schemaVersion,
                                                                                                           uploadClusterTopology,
                                                                                                           proxySettings));
        coordinator = null;
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    @Override
    protected void run0() throws Exception {
        assert coordinator != null;

        final ResultGatherer<BackupOperationRequest> coordinatorResult = coordinator.coordinate(this);

        coordinatorResult.getFailedOperations().forEach(e -> logger.error(e.exceptionMessage));
        coordinatorResult.getErrorneousOperations().forEach(e -> logger.error(e.exceptionMessage));

        if (coordinatorResult.hasErrors()) {
            throw new GatheringOperationCoordinatorException(coordinatorResult.getErrorneousOperations());
        }
    }
}
