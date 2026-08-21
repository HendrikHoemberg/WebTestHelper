package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/** Stable-per-JVM lease owner, so a restarted container never matches its own stale leases. */
@Component
public class WorkerIdentity {

    private final String name;

    public WorkerIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        this.name = host + "/" + UUID.randomUUID();
    }

    public String name() {
        return name;
    }
}
