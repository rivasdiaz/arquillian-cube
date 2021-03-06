package org.arquillian.cube.openshift.impl.client;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftConfig;

import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.ApplicationScoped;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.event.suite.AfterSuite;

public class OpenShiftClientCreator {

    @Inject
    @ApplicationScoped
    private InstanceProducer<OpenShiftClient> openShiftClientProducer;

    public void createClient(@Observes CubeOpenShiftConfiguration cubeConfiguration) {
        // System.setProperty(Configs.OPENSHIFT_CONFIG_FILE_PROPERTY,
        // "./src/test/resources/config.yaml");
        System.setProperty("KUBERNETES_TRUST_CERT", "true");
        // override defaults for master and namespace
        final Config config = new ConfigBuilder()
                .withMasterUrl(cubeConfiguration.getOriginServer().toString())
                .withNamespace(cubeConfiguration.getNamespace())
                .withTrustCerts(true)
                .accept(new TypedVisitor<ConfigBuilder>() {
                    @Override
                    public void visit(ConfigBuilder b) {
                        b.withNoProxy(b.getNoProxy() == null ? new String[0] : b.getNoProxy());
                    }
                }).build();

        openShiftClientProducer.set(createClient(config, cubeConfiguration.getNamespace(),
                cubeConfiguration.shouldKeepAliveGitServer()));
    }

    public void clean(@Observes AfterSuite event, OpenShiftClient client) throws Exception {
        client.shutdown();
    }

    public OpenShiftClient createClient(Config openShiftConfig, String namespace, boolean keepAliveGitServer) {
        return new OpenShiftClient(openShiftConfig, namespace, keepAliveGitServer);
    }
}
