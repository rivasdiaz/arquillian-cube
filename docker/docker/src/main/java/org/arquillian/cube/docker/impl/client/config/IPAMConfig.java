package org.arquillian.cube.docker.impl.client.config;

public class IPAMConfig {

    private String subnet;
    private String ipRange;
    private String gateway;

    public String getGateway() {
        return gateway;
    }

    public String getIpRange() {
        return ipRange;
    }

    public String getSubnet() {
        return subnet;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public void setIpRange(String ipRange) {
        this.ipRange = ipRange;
    }

    public void setSubnet(String subnet) {
        this.subnet = subnet;
    }
}
