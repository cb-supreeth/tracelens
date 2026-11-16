package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationStatusTest {
    @Test void distinguishesConfigurationInstallationTransformationAndFailure() {
        AgentStatus.configureIntegration("jdbc", false);
        assertEquals("disabled", AgentStatus.integrations().get("jdbc").get("state"));
        AgentStatus.configureIntegration("jdbc", true);
        AgentStatus.integrationInstalled("jdbc");
        assertEquals("awaiting-class-load", AgentStatus.integrations().get("jdbc").get("state"));
        AgentStatus.integrationTransformed("jdbc");
        assertEquals("transformation-observed", AgentStatus.integrations().get("jdbc").get("state"));
        AgentStatus.integrationFailed("jdbc");
        assertEquals("degraded", AgentStatus.integrations().get("jdbc").get("state"));
        AgentStatus.configureIntegration("untrusted-dynamic-key", true);
        assertFalse(AgentStatus.integrations().containsKey("untrusted-dynamic-key"));
        AgentStatus.configureIntegration("jdbc", false);
    }
}
