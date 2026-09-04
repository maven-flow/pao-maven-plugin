package com.jardoapps.pao;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.maven.plugin.logging.Log;

import com.jardoapps.pao.git.GitClient;

/** Works out which branch the build is running on. */
public final class BranchDetector {

    /** CI environment variables that carry the branch name, in priority order. */
    private static final Map<String, String> CI_VARIABLES = new LinkedHashMap<>();

    static {
        // On pull_request / pull_request_target events GITHUB_REF is refs/pull/<n>/merge,
        // making GITHUB_REF_NAME the synthetic '<n>/merge' rather than a branch. That
        // would derive a version nobody can merge back and then push to a ref the
        // forge rejects. GITHUB_HEAD_REF carries the real source branch and is set
        // only for pull-request events, so checking it first is safe on push builds.
        CI_VARIABLES.put("GITHUB_HEAD_REF", "GitHub Actions (pull request)");
        CI_VARIABLES.put("GITHUB_REF_NAME", "GitHub Actions");
        CI_VARIABLES.put("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME", "GitLab CI/CD (merge request)");
        CI_VARIABLES.put("CI_COMMIT_REF_NAME", "GitLab CI/CD");
        CI_VARIABLES.put("BITBUCKET_BRANCH", "Bitbucket Pipelines");
        CI_VARIABLES.put("CIRCLE_BRANCH", "CircleCI");
        CI_VARIABLES.put("TRAVIS_BRANCH", "Travis CI");
    }

    private BranchDetector() {
    }

    /**
     * Returns the configured branch name, else the first CI variable that is set,
     * else the branch git reports.
     *
     * @throws PaoException if the branch cannot be determined
     */
    public static String detect(String configured, UnaryOperator<String> environment, GitClient git, Log log) {
        if (configured != null && !configured.isBlank()) {
            log.info("Branch name provided: " + configured);
            return configured;
        }

        for (Map.Entry<String, String> variable : CI_VARIABLES.entrySet()) {
            String value = environment.apply(variable.getKey());
            if (value != null && !value.isBlank()) {
                log.info("Detected " + variable.getValue() + ", branch: " + value);
                return value;
            }
        }

        return git.currentBranch()
                .map(branch -> {
                    log.info("Detected local git, branch: " + branch);
                    return branch;
                })
                .orElseThrow(() -> new PaoException(
                        "Could not detect the branch name. Set it with -Dpao.branchName=<branch>."));
    }
}
