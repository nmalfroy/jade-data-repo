package bio.terra.flight.file.ingest;

import bio.terra.filesystem.FileDao;
import bio.terra.filesystem.exception.FileSystemObjectAlreadyExistsException;
import bio.terra.flight.file.FileMapKeys;
import bio.terra.metadata.FSObject;
import bio.terra.metadata.Study;
import bio.terra.model.FileLoadModel;
import bio.terra.service.FileService;
import bio.terra.service.JobMapKeys;
import bio.terra.stairway.FlightContext;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import org.apache.commons.lang3.StringUtils;

import java.util.UUID;

public class IngestFileMetadataStepStart implements Step {
    private final FileDao fileDao;
    private final Study study;
    private final FileService fileService;

    public IngestFileMetadataStepStart(FileDao fileDao, Study study, FileService fileService) {
        this.fileDao = fileDao;
        this.study = study;
        this.fileService = fileService;
    }

    @Override
    public StepResult doStep(FlightContext context) {
        FlightMap inputParameters = context.getInputParameters();
        FileLoadModel loadModel = inputParameters.get(JobMapKeys.REQUEST.getKeyName(), FileLoadModel.class);
        String studyId = inputParameters.get(JobMapKeys.STUDY_ID.getKeyName(), String.class);
        UUID studyUUID = UUID.fromString(studyId);

        FlightMap workingMap = context.getWorkingMap();

        // Lookup the file - on a recovery, we may have already created it, but not
        // finished. Or it might already exist, created by someone else.
        FSObject fsObject = fileDao.retrieveByPathNoThrow(studyUUID, loadModel.getTargetPath());
        if (fsObject != null) {
            // OK, some file exists. If this flight created it, then we record it
            // and claim success. Otherwise someone else created it and we throw.
            if (StringUtils.equals(fsObject.getFlightId(), context.getFlightId())) {
                workingMap.put(FileMapKeys.OBJECT_ID, fsObject.getObjectId().toString());
                return StepResult.getStepResultSuccess();
            }
            throw new FileSystemObjectAlreadyExistsException("Path already exists: " + fsObject.getPath());
        }

        fsObject = new FSObject()
            .studyId(study.getId())
            .objectType(FSObject.FSObjectType.INGESTING_FILE)
            .path(loadModel.getTargetPath())
            .mimeType(loadModel.getMimeType())
            .description(loadModel.getDescription())
            .flightId(context.getFlightId());

        UUID objectId = fileDao.createFileStart(fsObject);
        workingMap.put(FileMapKeys.OBJECT_ID, objectId.toString());

        return StepResult.getStepResultSuccess();
    }

    @Override
    public StepResult undoStep(FlightContext context) {
        FlightMap inputParameters = context.getInputParameters();
        FileLoadModel loadModel = inputParameters.get(JobMapKeys.REQUEST.getKeyName(), FileLoadModel.class);
        String studyId = inputParameters.get(JobMapKeys.STUDY_ID.getKeyName(), String.class);
        UUID studyUUID = UUID.fromString(studyId);

        FSObject fsObject = fileDao.retrieveByPathNoThrow(studyUUID, loadModel.getTargetPath());
        if (fsObject != null) {
            // OK, some file exists. If this flight created it, then we delete it
            if (StringUtils.equals(fsObject.getFlightId(), context.getFlightId())) {
                fileDao.deleteFileForCreateUndo(fsObject.getObjectId(), context.getFlightId());
            }
        }
        return StepResult.getStepResultSuccess();
    }

}