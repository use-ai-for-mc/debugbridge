# DebugBridge Web UI

A local web frontend for inspecting and interacting with Minecraft through the DebugBridge mod.

## Features

### Phase 1 (Current)
- **Connection Panel** - Connect to running Minecraft instances
- **Groovy Console** - Execute Groovy scripts with history
- **Object Inspector** - Explore Java objects as expandable trees
- **Dashboard** - View player stats, position, world info
- **Quick Actions** - One-click access to common objects (Minecraft, Player, World)
- **Pinned Objects** - Save frequently accessed objects

### Planned
- **Phase 2**: Entity browser, inventory viewer, live updates
- **Phase 3**: Object graph visualization, mini-map
- **Phase 4**: Script library, watch expressions, method tracer UI

## Development

```bash
# Install dependencies
npm install

# Start dev server (hot reload)
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

## Usage

1. Start Minecraft with the DebugBridge mod installed
2. Run `npm run dev` to start the web UI
3. Open http://localhost:5173 in your browser
4. Click "Connect" to connect to Minecraft

## Tech Stack

- Vue 3 + TypeScript
- Vite
- Tailwind CSS v4
- Pinia (state management)

## Architecture

```
src/
├── components/
│   ├── layout/          # Header, Sidebar
│   ├── console/         # Groovy console + editor
│   ├── inspector/       # Entities, blocks, inventory, browser, JSON/SNBT trees
│   └── dashboard/       # Player snapshot
├── services/
│   ├── bridge.ts        # WebSocket client
│   ├── groovy-helpers.ts # Groovy code generation helpers
│   └── snbt.ts          # SNBT parsing
├── stores/              # Pinia: connection, console, inspector, entities,
│                        # blocks, inventory, browser
└── types/
    └── index.ts         # TypeScript types
```
