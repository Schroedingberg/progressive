# Romance Progression PWA

A local-first Progressive Web App for workout tracking, built with ClojureScript.

## Features

- **Offline-first**: Works without internet connection
- **Local storage**: All data persisted in browser localStorage
- **Event sourcing**: Immutable event log with DataScript
- **Plan templates**: Customizable workout plans

## Tech Stack

- **ClojureScript** with shadow-cljs
- **Reagent** for React-based UI
- **DataScript** for in-browser database
- **Pico CSS** for minimal styling
- **Service Worker** for offline support

## Development

### Prerequisites

- Node.js (v18+)
- Clojure CLI tools

### Setup

```bash
# Install dependencies
npm install

# Start development server
npm run dev
```

The app will be available at http://localhost:3000

### Commands

| Command | Description |
|---------|-------------|
| `npm run dev` | Start development server with hot reload |
| `npm run release` | Build optimized production bundle |
| `npm run test` | Run tests |
| `npm run clean` | Remove compiled artifacts |

## Project Structure

```
├── deps.edn              # Clojure dependencies
├── package.json          # Node dependencies
├── shadow-cljs.edn       # ClojureScript build config
├── resources/
│   └── public/
│       ├── index.html    # HTML entry point
│       ├── manifest.json # PWA manifest
│       └── sw.js         # Service worker
├── src/cljs/rp/
│   ├── core.cljs         # App entry point
│   ├── db.cljs           # DataScript event store
│   ├── plan.cljs         # Workout plan templates
│   ├── state.cljs        # State reconstruction
│   ├── storage.cljs      # localStorage persistence
│   ├── ui.cljs           # Reagent components
│   └── util.cljs         # Utility functions
└── test/cljs/rp/
    └── state_test.cljs   # Tests
```

## Architecture

The app uses an **event sourcing** pattern:

1. User actions create immutable events (e.g., "set completed")
2. Events are stored in DataScript and persisted to localStorage
3. Current state is reconstructed by replaying events against the plan template

This allows for complete history, undo capability, and sync in the future.

## License

MIT
