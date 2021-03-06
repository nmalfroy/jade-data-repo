package bio.terra.service.filedata.flight.ingest;

import bio.terra.app.configuration.ApplicationConfiguration;
import bio.terra.model.BulkLoadArrayRequestModel;
import bio.terra.model.BulkLoadRequestModel;
import bio.terra.service.configuration.ConfigurationService;
import bio.terra.service.dataset.DatasetService;
import bio.terra.service.filedata.google.gcs.GcsPdao;
import bio.terra.service.iam.IamAction;
import bio.terra.service.iam.IamProviderInterface;
import bio.terra.service.iam.IamResourceType;
import bio.terra.service.iam.flight.VerifyAuthorizationStep;
import bio.terra.service.job.JobMapKeys;
import bio.terra.service.kubernetes.KubeService;
import bio.terra.service.load.LoadService;
import bio.terra.service.load.flight.LoadLockStep;
import bio.terra.service.load.flight.LoadMapKeys;
import bio.terra.service.load.flight.LoadUnlockStep;
import bio.terra.service.resourcemanagement.DataLocationService;
import bio.terra.service.tabulardata.google.BigQueryPdao;
import bio.terra.stairway.Flight;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.RetryRuleExponentialBackoff;
import bio.terra.stairway.RetryRuleRandomBackoff;
import org.springframework.context.ApplicationContext;

/*
 * Required input parameters:
 * - DATASET_ID dataset we are loading into
 * - LOAD_TAG is the load tag for this ingest
 * - CONCURRENT_INGESTS is the number of ingests to allow to run in parallel
 * - CONCURRENT_FILES is the number of file loads to run in parallel
 * - REQUEST is a BulkLoadRequestModel or BulkLoadArrayRequestModel
 * - IS_ARRAY boolean, true if this is a bulk load array version
 */

public class FileIngestBulkFlight extends Flight {

    public FileIngestBulkFlight(FlightMap inputParameters,
                                Object applicationContext) {

        super(inputParameters, applicationContext);

        ApplicationContext appContext = (ApplicationContext) applicationContext;
        IamProviderInterface iamClient = (IamProviderInterface) appContext.getBean("iamProvider");
        LoadService loadService = (LoadService)appContext.getBean("loadService");
        ApplicationConfiguration appConfig = (ApplicationConfiguration)appContext.getBean("applicationConfiguration");
        DataLocationService locationService = (DataLocationService)appContext.getBean("dataLocationService");
        BigQueryPdao bigQueryPdao = (BigQueryPdao)appContext.getBean("bigQueryPdao");
        DatasetService datasetService = (DatasetService) appContext.getBean("datasetService");
        ConfigurationService configurationService = (ConfigurationService) appContext.getBean("configurationService");
        KubeService kubeService = (KubeService) appContext.getBean("kubeService");
        GcsPdao gcsPdao = (GcsPdao) appContext.getBean("gcsPdao");

        // Common input parameters
        String datasetId = inputParameters.get(JobMapKeys.DATASET_ID.getKeyName(), String.class);
        String loadTag = inputParameters.get(LoadMapKeys.LOAD_TAG, String.class);
        int driverWaitSeconds = inputParameters.get(LoadMapKeys.DRIVER_WAIT_SECONDS, Integer.class);
        int loadHistoryWaitSeconds = inputParameters.get(LoadMapKeys.LOAD_HISTORY_WAIT_SECONDS, Integer.class);
        int fileChunkSize = inputParameters.get(LoadMapKeys.LOAD_HISTORY_COPY_CHUNK_SIZE, Integer.class);
        boolean isArray = inputParameters.get(LoadMapKeys.IS_ARRAY, Boolean.class);
        // TODO: for reserving a bulk load slot:
        //    int concurrentIngests = inputParameters.get(LoadMapKeys.CONCURRENT_INGESTS, Integer.class);
        //  We can maybe just use the load tag lock table to know how many are active.

        // Parameters dependent on which request we get
        int maxFailedFileLoads;
        String profileId;

        if (isArray) {
            BulkLoadArrayRequestModel loadRequest =
                inputParameters.get(JobMapKeys.REQUEST.getKeyName(), BulkLoadArrayRequestModel.class);
            maxFailedFileLoads = loadRequest.getMaxFailedFileLoads();
            profileId = loadRequest.getProfileId();
        } else {
            BulkLoadRequestModel loadRequest =
                inputParameters.get(JobMapKeys.REQUEST.getKeyName(), BulkLoadRequestModel.class);
            maxFailedFileLoads = loadRequest.getMaxFailedFileLoads();
            profileId = loadRequest.getProfileId();
        }

        RetryRuleRandomBackoff createBucketRetry =
            new RetryRuleRandomBackoff(500, appConfig.getMaxStairwayThreads(), 5);

        RetryRuleExponentialBackoff driverRetry = new RetryRuleExponentialBackoff(5, 20, 600);

        // The flight plan:
        // 0. Verify authorization to do the ingest
        // 1. Lock the load tag - only one flight operating on a load tag at a time
        // 2. TODO: reserve a bulk load slot to make sure we have the threads to do the flight; abort otherwise (DR-754)
        // 3. Locate the bucket where this file should go and store it in the working map. We need to make the
        //    decision about where we will put the file and remember it persistently in the working map before
        //    we copy the file in. That allows the copy undo to know the location to look at to delete the file.
        //    We do this once here and pass the information into the worker flight. We also need to know the
        //    project that contains the bucket. It will be charged for the copying if the source file listing
        //    the files to load is in a requester pay bucket.
        // 4. Depends on the request type:
        //    a. isArray - put the array into the load_file table for processing
        //    b. !isArray - read the file into the load_file table for processing
        // 5. Main loading loop - shared with bulk ingest from a file in a bucket
        // 6. Depends on request type:
        //    a. isArray - generate the bulk array response: summary and array of results
        //    b. !isArray - generate the bulk file response - just the summary information
        // 7. TODO: Copy results into the database BigQuery (DR-694)
        // 8. Clean load_file table
        // 9. TODO: release the bulk load slot (DR-754) - may not need a step if we use the count of locked tags
        // 10. Unlock the load tag
        addStep(new VerifyAuthorizationStep(iamClient, IamResourceType.DATASET, datasetId, IamAction.INGEST_DATA));
        addStep(new LoadLockStep(loadService));
        addStep(new IngestFilePrimaryDataLocationStep(locationService, profileId), createBucketRetry);

        if (isArray) {
            addStep(new IngestPopulateFileStateFromArrayStep(loadService));
        } else {
            addStep(new IngestPopulateFileStateFromFileStep(
                loadService,
                appConfig.getMaxBadLoadFileLineErrorsReported(),
                appConfig.getLoadFilePopulateBatchSize(),
                gcsPdao));
        }
        addStep(new IngestDriverStep(
            loadService,
            configurationService,
            kubeService,
            datasetId,
            loadTag,
            maxFailedFileLoads,
            driverWaitSeconds,
            profileId), driverRetry);

        if (isArray) {
            addStep(new IngestBulkArrayResponseStep(loadService, loadTag));
        } else {
            addStep(new IngestBulkFileResponseStep(loadService, loadTag));
        }

        addStep(new IngestCopyLoadHistoryToBQStep(
            loadService,
            datasetService,
            loadTag,
            datasetId,
            bigQueryPdao,
            fileChunkSize,
            loadHistoryWaitSeconds));
        addStep(new IngestCleanFileStateStep(loadService));

        addStep(new LoadUnlockStep(loadService));
    }
}
