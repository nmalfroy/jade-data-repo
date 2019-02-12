package bio.terra.dao;

import bio.terra.metadata.Study;
import bio.terra.metadata.StudyRelationship;
import bio.terra.metadata.StudyTableColumn;
import bio.terra.model.RelationshipTermModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class RelationshipDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    public RelationshipDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // part of a transaction propagated from StudyDao
    public void createStudyRelationships(Study study) {
        for (StudyRelationship rel : study.getRelationships()) {
            create(rel);
        }
    }

    protected void create(StudyRelationship studyRelationship) {
        String sql = "INSERT INTO study_relationship " +
                "(name, from_cardinality, to_cardinality, from_column, to_column) VALUES " +
                "(:name, :from_cardinality, :to_cardinality, :from_column, :to_column)";
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("name", studyRelationship.getName());
        params.addValue("from_cardinality", studyRelationship.getFromCardinality().toString());
        params.addValue("to_cardinality", studyRelationship.getToCardinality().toString());
        params.addValue("from_column", studyRelationship.getFrom().getId());
        params.addValue("to_column", studyRelationship.getTo().getId());
        UUIDHolder keyHolder = new UUIDHolder();
        jdbcTemplate.update(sql, params, keyHolder);
        UUID relationshipId = keyHolder.getId();
        studyRelationship.setId(relationshipId);
    }

    public void retrieve(Study study) {
        List<UUID> columnIds = new ArrayList<>();
        study.getTables().forEach(table ->
                table.getColumns().forEach(column -> columnIds.add(column.getId())));
        study.setRelationships(retrieveStudyRelationships(columnIds, study.getAllColumnsById()));
    }

    private List<StudyRelationship> retrieveStudyRelationships(
            List<UUID> columnIds, Map<UUID,
            StudyTableColumn> columns) {
        return jdbcTemplate.query(
                "SELECT id, name, from_cardinality, to_cardinality, from_column, to_column " +
                        "FROM study_relationship WHERE from_column IN (:columns) OR to_column IN (:columns)",
                new MapSqlParameterSource().addValue("columns", columnIds), (
                        rs, rowNum) -> new StudyRelationship()
                        .setId(UUID.fromString(rs.getString("id")))
                        .setName(rs.getString("name"))
                        .setFromCardinality(RelationshipTermModel.CardinalityEnum.fromValue(
                                rs.getString("from_cardinality")))
                        .setToCardinality(RelationshipTermModel.CardinalityEnum.fromValue(
                                rs.getString("to_cardinality")))
                        .setFrom(columns.get(UUID.fromString(rs.getString("from_column"))))
                        .setTo(columns.get(UUID.fromString(rs.getString("to_column")))));
    }
}
