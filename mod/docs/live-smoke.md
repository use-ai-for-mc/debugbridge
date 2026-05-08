# DebugBridge MCP Live Smoke

This is the live verification path for DebugBridge on the 26.2 development
snapshot. Use MCP tool calls for Minecraft actions; do not route those actions
through shell-based Minecraft control wrappers.

## Prepare

From the DebugBridge root:

```powershell
.\tools\debugbridge-live-smoke.ps1
```

This builds the affected DebugBridge artifacts and copies the 26.2 Fabric jar to
the render mod's `run/mods` directory.

To start the render mod client from the script:

```powershell
.\tools\debugbridge-live-smoke.ps1 -StartClient
```

## MCP Sequence

1. Confirm the bridge is reachable with `mc_snapshot` or `mc_execute`.
2. Open or enter a world with `mc_execute`.

   The known-good Lua shape is:

   ```lua
   local Minecraft = java.import('net.minecraft.client.Minecraft')
   local Thread = java.import('java.lang.Thread')
   local mc = Minecraft.getInstance()
   mc.options.pauseOnLostFocus = false
   local flows = mc:createWorldOpenFlows()
   flows:openWorld('New World', java.new(Thread))
   return 'opening world from ' .. Thread:currentThread():getName()
   ```

3. Exercise native runtime tools: `mc_snapshot`, `mc_screenshot`,
   `mc_screen_inspect`, `mc_chat_history`, and focused `mc_execute` calls.
