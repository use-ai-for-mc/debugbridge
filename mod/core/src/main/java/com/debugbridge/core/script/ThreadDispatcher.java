package com.debugbridge.core.script;

import java.util.concurrent.Callable;

/**
 * Dispatches a task onto the Minecraft client (game) thread and blocks for the
 * result. Reflective field reads and method invocations on live game objects
 * must run on that thread; each version module supplies an implementation that
 * posts to {@code Minecraft.execute(...)}.
 */
public interface ThreadDispatcher {
    <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception;
}
