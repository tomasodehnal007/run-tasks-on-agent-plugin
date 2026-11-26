package io.jenkins.plugins.runtasksonagent.execution;

import java.time.Duration;

/** Lets tests in other packages shorten the package-private timeout of {@link FreestyleRunListener}. */
public final class FreestyleRunListenerAccess {

    private FreestyleRunListenerAccess() {}

    /**
     * Sets {@link FreestyleRunListener#nodeBlocksTimeout}.
     *
     * @param timeout the new timeout
     * @return the previous timeout, to restore after the test
     */
    public static Duration nodeBlocksTimeout(Duration timeout) {
        Duration previous = FreestyleRunListener.nodeBlocksTimeout;
        FreestyleRunListener.nodeBlocksTimeout = timeout;
        return previous;
    }
}
