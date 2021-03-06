package com.instaclustr.esop.s3;

import static com.amazonaws.event.ProgressEventType.TRANSFER_COMPLETED_EVENT;
import static com.amazonaws.event.ProgressEventType.TRANSFER_FAILED_EVENT;
import static java.util.Optional.ofNullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.transfer.PersistableTransfer;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.internal.S3ProgressListener;
import com.instaclustr.esop.impl.RemoteObjectReference;
import com.instaclustr.esop.impl.backup.BackupCommitLogsOperationRequest;
import com.instaclustr.esop.impl.backup.BackupOperationRequest;
import com.instaclustr.esop.impl.backup.Backuper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseS3Backuper extends Backuper {

    private static final Logger logger = LoggerFactory.getLogger(BaseS3Backuper.class);
    private final TransferManager transferManager;

    public BaseS3Backuper(final TransferManagerFactory transferManagerFactory,
                          final BackupOperationRequest request) {
        super(request);
        this.transferManager = transferManagerFactory.build(request);
    }

    public BaseS3Backuper(final TransferManagerFactory transferManagerFactory,
                          final BackupCommitLogsOperationRequest request) {
        super(request);
        this.transferManager = transferManagerFactory.build(request);
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) {
        return new S3RemoteObjectReference(objectKey, objectKey.toString());
    }

    @Override
    public RemoteObjectReference objectKeyToNodeAwareRemoteReference(final Path objectKey) {
        return new S3RemoteObjectReference(objectKey, resolveNodeAwareRemotePath(objectKey));
    }

    @Override
    public FreshenResult freshenRemoteObject(final RemoteObjectReference object) throws InterruptedException {
        final String canonicalPath = ((S3RemoteObjectReference) object).canonicalPath;

        final CopyObjectRequest copyRequest = new CopyObjectRequest(request.storageLocation.bucket, canonicalPath, request.storageLocation.bucket, canonicalPath)
            .withStorageClass(StorageClass.Standard)
            .withMetadataDirective(request.metadataDirective);

        try {
            // attempt to refresh existing object in the bucket via an inplace copy
            transferManager.copy(copyRequest).waitForCompletion();
            return FreshenResult.FRESHENED;

        } catch (final AmazonServiceException e) {
            // AWS S3 under certain access policies can't return NoSuchKey (404)
            // instead, it returns AccessDenied (403) — handle it the same way
            if (e.getStatusCode() != 404 && e.getStatusCode() != 403) {
                throw e;
            }

            // the freshen failed because the file/key didn't exist
            return FreshenResult.UPLOAD_REQUIRED;
        }
    }

    @Override
    public void uploadFile(final long size, final InputStream localFileStream, final RemoteObjectReference objectReference) throws Exception {
        final S3RemoteObjectReference s3RemoteObjectReference = (S3RemoteObjectReference) objectReference;

        final PutObjectRequest putObjectRequest = new PutObjectRequest(request.storageLocation.bucket,
                                                                       s3RemoteObjectReference.canonicalPath,
                                                                       localFileStream,
                                                                       new ObjectMetadata() {{
                                                                           setContentLength(size);
                                                                       }});

        upload(s3RemoteObjectReference, putObjectRequest);
    }

    @Override
    public void uploadText(final String text, final RemoteObjectReference objectReference) throws Exception {
        final S3RemoteObjectReference s3RemoteObjectReference = (S3RemoteObjectReference) objectReference;

        final PutObjectRequest putObjectRequest = new PutObjectRequest(request.storageLocation.bucket,
                                                                       s3RemoteObjectReference.canonicalPath,
                                                                       new ByteArrayInputStream(text.getBytes()),
                                                                       new ObjectMetadata() {{
                                                                           setContentLength(text.getBytes().length);
                                                                       }});

        upload(s3RemoteObjectReference, putObjectRequest);
    }

    private void upload(final S3RemoteObjectReference s3RemoteObjectReference,
                        final PutObjectRequest putObjectRequest) throws Exception {
        final UploadProgressListener listener = new UploadProgressListener(s3RemoteObjectReference);

        final Optional<AmazonClientException> exception = ofNullable(transferManager.upload(putObjectRequest, listener).waitForException());

        if (exception.isPresent()) {
            throw exception.get();
        }
    }

    public static class UploadProgressListener implements S3ProgressListener {

        private final S3RemoteObjectReference s3RemoteObjectReference;

        UploadProgressListener(final S3RemoteObjectReference s3RemoteObjectReference) {
            this.s3RemoteObjectReference = s3RemoteObjectReference;
        }

        @Override
        public void progressChanged(final ProgressEvent progressEvent) {
            final ProgressEventType progressEventType = progressEvent.getEventType();

            if (progressEventType == ProgressEventType.TRANSFER_PART_COMPLETED_EVENT) {
                logger.debug("Successfully uploaded part for {}.", s3RemoteObjectReference.canonicalPath);
            }

            if (progressEventType == ProgressEventType.TRANSFER_PART_FAILED_EVENT) {
                logger.debug("Failed to upload part for {}.", s3RemoteObjectReference.canonicalPath);
            }

            if (progressEventType == TRANSFER_FAILED_EVENT) {
                logger.debug("Failed to upload {}.", s3RemoteObjectReference.canonicalPath);
            }

            if (progressEventType == TRANSFER_COMPLETED_EVENT) {
                logger.debug("Successfully uploaded {}.", s3RemoteObjectReference.canonicalPath);
            }
        }

        @Override
        public void onPersistableTransfer(final PersistableTransfer persistableTransfer) {
            // We don't resume uploads
        }
    }

    @Override
    public void cleanup() {
        try {
            transferManager.shutdownNow(true);
        } catch (final Exception ex) {
            logger.warn("Exception occurred while shutting down transfer manager for S3Backuper", ex);
        }
    }
}
