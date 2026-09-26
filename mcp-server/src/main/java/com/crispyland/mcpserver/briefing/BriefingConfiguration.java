package com.crispyland.mcpserver.briefing;

import com.crispyland.mcpserver.google.GoogleProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The three collaborators that need a decision made about them before they can be constructed: a
 * clock in the right zone, a store pointed at a file, and a narrator that may have no key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BriefingProperties.class)
public class BriefingConfiguration {

    /**
     * The system clock, fixed to the Google zone rather than the JVM's.
     * <p>
     * This is the bug that would otherwise ship to the VPS and nowhere else: the Hetzner image is
     * UTC, so a default-zone clock would make "today" flip at 08:00 Shanghai and the morning briefing
     * would describe yesterday. Taking the zone from {@code google.time-zone} means one setting
     * decides both which events are fetched and which day they are filed under.
     */
    @Bean
    public Clock briefingClock(GoogleProperties google) {
        return Clock.system(google.zone());
    }

    @Bean
    public BriefingStore briefingStore(ObjectMapper mapper, BriefingProperties properties) {
        return new BriefingStore(mapper, properties.path(), properties.keepRuns(),
                properties.initialSchedule());
    }

    /**
     * Its own {@link RestClient} with a short read timeout, not a shared one. The scheduler has a
     * single thread: a narrator hanging on a provider that accepted the connection and then went
     * quiet is a job that has stopped collecting, and the timeout is the only thing that ends it.
     */
    @Bean
    public BriefingNarrator briefingNarrator(ObjectMapper mapper, BriefingProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.narrator().timeout());
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new GroqBriefingNarrator(restClient, mapper, properties.narrator());
    }
}
