package bio.terra.metadata;

import java.util.UUID;

public class StudyTableColumn {
    private String name;
    private String type;
    private UUID id;
    private transient StudyTable inTable;

    public String getName() {
        return name;
    }

    public StudyTableColumn name(String name) {
        this.name = name;
        return this;
    }

    public String getType() {
        return type;
    }

    public StudyTableColumn type(String type) {
        this.type = type;
        return this;
    }

    public UUID getId() {
        return id;
    }

    public StudyTableColumn id(UUID id) {
        this.id = id;
        return this;
    }

    public StudyTable getInTable() {
        return inTable;
    }

    public StudyTableColumn inTable(StudyTable inTable) {
        this.inTable = inTable;
        return this;
    }

}
