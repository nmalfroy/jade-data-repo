package bio.terra.integration;

import bio.terra.clienttests.KubernetesClientUtils;
import bio.terra.common.category.Integration;
import bio.terra.common.configuration.TestConfiguration;
import bio.terra.common.fixtures.Names;
import bio.terra.model.*;
import bio.terra.service.iam.IamRole;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles({"google", "integrationtest"})
@AutoConfigureMockMvc
@Category(Integration.class)
public class KubeTests extends UsersBase {
    private static Logger logger = LoggerFactory.getLogger(FileTest.class);

    @Autowired
    private DataRepoFixtures dataRepoFixtures;

    @Autowired
    private DataRepoClient dataRepoClient;

    @Autowired
    private TestConfiguration testConfiguration;

    private DatasetSummaryModel datasetSummaryModel;
    private String datasetId;
    private String profileId;

    @Before
    public void setup() throws Exception {
        super.setup();
        profileId = dataRepoFixtures.createBillingProfile(steward()).getId();
        datasetSummaryModel = dataRepoFixtures.createDataset(steward(), "file-acl-test-dataset.json");
        datasetId = datasetSummaryModel.getId();
        logger.info("created dataset " + datasetId);
        dataRepoFixtures.addDatasetPolicyMember(
            steward(), datasetSummaryModel.getId(), IamRole.CUSTODIAN, custodian().getEmail());
        KubernetesClientUtils.getKubernetesClientObject();
    }

    @After
    public void tearDown() throws Exception {
        if (datasetId != null) {
            dataRepoFixtures.deleteDataset(steward(), datasetId);
        }
    }

    @Test
    public void longFileLoadTest() throws Exception {
        // TODO: want this to run about 5 minutes on 2 DRmanager instances. The speed of loads is when they are
        //  not local is about 2.5GB/minutes. With a fixed size of 1GB, each instance should do 2.5 files per minute,
        //  so two instances should do 5 files per minute. To run 5 minutes we should run 25 files.
        //  (There are 25 files in the directory, so if we need more we should do a reuse scheme like the fileLoadTest)
        final int filesToLoad = 2;//25;

        String loadTag = Names.randomizeName("longtest");

        BulkLoadArrayRequestModel
            arrayLoad = new BulkLoadArrayRequestModel()
            .profileId(profileId)
            .loadTag(loadTag)
            .maxFailedFileLoads(filesToLoad); // do not stop if there is a failure.

        logger.info("longFileLoadTest loading " + filesToLoad + " files into dataset id " + datasetId);

        for (int i = 0; i < filesToLoad; i++) {
            String tailPath = String.format("/fileloadscaletest/file1GB-%02d.txt", i);
            String sourcePath = "gs://jade-testdata-uswestregion" + tailPath;
            String targetPath = "/" + loadTag + tailPath;

            BulkLoadFileModel model = new BulkLoadFileModel().mimeType("application/binary");
            model.description("bulk load file " + i)
                .sourcePath(sourcePath)
                .targetPath(targetPath);
            arrayLoad.addLoadArrayItem(model);
        }

        // Breaking down this call so we can scale in between file loads
        // BulkLoadArrayResultModel result = dataRepoFixtures.bulkLoadArray(steward(), datasetId, arrayLoad);
        TestConfiguration.User user = steward();
        DataRepoResponse<BulkLoadArrayResultModel> response = null;
        IOException lastException = null;

        // initial request
        DataRepoResponse<JobModel> launchResponse = dataRepoFixtures.postBulkLoadArrayRequest(
            user, datasetId, arrayLoad);

        // let it run a few iterations
        // response  = dataRepoClient.waitForResponse(user, launchResponse, BulkLoadArrayResultModel.class, 5);
        // TimeUnit.SECONDS.sleep(10);
        // scale pods
        // KubernetesClientUtils.killPod("sh");
        // wait
       // TimeUnit.SECONDS.sleep(30);
        /*for (int i = 0; i < 4; i++) {
            try {
                response  = dataRepoClient.waitForResponse(user, launchResponse,
                    BulkLoadArrayResultModel.class, 15);
                break;
            } catch(IOException ex) {
                logger.info("Try #{): Wait for response", i);
                lastException = ex;
            }
        }
        if (response == null && lastException != null){
            throw lastException;
        }*/
        response  = dataRepoClient.waitForResponse(user, launchResponse,
            BulkLoadArrayResultModel.class, true);


        BulkLoadArrayResultModel result = dataRepoFixtures.checkBulkLoadArraySuccess(response);

        BulkLoadResultModel loadSummary = result.getLoadSummary();
        logger.info("Total files    : " + loadSummary.getTotalFiles());
        logger.info("Succeeded files: " + loadSummary.getSucceededFiles());
        logger.info("Failed files   : " + loadSummary.getFailedFiles());
        logger.info("Not Tried files: " + loadSummary.getNotTriedFiles());
    }
}
