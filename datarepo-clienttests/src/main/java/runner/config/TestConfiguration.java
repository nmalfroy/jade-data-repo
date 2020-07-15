package runner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import utils.FileUtils;

public class TestConfiguration implements SpecificationInterface {
  public String name;
  public String description = "";
  public String serverSpecificationFile;
  public String billingAccount;
  public List<String> testUserFiles;

  public ServerSpecification server;
  public KubernetesSpecification kubernetes;
  public ApplicationSpecification application;
  public List<TestScriptSpecification> testScripts;
  public List<TestUserSpecification> testUsers = new ArrayList<>();

  public static final String resourceDirectory = "configs";

  public TestConfiguration() {}

  public static TestConfiguration fromJSONFile(String resourceFileName) throws Exception {
    // use Jackson to map the stream contents to a TestConfiguration object
    ObjectMapper objectMapper = new ObjectMapper();

    // read in the test config file
    InputStream inputStream =
        FileUtils.getJSONFileHandle(resourceDirectory + "/" + resourceFileName);
    TestConfiguration testConfig = objectMapper.readValue(inputStream, TestConfiguration.class);

    // read in the server file
    inputStream =
        FileUtils.getJSONFileHandle(
            ServerSpecification.resourceDirectory + "/" + testConfig.serverSpecificationFile);
    testConfig.server = objectMapper.readValue(inputStream, ServerSpecification.class);

    // read in the test user files and the nested service account files
    for (String testUserFile : testConfig.testUserFiles) {
      inputStream =
          FileUtils.getJSONFileHandle(TestUserSpecification.resourceDirectory + "/" + testUserFile);
      TestUserSpecification testUser =
          objectMapper.readValue(inputStream, TestUserSpecification.class);

      inputStream =
          FileUtils.getJSONFileHandle(
              ServiceAccountSpecification.resourceDirectory
                  + "/"
                  + testUser.delegatorServiceAccountFile);
      testUser.delegatorServiceAccount =
          objectMapper.readValue(inputStream, ServiceAccountSpecification.class);
      testConfig.testUsers.add(testUser);
    }

    // instantiate default kubernetes, application specification objects, if null
    if (testConfig.kubernetes == null) {
      testConfig.kubernetes = new KubernetesSpecification();
    }
    if (testConfig.application == null) {
      testConfig.application = new ApplicationSpecification();
    }

    return testConfig;
  }

  /**
   * Validate the object read in from the JSON file. This method also fills in additional properties
   * of the objects, for example by parsing the string values in the JSON object.
   */
  public void validate() {
    server.validate();
    kubernetes.validate();
    application.validate();

    for (TestScriptSpecification testScript : testScripts) {
      testScript.validate();
    }

    for (TestUserSpecification testUser : testUsers) {
      testUser.validate();
    }
  }

  public void display() {
    System.out.println("Test configuration: " + name);
    System.out.println("  Description: " + description);

    System.out.println();
    server.display();

    System.out.println();
    kubernetes.display();

    System.out.println();
    application.display();

    System.out.println();
    System.out.println("  Billing account: " + billingAccount);

    for (TestScriptSpecification testScript : testScripts) {
      System.out.println();
      testScript.display();
    }

    for (TestUserSpecification testUser : testUsers) {
      System.out.println();
      testUser.display();
    }

    System.out.println();
  }
}