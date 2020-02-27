package bio.terra.service.filedata.google.gcs;

import bio.terra.service.filedata.google.firestore.FireStoreDao;
import bio.terra.service.filedata.google.firestore.FireStoreFile;
import bio.terra.service.dataset.Dataset;
import bio.terra.service.filedata.FSFile;
import bio.terra.service.filedata.FSFileInfo;
import bio.terra.service.filedata.FSItem;
import bio.terra.model.FileLoadModel;
import bio.terra.common.exception.PdaoException;
import bio.terra.common.exception.PdaoFileCopyException;
import bio.terra.common.exception.PdaoInvalidUriException;
import bio.terra.common.exception.PdaoSourceFileNotFoundException;
import bio.terra.service.resourcemanagement.google.GoogleBucketResource;
import bio.terra.service.resourcemanagement.google.GoogleProjectResource;
import bio.terra.service.resourcemanagement.DataLocationService;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Acl;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.CopyWriter;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@Profile("google")
public class GcsPdao {
    private static final Logger logger = LoggerFactory.getLogger(GcsPdao.class);
    private static final int DATASET_DELETE_BATCH_SIZE = 1000;

    private final GcsProjectFactory gcsProjectFactory;
    private final DataLocationService dataLocationService;
    private final FireStoreDao fileDao;

    @Autowired
    public GcsPdao(
        GcsProjectFactory gcsProjectFactory,
        DataLocationService dataLocationService,
        FireStoreDao fileDao) {
        this.gcsProjectFactory = gcsProjectFactory;
        this.dataLocationService = dataLocationService;
        this.fileDao = fileDao;
    }

    private Storage storageForBucket(GoogleBucketResource bucketResource) {
        GoogleProjectResource projectResource = bucketResource.getProjectResource();
        GcsProject gcsProject = gcsProjectFactory.get(projectResource.getGoogleProjectId());
        return gcsProject.getStorage();
    }

    public FSFileInfo copyFile(Dataset dataset,
                               FileLoadModel fileLoadModel,
                               String fileId,
                               GoogleBucketResource bucketResource) {
        String sourceBucket;
        String sourcePath;

        try {
            URI sourceUri = URI.create(fileLoadModel.getSourcePath());
            if (!StringUtils.equals(sourceUri.getScheme(), "gs")) {
                throw new PdaoInvalidUriException("Source path is not a gs path: '" +
                    fileLoadModel.getSourcePath() + "'");
            }
            if (sourceUri.getPort() != -1) {
                throw new PdaoInvalidUriException("Source path must not have a port specified: '" +
                    fileLoadModel.getSourcePath() + "'");
            }
            sourceBucket = sourceUri.getAuthority();
            sourcePath = StringUtils.removeStart(sourceUri.getPath(), "/");
        } catch (IllegalArgumentException ex) {
            throw new PdaoInvalidUriException("Invalid gs path: '" +
                fileLoadModel.getSourcePath() + "'", ex);
        }

        if (sourceBucket == null || sourcePath == null) {
            throw new PdaoInvalidUriException("Invalid gs path: '" +
                fileLoadModel.getSourcePath() + "'");
        }

        Storage storage = storageForBucket(bucketResource);
        Blob sourceBlob = storage.get(BlobId.of(sourceBucket, sourcePath));
        if (sourceBlob == null) {
            throw new PdaoSourceFileNotFoundException("Source file not found: '" +
                fileLoadModel.getSourcePath() + "'");
        }

        // Our path is /<dataset-id>/<file-id>
        String targetPath = dataset.getId().toString() + "/" + fileId;

        try {
            // The documentation is vague whether or not it is important to copy by chunk. One set of
            // examples does it and another doesn't.
            //
            // I have been seeing timeouts and I think they are due to particularly large files,
            // so I changed exported the timeouts to application.properties to allow for tuning
            // and I am changing this to copy chunks.

            int mb = 1024*1024;
            Runtime runtime = Runtime.getRuntime();
            System.out.println("##### Heap utilization statistics [MB] #####");
            System.out.println("Used Memory:" + (runtime.totalMemory() - runtime.freeMemory()) / mb);
            System.out.println("Free Memory:" + runtime.freeMemory() / mb);
            System.out.println("Total Memory:" + runtime.totalMemory() / mb);
            System.out.println("Max Memory:" + runtime.maxMemory() / mb);

            // timer start
            long nsStart = System.nanoTime();

            // create an empty target blob
            BlobId targetBlobId = BlobId.of(bucketResource.getName(), targetPath);
            BlobInfo targetBlobInfo = BlobInfo.newBuilder(targetBlobId).build();
            Blob targetBlob = storage.create(targetBlobInfo);

//            // download the source blob to the target blob using a shared output stream
            WriteChannel targetBlobWriter = targetBlob.writer();
//            WriteChannel targetBlobWriter = storage.writer(targetBlobInfo);
//            targetBlobWriter.setChunkSize(100 * 1048576); // 100MiB
            sourceBlob.downloadTo(Channels.newOutputStream(targetBlobWriter));

//            ReadChannel readChannel = null;
//            WriteChannel writeChannel = null;
//            try {
//                readChannel = sourceBlob.reader();
//                writeChannel = targetBlob.writer();
//                readChannel.setChunkSize(100 * 1024 * 1024); // 100MB
//                writeChannel.setChunkSize(100 * 1024 * 1024); // 100MB
//                ByteBuffer bytes = ByteBuffer.allocate(100 * 1024 * 1024); // 100MB
//                while (readChannel.read(bytes) > 0) {
//                    bytes.flip();
//                    writeChannel.write(bytes);
//                    bytes.clear();
//                }
//                } catch (IOException ioEx) {
//                throw new RuntimeException(ioEx);
//            }
//
//            // cleanup channels
//            try {
//                readChannel.close();
//                writeChannel.close();
//            } catch (Exception ex) { }

////            Long megabytesPerChunk = Long.valueOf(500);
////            Storage.CopyRequest request =
////                Storage.CopyRequest.newBuilder()
////                    .setSource(sourceBlob.getBlobId())
////                    .setTarget(BlobId.of(bucketResource.getName(), targetPath), Storage.BlobTargetOption.disableGzipContent())
////                    .setMegabytesCopiedPerChunk(megabytesPerChunk)
////                    .build();
////            CopyWriter copyWriter = storage.copy(request);
////            CopyWriter copyWriter = sourceBlob.copyTo(BlobId.of(bucketResource.getName(), targetPath));
//
//            int rpcCtr = 0;
//            while (!copyWriter.isDone()) {
//                copyWriter.copyChunk();
//                rpcCtr++;
//            }
//            Blob targetBlob = copyWriter.getResult();

            // timer end
            long nsEnd = System.nanoTime();
            long nsElapsed = (nsEnd - nsStart);
            System.out.println("nsElapsed = " + nsElapsed);
            System.out.println("secElapsed = " + nsElapsed/Math.pow(10,9));
//            System.out.println("rpcCtr = " + rpcCtr);

            if (targetBlob == null) {
                System.out.println("targetBlob = null");
                throw new RuntimeException("targetBlob = null");
            }

            // MD5 is computed per-component. So if there are multiple components, the MD5 here is
            // not useful for validating the contents of the file on access. Therefore, we only
            // return the MD5 if there is only a single component. For more details,
            // see https://cloud.google.com/storage/docs/hashes-etags
            Integer componentCount = targetBlob.getComponentCount();
            String checksumMd5 = null;
            if (componentCount == null || componentCount == 1) {
                checksumMd5 = targetBlob.getMd5ToHexString();
            }

            // Grumble! It is not documented what the meaning of the Long is.
            // From poking around I think it is a standard POSIX milliseconds since Jan 1, 1970.
            Instant createTime = Instant.ofEpochMilli(targetBlob.getCreateTime());

            URI gspath = new URI("gs",
                bucketResource.getName(),
                "/" + targetPath,
                null,
                null);

            FSFileInfo fsFileInfo = new FSFileInfo()
                .fileId(fileId)
                .createdDate(createTime.toString())
                .gspath(gspath.toString())
                .checksumCrc32c(targetBlob.getCrc32cToHexString())
                .checksumMd5(checksumMd5)
                .size(targetBlob.getSize())
                .bucketResourceId(bucketResource.getResourceId().toString());

            return fsFileInfo;
        } catch (StorageException ex) {
            // For now, we assume that the storage exception is caused by bad input (the file copy exception
            // derives from BadRequestException). I think there are several cases here. We might need to retry
            // for flaky google case or we might need to bail out if access is denied.
            throw new PdaoFileCopyException("File ingest failed", ex);
        } catch (URISyntaxException ex) {
            throw new PdaoException("Bad URI of our own making", ex);
        }
    }

    // Three flavors of deleteFileMetadata
    // 1. for undo file ingest - it gets the bucket path from the dataset and file id
    // 2. for delete file flight - it gets bucket path from the gspath
    // 3. for delete file consumer for deleting all files - it gets bucket path
    //    from gspath in the fireStoreFile

    public boolean deleteFileById(Dataset dataset,
                                  String fileId,
                                  GoogleBucketResource bucketResource) {
        String bucketPath = dataset.getId().toString() + "/" + fileId;
        return deleteWorker(bucketResource, bucketPath);
    }

    public boolean deleteFileByGspath(String inGspath, GoogleBucketResource bucketResource) {
        if (inGspath != null) {
            URI uri = URI.create(inGspath);
            String bucketPath = StringUtils.removeStart(uri.getPath(), "/");
            return deleteWorker(bucketResource, bucketPath);
        }
        return false;
    }

    // Consumer method for deleting GCS files driven from a scan over the firestore files
    public void deleteFile(FireStoreFile fireStoreFile) {
        if (fireStoreFile != null) {
            GoogleBucketResource bucketResource = dataLocationService.lookupBucket(fireStoreFile.getBucketResourceId());
            deleteFileByGspath(fireStoreFile.getGspath(), bucketResource);
        }
    }

    private boolean deleteWorker(GoogleBucketResource bucketResource, String bucketPath) {
        GcsProject gcsProject = gcsProjectFactory.get(bucketResource.getProjectResource().getGoogleProjectId());
        Storage storage = gcsProject.getStorage();
        Optional<Blob> blob = Optional.ofNullable(storage.get(BlobId.of(bucketResource.getName(), bucketPath)));
        if (blob.isPresent()) {
            return blob.get().delete();
        }
        return false;
    }

    private enum AclOp {
        ACL_OP_CREATE,
        ACL_OP_DELETE
    };

    public void setAclOnFiles(Dataset dataset, List<String> fileIds, String readersPolicyEmail) {
        fileAclOp(AclOp.ACL_OP_CREATE, dataset, fileIds, readersPolicyEmail);
    }

    public void removeAclOnFiles(Dataset dataset, List<String> fileIds, String readersPolicyEmail) {
        fileAclOp(AclOp.ACL_OP_DELETE, dataset, fileIds, readersPolicyEmail);
    }

    private void fileAclOp(AclOp op, Dataset dataset, List<String> fileIds, String readersPolicyEmail) {
        Acl.Group readerGroup = new Acl.Group(readersPolicyEmail);
        Acl acl = Acl.newBuilder(readerGroup, Acl.Role.READER).build();

        for (String fileId : fileIds) {
            FSItem fsItem = fileDao.retrieveById(dataset, fileId, 0, true);
            if (fsItem instanceof FSFile) {
                FSFile fsFile = (FSFile) fsItem;
                GoogleBucketResource bucketForFile = dataLocationService.lookupBucket(fsFile.getBucketResourceId());
                Storage storage = storageForBucket(bucketForFile);
                URI gsUri = URI.create(fsFile.getGspath());
                String bucketPath = StringUtils.removeStart(gsUri.getPath(), "/");
                BlobId blobId = BlobId.of(bucketForFile.getName(), bucketPath);
                switch (op) {
                    case ACL_OP_CREATE:
                        storage.createAcl(blobId, acl);
                        break;
                    case ACL_OP_DELETE:
                        storage.deleteAcl(blobId, readerGroup);
                        break;
                }
            }
        }
    }
}
