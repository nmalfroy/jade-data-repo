package bio.terra.service.dataset;

import bio.terra.common.TestUtils;
import bio.terra.common.category.Integration;
import bio.terra.common.fixtures.DatasetFixtures;
import bio.terra.common.fixtures.JsonLoader;
import bio.terra.integration.DataRepoClient;
import bio.terra.integration.DataRepoFixtures;
import bio.terra.integration.DataRepoResponse;
import bio.terra.integration.UsersBase;
import bio.terra.model.AssetModel;
import bio.terra.model.DatasetModel;
import bio.terra.model.DatasetSpecificationModel;
import bio.terra.model.DatasetSummaryModel;
import bio.terra.model.DeleteResponseModel;
import bio.terra.model.EnumerateDatasetModel;
import bio.terra.service.configuration.ConfigEnum;
import bio.terra.service.iam.IamRole;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

// TODO move me to integration dir
@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles({"google", "integrationtest"})
@AutoConfigureMockMvc
@Category(Integration.class)
public class DatasetIntegrationTest extends UsersBase {
    private static final String omopDatasetName = "it_dataset_omop";
    private static final String omopDatasetDesc =
        "OMOP schema based on BigQuery schema from https://github.com/OHDSI/CommonDataModel/wiki";
    private static Logger logger = LoggerFactory.getLogger(DatasetIntegrationTest.class);

    @Autowired
    private DataRepoClient dataRepoClient;

    @Autowired
    private JsonLoader jsonLoader;

    @Autowired
    private DataRepoFixtures dataRepoFixtures;

    @Before
    public void setup() throws Exception {
        super.setup();
    }

    @Test
    public void datasetHappyPath() throws Exception {
        DatasetSummaryModel summaryModel = dataRepoFixtures.createDataset(steward(), "it-dataset-omop.json");
        try {
            logger.info("dataset id is " + summaryModel.getId());
            assertThat(summaryModel.getName(), startsWith(omopDatasetName));
            assertThat(summaryModel.getDescription(), equalTo(omopDatasetDesc));

            DatasetModel datasetModel = dataRepoFixtures.getDataset(steward(), summaryModel.getId());

            assertThat(datasetModel.getName(), startsWith(omopDatasetName));
            assertThat(datasetModel.getDescription(), equalTo(omopDatasetDesc));

            // There is a delay from when a resource is created in SAM to when it is available in an enumerate call.
            boolean metExpectation = TestUtils.eventualExpect(5, 60, true, () -> {
                EnumerateDatasetModel enumerateDatasetModel = dataRepoFixtures.enumerateDatasets(steward());
                boolean found = false;
                for (DatasetSummaryModel oneDataset : enumerateDatasetModel.getItems()) {
                    if (oneDataset.getId().equals(datasetModel.getId())) {
                        assertThat(oneDataset.getName(), startsWith(omopDatasetName));
                        assertThat(oneDataset.getDescription(), equalTo(omopDatasetDesc));
                        found = true;
                        break;
                    }
                }
                return found;
            });

            assertTrue("dataset was found in enumeration", metExpectation);

            // test allowable permissions

            dataRepoFixtures.addDatasetPolicyMember(
                steward(),
                summaryModel.getId(),
                IamRole.CUSTODIAN,
                custodian().getEmail());
            DataRepoResponse<EnumerateDatasetModel> enumDatasets = dataRepoFixtures.enumerateDatasetsRaw(custodian());
            assertThat("Custodian is authorized to enumerate datasets",
                enumDatasets.getStatusCode(),
                equalTo(HttpStatus.OK));

        } finally {
            logger.info("deleting dataset");
            dataRepoFixtures.deleteDatasetRaw(steward(), summaryModel.getId());
        }
    }

    @Test
    public void datasetUnauthorizedPermissionsTest() throws Exception {
        dataRepoFixtures.createDatasetError(custodian(), "dataset-minimal.json", HttpStatus.UNAUTHORIZED);
        dataRepoFixtures.createDatasetError(reader(), "dataset-minimal.json", HttpStatus.UNAUTHORIZED);

        EnumerateDatasetModel enumDatasetsResp = dataRepoFixtures.enumerateDatasets(reader());
        List<DatasetSummaryModel> items = enumDatasetsResp.getItems();
        if (items != null) {
            for (DatasetSummaryModel datasetModel : items) {
                logger.info(String.format("found dataset for reader: %s, created: %s",
                    datasetModel.getId(), datasetModel.getCreatedDate()));
            }
        }
        assertThat("Reader does not have access to datasets",
            enumDatasetsResp.getTotal(),
            equalTo(0));

        DatasetSummaryModel summaryModel = null;
        try {
            summaryModel = dataRepoFixtures.createDataset(steward(), "dataset-minimal.json");
            logger.info("dataset id is " + summaryModel.getId());

            DataRepoResponse<DatasetModel> getDatasetResp =
                dataRepoFixtures.getDatasetRaw(reader(), summaryModel.getId());
            assertThat("Reader is not authorized to get dataset",
                getDatasetResp.getStatusCode(),
                equalTo(HttpStatus.UNAUTHORIZED));

            DataRepoResponse<DeleteResponseModel> deleteResp1 = dataRepoFixtures.deleteDatasetRaw(
                reader(), summaryModel.getId());
            assertThat("Reader is not authorized to delete datasets",
                deleteResp1.getStatusCode(),
                equalTo(HttpStatus.UNAUTHORIZED));

            DataRepoResponse<DeleteResponseModel> deleteResp2 = dataRepoFixtures.deleteDatasetRaw(
                custodian(), summaryModel.getId());
            assertThat("Custodian is not authorized to delete datasets",
                deleteResp2.getStatusCode(),
                equalTo(HttpStatus.UNAUTHORIZED));
        } finally {
            if (summaryModel != null)
                dataRepoFixtures.deleteDataset(steward(), summaryModel.getId());
        }
    }

    @Test
    public void testAssetCreationUndo() throws Exception {
        // create a dataset
        DatasetSummaryModel summaryModel = dataRepoFixtures.createDataset(steward(), "it-dataset-omop.json");
        DatasetModel datasetModel = dataRepoFixtures.getDataset(steward(), summaryModel.getId());
        List<AssetModel> originalAssetList = datasetModel.getSchema().getAssets();

        assertThat("Asset specification is as originally expected",
            originalAssetList.size(),
            equalTo(1));
        AssetModel assetModel = new AssetModel()
            .name("assetName")
            .rootTable("person")
            .rootColumn("person_id")
            .tables(Arrays.asList(
                DatasetFixtures.buildAssetParticipantTable(),
                DatasetFixtures.buildAssetSampleTable()))
            .follow(Collections.singletonList("fpk_visit_person"));

        // have the asset creation fail
        // by calling the fault insertion
        dataRepoFixtures.setFault(steward(), ConfigEnum.CREATE_ASSET_FAULT.name(), true);

        // add an asset spec
        dataRepoFixtures.addDatasetAsset(
            steward(), datasetModel.getId(), assetModel);
        // make sure undo is completed successfully
        DatasetModel datasetModelWAsset = dataRepoFixtures.getDataset(steward(), datasetModel.getId());
        DatasetSpecificationModel datasetSpecificationModel = datasetModelWAsset.getSchema();
        List<AssetModel> assetList = datasetSpecificationModel.getAssets();

        // assert that the asset isn't there
        assertThat("Additional asset specification has never been added",
            assetList.size(),
            equalTo(1));
        // delete the dataset
        dataRepoFixtures.deleteDataset(steward(), summaryModel.getId());
    }
}