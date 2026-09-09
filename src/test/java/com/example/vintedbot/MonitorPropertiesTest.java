package com.example.vintedbot;

// Traduit depuis le russe par Ayoub Lemsoudi.

import com.example.vintedbot.config.MonitorProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorPropertiesTest {

    @Test
    void defaultsUseConservativePollingAndBackoff() {
        MonitorProperties properties = new MonitorProperties();

        assertThat(properties.getIntervalMs()).isEqualTo(60_000);
        assertThat(properties.getBackoffMs()).isEqualTo(900_000);
    }
}
