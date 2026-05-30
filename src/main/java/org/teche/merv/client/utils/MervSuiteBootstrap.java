package org.teche.merv.client.utils;

import org.teche.merv.client.MervClient;
import org.teche.merv.client.dto.TestSuiteRequest;
import org.teche.merv.client.dto.TestSuiteResponse;
import org.teche.merv.client.exception.MervClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds test suite requests from {@code merv.properties}. When {@code merv.parent_hierarchy}
 * is omitted, {@code hierarchy_id} is left unset so the API places the suite in the user's
 * default Personal-workspace project (Default Project).
 */
public final class MervSuiteBootstrap {

    private MervSuiteBootstrap() {
    }

    public static TestSuiteRequest buildNewSuiteRequest(Properties props, String defaultTitle) {
        TestSuiteRequest testSuite = new TestSuiteRequest();
        testSuite.setTitle(props.getProperty("merv.regression_suite", defaultTitle));
        applyOptionalHierarchy(testSuite, props);
        testSuite.setSprint(props.getProperty("merv.sprint"));
        applyTags(testSuite, props);
        return testSuite;
    }

    /**
     * Resolves suite id from append env/property, alias, or creates a new suite from properties.
     */
    public static UUID resolveSuiteId(MervClient client, Properties props, String defaultTitle)
            throws MervClientException {
        String appendSuite = firstNonBlank(
                System.getenv("merv.append_suite"), props.getProperty("merv.append_suite"));
        if (appendSuite != null && !appendSuite.isBlank()) {
            return UUID.fromString(appendSuite.trim());
        }

        String suiteAlias = firstNonBlank(
                System.getenv("merv.append_suite_alias"), props.getProperty("merv.append_suite_alias"));
        if (suiteAlias != null && !suiteAlias.isBlank()) {
            return client.getTestSuiteIdByAlias(suiteAlias.trim());
        }

        TestSuiteResponse res = client.createTestSuite(buildNewSuiteRequest(props, defaultTitle));
        UUID id = res != null ? res.getId() : null;
        if (id == null) {
            throw new MervClientException(
                    "Test suite was created but the API returned no suite id (expected uuid in response)");
        }
        return id;
    }

    public static void applyOptionalHierarchy(TestSuiteRequest testSuite, Properties props) {
        if (props == null) {
            return;
        }
        String parentHierarchy = props.getProperty("merv.parent_hierarchy");
        if (parentHierarchy != null && !parentHierarchy.trim().isEmpty()) {
            testSuite.setHierarchyId(UUID.fromString(parentHierarchy.trim()));
        }
    }

    public static void applyTags(TestSuiteRequest testSuite, Properties props) {
        if (props == null) {
            return;
        }
        String tags = props.getProperty("merv.tags");
        if (tags == null || tags.isBlank()) {
            return;
        }
        List<String> tagList = Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (!tagList.isEmpty()) {
            testSuite.setTags(tagList);
        }
    }

    public static boolean usesDefaultProject(Properties props) {
        if (props == null) {
            return true;
        }
        String parentHierarchy = props.getProperty("merv.parent_hierarchy");
        return parentHierarchy == null || parentHierarchy.trim().isEmpty();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
