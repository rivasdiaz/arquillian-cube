package org.arquillian.cube.kubernetes.impl.enricher;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.arquillian.cube.kubernetes.annotations.Port;
import org.arquillian.cube.kubernetes.annotations.PortForward;
import org.arquillian.cube.kubernetes.annotations.Scheme;
import org.arquillian.cube.kubernetes.api.Session;
import org.arquillian.cube.kubernetes.api.SessionListener;
import org.arquillian.cube.kubernetes.impl.portforward.PortForwarder;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A {@link ResourceProvider} for {@link io.fabric8.kubernetes.api.model.ServiceList}.
 * It refers to services that have been created during the current session.
 */
public class UrlResourceProvider extends AbstractKubernetesResourceProvider {


    private static final String SERVICE_PATH = "api.service.kubernetes.io/path";
    private static final String SERVICE_SCHEME = "api.service.kubernetes.io/scheme";

    private static final String DEFAULT_SCHEME = "http";
    private static final String DEFAULT_PATH = "/";

    private static final String POD = "Pod";
    private static final String LOCALHOST = "localhost";

    private static final Random RANDOM = new Random();

    @Override
    public boolean canProvide(Class<?> type) {
        return URL.class.isAssignableFrom(type);
    }

    @Override
    public Object lookup(ArquillianResource resource, Annotation... qualifiers) {
        String name = getName(qualifiers);
        String namespace = getSession().getNamespace();
        Service service = getClient().services().inNamespace(namespace).withName(name).get();
        String scheme = getScheme(service, qualifiers);
        String path = getPath(service, qualifiers);

        String ip = service.getSpec().getClusterIP();
        int port = 0;

        if (isPortForwardingEnabled(qualifiers)) {
            Pod pod = getRandomPod(getClient(), name, namespace);
            int containerPort = getContainerPort(service, qualifiers);
            port = portForward(getClient(), getSession(), pod.getMetadata().getName(), containerPort);
            ip = LOCALHOST;
        } else {
            port = getPort(service, qualifiers);
        }

        try {
            if (port > 0) {
                return new URL(scheme, ip, port, path);
            } else {
                return new URL(scheme, ip, path);
            }
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Cannot resolve URL for service: [" + name + "] in namespace:[" + namespace + "].");
        }
    }

    private int portForward(KubernetesClient client, Session session, String podName, int targetPort) {
        return portForward(client, session, podName, findRandomFreeLocalPort(), targetPort);
    }

    private int portForward(KubernetesClient client, Session session, String podName, int sourcePort, int targetPort) {
        try {
            final PortForwarder portForwarder = new PortForwarder(getClient().getConfiguration(), podName);
            portForwarder.forwardPort(sourcePort, targetPort);
            session.addListener(new SessionListener() {
                @Override
                public void onClose() {
                    portForwarder.close();
                }
            });
            return sourcePort;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * @param qualifiers    The qualifiers
     * @return              true if qualifiers contain the `PortForward` qualifier.
     */
    private static boolean isPortForwardingEnabled(Annotation... qualifiers) {
        for (Annotation q : qualifiers) {
            if (q instanceof PortForward) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the {@link ServicePort} of the {@link Service} that matches the qualifiers
     * @param service       The target service.
     * @param qualifiers    The qualifiers.
     * @return
     */
    private static ServicePort findQualifiedServicePort(Service service, Annotation... qualifiers) {
        Port port = null;
        for (Annotation q : qualifiers) {
            if (q instanceof Port) {
                port = (Port) q;
            }
        }
        if (service.getSpec() != null && service.getSpec().getPorts() != null) {
            for (ServicePort servicePort : service.getSpec().getPorts()) {
                //if no port name is specified we will use the first.
                if (port == null) {
                    return servicePort;
                }

                if (servicePort.getName() != null && servicePort.getName().equals(port.name())) {
                    return servicePort;
                }
            }
        }
        return null;
    }

    /**
     * Find the the qualified container port of the target service
     * Uses java annotations first or returns the container port.
     * @param service       The target service.
     * @param qualifiers    The set of qualifiers.
     * @return              Returns the resolved containerPort of '0' as a fallback.
     */
    private static int getPort(Service service, Annotation... qualifiers) {
        for (Annotation q : qualifiers) {
            if (q instanceof Port) {
                Port port = (Port) q;
                if (port.value() > 0) {
                    return port.value();
                }
            }
        }

        ServicePort servicePort = findQualifiedServicePort(service, qualifiers);
        if (servicePort != null) {
            return servicePort.getPort();
        }
        return 0;
    }

    /**
     * Find the the qualfied container port of the target service
     * Uses java annotations first or returns the container port.
     * @param service       The target service.
     * @param qualifiers    The set of qualifiers.
     * @return              Returns the resolved containerPort of '0' as a fallback.
     */
    private static int getContainerPort(Service service, Annotation... qualifiers) {
        for (Annotation q : qualifiers) {
            if (q instanceof Port) {
                Port port = (Port) q;
                if (port.value() > 0) {
                    return port.value();
                }
            }
        }

        ServicePort servicePort = findQualifiedServicePort(service, qualifiers);
        if (servicePort != null) {
            return servicePort.getTargetPort().getIntVal();
        }
        return 0;
    }

    /**
     * Find the scheme to use to connect to the service.
     * Uses java annotations first and if not found, uses kubernetes annotations on the service object.
     * @param service       The target service.
     * @param qualifiers    The set of qualifiers.
     * @return              Returns the resolved scheme of 'http' as a fallback.
     */
    private static String getScheme(Service service, Annotation... qualifiers) {
        for (Annotation q : qualifiers) {
            if (q instanceof Scheme) {
                return ((Scheme) q).value();
            }
        }

        if (service.getMetadata() != null && service.getMetadata().getAnnotations() != null) {
            String s = service.getMetadata().getAnnotations().get(SERVICE_SCHEME);
            if (s != null && s.isEmpty()) {
                return s;
            }
        }

        return DEFAULT_SCHEME;
    }

    /**
     * Find the path to use .
     * Uses java annotations first and if not found, uses kubernetes annotations on the service object.
     * @param service       The target service.
     * @param qualifiers    The set of qualifiers.
     * @return              Returns the resolved path of '/' as a fallback.
     */
    private static String getPath(Service service, Annotation... qualifiers) {
        for (Annotation q : qualifiers) {
            if (q instanceof Scheme) {
                return ((Scheme) q).value();
            }
        }

        if (service.getMetadata() != null && service.getMetadata().getAnnotations() != null) {
            String s = service.getMetadata().getAnnotations().get(SERVICE_SCHEME);
            if (s != null && s.isEmpty()) {
                return s;
            }
        }
        return DEFAULT_PATH;
    }

    /**
     * Get a random pod that provides the specified service in the specified namespace.
     * @param client           The client instance to use.
     * @param name             The name of the service.
     * @param namespace        The namespace of the service.
     * @return                 The pod or null if no pod matches.
     */
    private static Pod getRandomPod(KubernetesClient client, String name, String namespace) {
        Endpoints endpoints = client.endpoints().inNamespace(namespace).withName(name).get();
        List<String> pods = new ArrayList<>();
        if (endpoints != null) {
            for (EndpointSubset subset : endpoints.getSubsets()) {
                for (EndpointAddress address : subset.getAddresses()) {
                    if (address.getTargetRef() != null && POD.equals(address.getTargetRef().getKind())) {
                        String pod = address.getTargetRef().getName();
                        if (pod != null && !pod.isEmpty()) {
                            pods.add(pod);
                        }
                    }
                }
            }
        }
        if (pods.isEmpty()) {
            return null;
        } else {
            String chosen = pods.get(RANDOM.nextInt(pods.size()));
            return client.pods().inNamespace(namespace).withName(chosen).get();
        }
    }

    /**
     * @return A random free local port.
     */
    private static final int findRandomFreeLocalPort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
    }
}
