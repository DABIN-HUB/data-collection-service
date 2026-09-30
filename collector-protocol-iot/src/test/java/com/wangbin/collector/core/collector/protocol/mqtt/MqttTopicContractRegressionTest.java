package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.core.config.validator.MqttConfigurationContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttTopicContractRegressionTest {

    @Test
    void exactAndSingleLevelFiltersRespectLevelBoundaries() {
        assertTrue(MqttTopicFilterMatcher.matches("factory/line1/temp", "factory/line1/temp"));
        assertFalse(MqttTopicFilterMatcher.matches("factory/line1/temp", "factory/line1/temp/extra"));
        assertFalse(MqttTopicFilterMatcher.matches("factory/line1/temp", "factory/line2/temp"));
        assertTrue(MqttTopicFilterMatcher.matches("factory/+/temp", "factory/line1/temp"));
        assertFalse(MqttTopicFilterMatcher.matches("factory/+/temp", "factory/line1/room/temp"));
        assertFalse(MqttTopicFilterMatcher.matches("factory/+/temp", "factory/temp"));
    }

    @Test
    void multiLevelFilterMatchesZeroOrMoreLevels() {
        assertTrue(MqttTopicFilterMatcher.matches("factory/#", "factory"));
        assertTrue(MqttTopicFilterMatcher.matches("factory/#", "factory/line1/temp"));
        assertFalse(MqttTopicFilterMatcher.matches("factory/#", "factories/line1"));
        assertTrue(MqttTopicFilterMatcher.matches("#", "factory/line1"));
    }

    @Test
    void emptyTopicLevelsAreSignificantAndCanBeMatchedByPlus() {
        assertTrue(MqttTopicFilterMatcher.matches("factory/+/temp", "factory//temp"));
        assertTrue(MqttTopicFilterMatcher.matches("/+/", "//"));
        assertTrue(MqttTopicFilterMatcher.matches("factory/", "factory/"));
        assertFalse(MqttTopicFilterMatcher.matches("factory", "factory/"));
    }

    @Test
    void rootWildcardsDoNotMatchSystemTopics() {
        assertFalse(MqttTopicFilterMatcher.matches("#", "$SYS/broker"));
        assertFalse(MqttTopicFilterMatcher.matches("+/broker", "$SYS/broker"));
        assertTrue(MqttTopicFilterMatcher.matches("$SYS/#", "$SYS/broker"));
        assertTrue(MqttTopicFilterMatcher.matches("$SYS/+", "$SYS/broker"));
    }

    @Test
    void malformedFiltersAndPublishTopicsAreRejected() {
        for (String filter : new String[]{"", "a+", "a/+suffix", "a/#/b", "a/b#", "a\u0000b"}) {
            assertThrows(IllegalArgumentException.class, () -> MqttTopicFilterMatcher.validateFilter(filter), filter);
            assertThrows(IllegalArgumentException.class, () -> MqttConfigurationContract.filter(filter), filter);
        }
        assertThrows(IllegalArgumentException.class, () -> MqttTopicFilterMatcher.validateFilter(null));
        for (String topic : new String[]{"", "a/+", "a/#", "a\u0000b"}) {
            assertThrows(IllegalArgumentException.class, () -> MqttTopicFilterMatcher.validatePublishTopic(topic), topic);
            assertThrows(IllegalArgumentException.class, () -> MqttConfigurationContract.publishTopic(topic), topic);
        }
        assertThrows(IllegalArgumentException.class, () -> MqttTopicFilterMatcher.validatePublishTopic(null));
        assertEquals("factory//temp", MqttTopicFilterMatcher.validatePublishTopic("factory//temp"));
    }

    @Test
    void sharedContractMatcherAgreesOnExactWildcardEmptyAndSystemCases() {
        String[][] cases = {
                {"factory/temp", "factory/temp"}, {"factory/temp", "factory/other"},
                {"factory/+", "factory/"}, {"factory/+", "factory/a/b"},
                {"factory/#", "factory"}, {"factory/#", "factory/a/b"},
                {"#", "$SYS/status"}, {"$SYS/#", "$SYS/status"}
        };
        for (String[] entry : cases) {
            assertEquals(MqttTopicFilterMatcher.matches(entry[0], entry[1]),
                    MqttConfigurationContract.matches(entry[0], entry[1]), entry[0] + " -> " + entry[1]);
        }
    }
}
