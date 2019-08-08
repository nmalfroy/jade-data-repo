package bio.terra.flight.snapshot.create;

import bio.terra.controller.AuthenticatedUserRequest;
import bio.terra.dao.SnapshotDao;
import bio.terra.exception.InternalServerErrorException;
import bio.terra.filesystem.FireStoreDependencyDao;
import bio.terra.flight.dataset.create.CreateDatasetAuthzResource;
import bio.terra.metadata.Snapshot;
import bio.terra.metadata.SnapshotSource;
import bio.terra.metadata.Dataset;
import bio.terra.model.SnapshotRequestModel;
import bio.terra.pdao.bigquery.BigQueryPdao;
import bio.terra.pdao.gcs.GcsPdao;
import bio.terra.service.JobMapKeys;
import bio.terra.service.SamClientService;
import bio.terra.service.DatasetService;
import bio.terra.stairway.FlightContext;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import com.google.api.client.http.HttpStatusCodes;
import org.broadinstitute.dsde.workbench.client.sam.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class AuthorizeSnapshot implements Step {
    private SamClientService sam;
    private BigQueryPdao bigQueryPdao;
    private FireStoreDependencyDao fireStoreDao;
    private SnapshotDao snapshotDao;
    private GcsPdao gcsPdao;
    private DatasetService datasetService;
    private static Logger logger = LoggerFactory.getLogger(CreateDatasetAuthzResource.class);

    public AuthorizeSnapshot(BigQueryPdao bigQueryPdao,
                            SamClientService sam,
                            FireStoreDependencyDao fireStoreDao,
                            SnapshotDao snapshotDao,
                            GcsPdao gcsPdao,
                            DatasetService datasetService) {
        this.bigQueryPdao = bigQueryPdao;
        this.sam = sam;
        this.fireStoreDao = fireStoreDao;
        this.snapshotDao = snapshotDao;
        this.gcsPdao = gcsPdao;
        this.datasetService = datasetService;
    }

    SnapshotRequestModel getRequestModel(FlightContext context) {
        FlightMap inputParameters = context.getInputParameters();
        return inputParameters.get(JobMapKeys.REQUEST.getKeyName(), SnapshotRequestModel.class);
    }

    @Override
    public StepResult doStep(FlightContext context) {
        FlightMap inputParameters = context.getInputParameters();
        AuthenticatedUserRequest userReq = inputParameters.get(
            JobMapKeys.USER_INFO.getKeyName(), AuthenticatedUserRequest.class);
        SnapshotRequestModel snapshotReq = getRequestModel(context);
        FlightMap workingMap = context.getWorkingMap();
        UUID snapshotId = workingMap.get("snapshotId", UUID.class);
        Snapshot snapshot = snapshotDao.retrieveSnapshot(snapshotId);
        Optional<List<String>> readersList = Optional.ofNullable(snapshotReq.getReaders());
        try {
            // This returns the policy email created by Google to correspond to the readers list in SAM
            String readersPolicyEmail = sam.createSnapshotResource(userReq, snapshotId, readersList);
            bigQueryPdao.addReaderGroupToSnapshot(snapshot, readersPolicyEmail);

            // Each dataset may keep its dependencies in its own scope. Therefore,
            // we have to iterate through the datasets in the snapshot and ask each one
            // to give us its list of file ids. Then we set acls on the files for that
            // dataset used by the snapshot.
            for (SnapshotSource snapshotSource : snapshot.getSnapshotSources()) {
                String datasetId = snapshotSource.getDataset().getId().toString();
                Dataset dataset = datasetService.retrieve(UUID.fromString(datasetId));
                List<String> fileIds = fireStoreDao.getDatasetSnapshotFileIds(dataset, snapshotId.toString());
                gcsPdao.setAclOnFiles(datasetId, fileIds, readersPolicyEmail);
            }
        } catch (ApiException ex) {
            throw new InternalServerErrorException("Couldn't add readers", ex);
        }
        return StepResult.getStepResultSuccess();
    }

    @Override
    public StepResult undoStep(FlightContext context) {
        FlightMap inputParameters = context.getInputParameters();
        AuthenticatedUserRequest userReq = inputParameters.get(
            JobMapKeys.USER_INFO.getKeyName(), AuthenticatedUserRequest.class);
        FlightMap workingMap = context.getWorkingMap();
        UUID snapshotId = workingMap.get("snapshotId", UUID.class);
        try {
            sam.deleteSnapshotResource(userReq, snapshotId);
            // We do not need to remove the ACL from the files or BigQuery. It disappears
            // when SAM deletes the ACL. How 'bout that!
        } catch (ApiException ex) {
            if (ex.getCode() == HttpStatusCodes.STATUS_CODE_UNAUTHORIZED) {
                // suppress exception
                logger.error("NEEDS CLEANUP: delete sam resource for snapshot " + snapshotId.toString());
                logger.warn(ex.getMessage());
            } else {
                throw new InternalServerErrorException(ex);
            }

        }
        return StepResult.getStepResultSuccess();
    }
}