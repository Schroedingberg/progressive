# Copilot Instructions for Romance Progression PWA

## Project Overview

This is a local-first PWA for workout tracking, written in ClojureScript. It's a rewrite of a server-based Clojure app, designed to work entirely offline.

## Architecture

### Event Sourcing Pattern

The core design uses event sourcing:

1. **Events** are immutable facts (e.g., `{:type :set-completed :weight 100 :reps 8 ...}`)
2. **State reconstruction** transforms flat events into nested structures matching the plan
3. **Deep merge** combines event data with plan templates for display

This means:
- Users can deviate from plans without breaking anything
- Progression algorithms can be swapped without data migration
- Complete history is preserved for undo/analytics

### Key Data Flow

```
User action → Event created → atom transact → localStorage persist
                                      ↓
Plan template + Events → events/view-progress-in-plan → UI render
```

### Namespace Responsibilities

| Namespace | Purpose |
|-----------|---------|
| `rp.app` | Entry point, vanilla DOM UI, storage |
| `rp.events` | Event store, state reconstruction, deep merge, feedback detection |
| `rp.plan` | Plan templates and expansion |
| `rp.progression` | Prescription algorithms |

## Code Style

### ClojureScript Conventions

- Use `defonce` for atoms that shouldn't reset on hot reload
- Prefer `->` and `->>` threading over nested calls
- Use `cond->` for conditional map building
- Keep namespaces small and focused (~50-100 lines ideal)
- Add docstrings to namespaces and public functions

### UI Patterns (Vanilla DOM)

- Use `el` helper function for DOM element creation
- State changes trigger full `render!` call (simple, fast enough)
- Store UI state in `app-state` atom
- Keep event handlers inline with `onclick`, `oninput`, etc.

### Data Representation

- Events use flat maps with simple keys (`:type`, `:weight`, `:reps`, etc.)
- Plans use nested maps: `{plan-name {microcycle {workout {exercise [sets]}}}}`
- Preserve key ordering with `array-map` or `sorted-map` where display order matters

## Testing

Run tests with:
```bash
npm run test        # Single run
npm run test:watch  # Watch mode
```

Tests use `cljs.test`. Focus on:
- State reconstruction logic (`state_test.cljs`)
- Plan expansion
- Pure functions over side-effects

## Common Tasks

### Adding a new event type

1. Add transaction function in `rp.events`
2. Handle in `rp.state/events->plan-map` if needed
3. Update UI in `rp.app` to trigger the event

### Adding a new plan template

1. Add template map in `rp.plan`
2. Add to `available-templates` vector

### Modifying progression logic

Progression belongs in a new `rp.progression` namespace (not yet implemented).
Keep it as pure functions: `(next-workout events) → prescribed-values`

## Don't

- Don't add server-side code - this is browser-only
- Don't use external state management (re-frame, etc.) - DataScript is sufficient
- Don't over-engineer - the goal is ~200 lines of core logic
- Don't break offline functionality - test with network disabled
