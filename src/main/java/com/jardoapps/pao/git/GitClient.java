package com.jardoapps.pao.git;

import java.util.Optional;

/** The git operations the plugin needs, kept behind an interface so runs can be faked in tests. */
public interface GitClient {

    /** Sets the local user identity used for commits. */
    void configureUser(String name, String email);

    /** True if the working tree has uncommitted changes. */
    boolean hasUncommittedChanges();

    /** Commits all tracked modifications. Does nothing if the tree is clean. */
    void commitAll(String message);

    /** Pushes HEAD to the given branch on {@code origin}. */
    void push(String branch);

    /** The branch currently checked out, empty in detached HEAD state or outside a repository. */
    Optional<String> currentBranch();
}
