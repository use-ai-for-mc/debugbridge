package com.debugbridge.core.script;

import groovy.lang.Binding;
import groovy.lang.MissingPropertyException;

/**
 * Script {@link Binding} that resolves {@code mc}, {@code player} and
 * {@code level} dynamically on each access, so they always reflect current game
 * state (they change across world loads / respawns) without the script having
 * to re-call {@code Minecraft.getInstance()}. Everything else uses the normal
 * binding map, which is shared across calls to give Lua-like persistent state.
 */
public class ScriptBinding extends Binding {
    private final GroovyBridge bridge;

    public ScriptBinding(GroovyBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public Object getVariable(String name) {
        switch (name) {
            case "mc":
                return resolve(name, bridge::mcInstance);
            case "player":
                return resolve(name, bridge::playerInstance);
            case "level":
                return resolve(name, bridge::levelInstance);
            default:
                return super.getVariable(name);
        }
    }

    @Override
    public boolean hasVariable(String name) {
        return name.equals("mc") || name.equals("player") || name.equals("level") || super.hasVariable(name);
    }

    private interface Resolver {
        Object get() throws Exception;
    }

    private Object resolve(String name, Resolver r) {
        try {
            return r.get();
        } catch (Exception e) {
            // No Minecraft on classpath (tests) or not yet in-world: behave like an undefined variable.
            throw new MissingPropertyException(name, Object.class);
        }
    }
}
