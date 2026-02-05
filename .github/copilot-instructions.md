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
User action → Event created → DataScript transact → localStorage persist
                                      ↓
Plan template + Events → state/view-progress-in-plan → UI render
```

### Namespace Responsibilities

| Namespace | Purpose |
|-----------|---------|
| `rp.core` | App entry point, service worker registration |
| `rp.db` | DataScript schema, transactions, queries |
| `rp.state` | Event log → plan structure transformation |
| `rp.plan` | Plan templates and expansion |
| `rp.storage` | localStorage persistence with auto-save |
| `rp.ui` | Reagent components |
| `rp.util` | Deep merge utilities |

## Code Style

### ClojureScript Conventions

- Use `defonce` for atoms that shouldn't reset on hot reload
- Prefer `->` and `->>` threading over nested calls
- Use `cond->` for conditional map building
- Keep namespaces small and focused (~50-100 lines ideal)
- Add docstrings to namespaces and public functions

### Reagent Patterns

- Form-1 components for pure rendering
- Form-2 components when local state is needed (atoms in outer fn)
- Use `r/atom` for component-local state
- Use DataScript + `db-version` atom for global reactive state

### Data Representation

- Events use flat maps with namespaced-style keys (`:event/type`, `:event/weight`)
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

1. Add schema in `rp.db`
2. Create transaction function in `rp.db`
3. Handle in `rp.state/events->plan-map` if needed
4. Update UI to trigger the event

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
