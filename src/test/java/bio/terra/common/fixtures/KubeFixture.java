package bio.terra.common.fixtures;

import bio.terra.service.kubernetes.KubeService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.ClientBuilder;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import static bio.terra.service.kubernetes.KubeConstants.KUBE_NAMESPACE_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static bio.terra.service.kubernetes.KubeConstants.API_POD_FILTER;

@Component
public final class KubeFixture {

    @Autowired
    private KubeFixture() {
    }

    @Autowired private KubeService kubeService;

    public List<String> listAllPods() {
        // Take as input the kubconfig.txt
        String kubeConfigPath = "~/.kube/config";
        ApiClient client =
            ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();
        ApiClient client = ClientBuilder.cluster().build();
        Configuration.setDefaultApiClient(client);
        CoreV1Api api = new CoreV1Api();


        V1PodList list = api.listNamespacedPod(namespace, null, null, null, null, null, null, null, null, null);
        for (V1Pod item : list.getItems()) {
            String podName = item.getMetadata().getName();
            if (StringUtils.contains(podName, API_POD_FILTER)) {
                podList.add(podName);
                logger.info("KubeService: Pod name {}", podName);
            }
        }
        return podList;
    }

    private String readFileIntoString(String path) {
        try {
            byte[] encoded = Files.readAllBytes(Paths.get(path));
            return new String(encoded, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to read file: " + path + "; ", e);
            return null;
        }
    }
}
