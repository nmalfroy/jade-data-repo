package common.utils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import runner.config.TestUserSpecification;

public final class BigQueryUtils {
  private static final Logger logger = LoggerFactory.getLogger(BigQueryUtils.class);

  private BigQueryUtils() {}

  /**
   * Build the Big Query client object with application default credentials. THe client object is
   * newly created on each call to this method; it is not cached.
   *
   * @param googleProjectId the project where BigQuery will run queries
   * @return the BigQuery client object for this data project
   */
  public static BigQuery getClient(String googleProjectId) throws IOException {
    logger.debug("Fetching application default credentials and building BigQuery client object");

    GoogleCredentials applicationDefaultCredentials =
        AuthenticationUtils.getApplicationDefaultCredential();
    BigQuery bigQuery =
        BigQueryOptions.newBuilder()
            .setProjectId(googleProjectId)
            .setCredentials(applicationDefaultCredentials)
            .build()
            .getService();

    return bigQuery;
  }

  /**
   * Build the Big Query client object for the given test user and server specifications.
   *
   * @param testUser the test user whose credentials are supplied to the API client object
   * @param googleProjectId the project where BigQuery will run queries
   * @return the BigQuery client object for this user and data project
   */
  public static BigQuery getClientForTestUser(
      TestUserSpecification testUser, String googleProjectId) throws IOException {
    logger.debug(
        "Fetching credentials and building BigQuery client object for test user: {}",
        testUser.name);

    GoogleCredentials userCredential = AuthenticationUtils.getDelegatedUserCredential(testUser);
    BigQuery bigQuery =
        BigQueryOptions.newBuilder()
            .setProjectId(googleProjectId)
            .setCredentials(userCredential)
            .build()
            .getService();

    return bigQuery;
  }

  /**
   * Execute a query with the given BigQuery client object.
   *
   * @param bigQueryClient the BigQuery client object
   * @param query the query to run
   * @return the result of the BQ query
   * @throws InterruptedException from the bigQuery.query() method
   */
  public static TableResult queryBigQuery(BigQuery bigQueryClient, String query)
      throws InterruptedException {
    QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
    return bigQueryClient.query(queryConfig);
  }

  /**
   * Execute a query to check if a row that matches fieldName = fieldValue already exists. Use the
   * given BigQuery client object.
   *
   * @return true if the row already exists, false otherwise
   */
  public static boolean checkRowExists(
      BigQuery bigQueryClient,
      String projectId,
      String datasetName,
      String tableName,
      String fieldName,
      String fieldValue)
      throws InterruptedException {
    String tableRef = String.format("`%s.%s.%s`", projectId, datasetName, tableName);
    String queryForRow =
        String.format(
            "SELECT 1 FROM %s WHERE %s = '%s' LIMIT %s", tableRef, fieldName, fieldValue, 1);
    logger.info("queryForRow: {}", queryForRow);

    TableResult result = queryBigQuery(bigQueryClient, queryForRow);
    AtomicInteger numMatchedRows = new AtomicInteger();
    result.iterateAll().forEach(r -> numMatchedRows.getAndIncrement());
    if (numMatchedRows.get() > 0) {
      logger.debug(
          "Row already exists: {} = {}, Matches found: {}",
          fieldName,
          fieldValue,
          numMatchedRows.get());
    }

    return (numMatchedRows.get() > 0);
  }

  public static String getDatasetName(String datasetName) {
    return "datarepo_" + datasetName;
  }

  public static String buildSelectQuery(
      String project, String datasetName, String tableName, String select, Long limit) {
    String tableRef = String.format("`%s.%s.%s`", project, datasetName, tableName);
    String sqlQuery = String.format("SELECT %s FROM %s LIMIT %s", select, tableRef, limit);
    return sqlQuery;
  }
}
