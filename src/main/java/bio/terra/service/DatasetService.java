package bio.terra.service;

import bio.terra.controller.AuthenticatedUserRequest;
import bio.terra.dao.DatasetDao;
import bio.terra.flight.dataset.create.DatasetCreateFlight;
import bio.terra.flight.dataset.delete.DatasetDeleteFlight;
import bio.terra.flight.dataset.ingest.IngestMapKeys;
import bio.terra.flight.dataset.ingest.DatasetIngestFlight;
import bio.terra.metadata.MetadataEnumeration;
import bio.terra.metadata.Dataset;
import bio.terra.metadata.DatasetSummary;
import bio.terra.model.DeleteResponseModel;
import bio.terra.model.EnumerateDatasetModel;
import bio.terra.model.IngestRequestModel;
import bio.terra.model.DatasetJsonConversion;
import bio.terra.model.DatasetModel;
import bio.terra.model.DatasetRequestModel;
import bio.terra.model.DatasetSummaryModel;
import bio.terra.service.dataproject.DataLocationService;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Stairway;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.dsde.workbench.client.sam.model.ResourceAndAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DatasetService {
    private static Logger logger = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetDao datasetDao;
    private final Stairway stairway;
    private final JobService jobService; // for handling flight response
    private final DataLocationService dataLocationService;

    @Autowired
    public DatasetService(DatasetDao datasetDao,
                          Stairway stairway,
                          JobService jobService,
                          DataLocationService dataLocationService) {
        this.datasetDao = datasetDao;
        this.stairway = stairway;
        this.jobService = jobService;
        this.dataLocationService = dataLocationService;
    }

    public DatasetSummaryModel createDataset(DatasetRequestModel datasetRequest, AuthenticatedUserRequest userInfo) {
        FlightMap flightMap = new FlightMap();
        flightMap.put(JobMapKeys.REQUEST.getKeyName(), datasetRequest);
        flightMap.put(JobMapKeys.DESCRIPTION.getKeyName(), "Creating a dataset");
        flightMap.put(JobMapKeys.USER_INFO.getKeyName(), userInfo);
        String flightId = stairway.submit(DatasetCreateFlight.class, flightMap);
        return getResponse(flightId, DatasetSummaryModel.class);
    }

    public Dataset retrieve(UUID id) {
        Dataset dataset = datasetDao.retrieve(id);
        return dataset.dataProject(dataLocationService.getProjectForDataset(dataset));
    }

    public DatasetModel retrieveModel(UUID id) {
        return DatasetJsonConversion.datasetModelFromDataset(retrieve(id));
    }

    public EnumerateDatasetModel enumerate(
        int offset, int limit, String sort, String direction, String filter, List<ResourceAndAccessPolicy> resources) {
        if (resources.isEmpty()) {
            return new EnumerateDatasetModel().total(0);
        }
        List<UUID> resourceIds = resources
            .stream()
            .map(resource -> UUID.fromString(resource.getResourceId()))
            .collect(Collectors.toList());
        MetadataEnumeration<DatasetSummary> datasetEnum = datasetDao.enumerate(
            offset, limit, sort, direction, filter, resourceIds);
        List<DatasetSummaryModel> summaries = datasetEnum.getItems()
            .stream()
            .map(DatasetJsonConversion::datasetSummaryModelFromDatasetSummary)
            .collect(Collectors.toList());
        return new EnumerateDatasetModel().items(summaries).total(datasetEnum.getTotal());
    }

    public DeleteResponseModel delete(UUID id, AuthenticatedUserRequest userInfo) {
        FlightMap flightMap = new FlightMap();
        flightMap.put(JobMapKeys.REQUEST.getKeyName(), id);
        flightMap.put(JobMapKeys.DESCRIPTION.getKeyName(), "Deleting the dataset with ID " + id);
        flightMap.put(JobMapKeys.USER_INFO.getKeyName(), userInfo);
        String flightId = stairway.submit(DatasetDeleteFlight.class, flightMap);
        return getResponse(flightId, DeleteResponseModel.class);
    }

    public String ingestDataset(String id, IngestRequestModel ingestRequestModel) {
        // Fill in a default load id if the caller did not provide one in the ingest request.
        if (StringUtils.isEmpty(ingestRequestModel.getLoadTag())) {
            String loadTag = "load-at-" + Instant.now().atZone(ZoneId.of("Z")).format(DateTimeFormatter.ISO_INSTANT);
            ingestRequestModel.setLoadTag(loadTag);
        }

        FlightMap flightMap = new FlightMap();
        flightMap.put(JobMapKeys.DESCRIPTION.getKeyName(),
            "Ingest from " + ingestRequestModel.getPath() +
                " to " + ingestRequestModel.getTable() +
                " in dataset id " + id);
        flightMap.put(JobMapKeys.REQUEST.getKeyName(), ingestRequestModel);
        flightMap.put(IngestMapKeys.DATASET_ID, id);
        return stairway.submit(DatasetIngestFlight.class, flightMap);
    }

    private <T> T getResponse(String flightId, Class<T> resultClass) {
        stairway.waitForFlight(flightId);
        return jobService.retrieveJobResult(flightId, resultClass);
    }
}
