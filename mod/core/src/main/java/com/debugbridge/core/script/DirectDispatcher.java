package com.debugbridge.core.script;

import java.util.concurrent.Callable;

/**
 * A {@link ThreadDispatcher} that runs tasks directly on the calling thread.
 * Used in tests, where there is no Minecraft game thread to post to.
 */
public class DirectDispatcher implements ThreadDispatcher {
    @Override
    public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
        return task.call();
    }
}
