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
}
