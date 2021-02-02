package org.spiget.resourcemanagerfetcher;

import com.google.gson.JsonObject;
import org.inventivetalent.metrics.IntervalFlusher;
import org.inventivetalent.metrics.Metrics;

import java.util.concurrent.TimeUnit;

public class SpigetMetrics {

    protected Metrics metrics;

    public SpigetMetrics(JsonObject config) {
        this.metrics = new Metrics(config.get("metrics.url").getAsString(), config.get("metrics.user").getAsString(), config.get("metrics.password").getAsString());
        this.metrics.getInflux().setDatabase("spiget");
        this.metrics.setFlusher(new IntervalFlusher(this.metrics, 1, TimeUnit.MINUTES));
    }

}
