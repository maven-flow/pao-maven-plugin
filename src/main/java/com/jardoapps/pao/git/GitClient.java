package com.jardoapps.pao.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The git operations the plugin needs, kept behind an interface so runs can be faked in tests. */
public interface GitClient {

    /**
     * Commits exactly the given files under the given identity. Anything else in the
     * working tree - an earlier pipeline step's output, a developer's own edits - is
     * deliberately left out, so the commit matches its message.
     */
    void commit(String message, List<Path> files, String userName, String userEmail);

    /** Pushes HEAD to the given branch on {@code origin}. */
    void push(String branch);

    /** The branch currently checked out, empty in detached HEAD state or outside a repository. */
    Optional<String> currentBranch();
}
