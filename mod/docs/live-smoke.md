# DebugBridge MCP Live Smoke

This is the live verification path for DebugBridge on exact 26.1 and exact
26.2. Use MCP tool calls for Minecraft actions; do not
route those actions through shell-based Minecraft control wrappers.

## Prepare

There is no one-size-fits-all live-smoke wrapper checked in. Build/deploy the
target version first, then use the MCP/runtime tools and the Node smoke helpers
below.

For the local exact-26.1 Prism Launcher instance on macOS, use the repo-root
deploy script:

```bash
JAVA_HOME_26_1=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  PRISM_INSTANCE_NAME=26.1 \
  ./build-and-deploy-26.1.sh
```

Do not restart a live Minecraft/Prism client while the user is on a ride unless
they explicitly approve the restart.

After the client is running and the bridge is reachable, use the repo smoke
helpers from the DebugBridge root:

```bash
node tools/smoke-test.mjs --port 9876 --version 26.1 --include-textures
node tools/record-video-smoke.mjs --port 9876 --version 26.1
```

## MCP Sequence

1. Confirm the bridge is reachable with `mc_snapshot` or `mc_execute`.
   If several clients are running, confirm the MCDEV connection/port is the
   intended exact-26.1 instance before trusting runtime results.
2. Open or enter a world with `mc_execute`.

   The known-good Groovy shape is:

   ```groovy
   def Minecraft = java.type('net.minecraft.client.Minecraft')
   def Thread = java.type('java.lang.Thread')
   def mc = Minecraft.getInstance()
   mc.options.pauseOnLostFocus = false
   def flows = mc.createWorldOpenFlows()
   flows.openWorld('New World', Thread())
   return 'opening world from ' + Thread.currentThread().getName()
   ```

3. Exercise native runtime tools: `mc_snapshot`, `mc_screenshot`,
   `mc_screen_inspect`, `mc_chat_history`, and focused `mc_execute` calls.
4. For exact 26.1, include item texture coverage with `mc_get_item_texture`
   or the repo smoke tool's `--include-textures` mode so placeholder fallbacks
   do not silently pass.
