# Architecture & Extension Guide

Quick reference for adding or extending features in the Romance Progression PWA.

## Core Pattern: Data-Driven UI

Structure is defined as **data**, and generic renderers interpret that data.

```clojure
;; Data defines WHAT
(def nav-items [[:workouts "Workouts"] [:plans "Plans"]])

;; Renderer defines HOW
(defn nav-menu []
  (for [[page label] nav-items]
    [:li [:a {:on-click #(reset! current-page page)} label]]))
```

---

## Extension Points

### Add a Navigation Tab

**File:** `src/rp/ui.cljs` → `nav-items`

```clojure
(def ^:private nav-items
  [[:workouts "Workouts"]
   [:plans    "Plans"]
   [:settings "Settings"]
   [:history  "History"]])  ;; ← add here
```

Then add corresponding page definition (see below).

---

### Add a Page

**File:** `src/rp/ui.cljs` → `pages`

```clojure
(def ^:private pages
  {:workouts {:title "Workouts" :subtitle "..." :content workouts-content}
   :plans    {:title "Plans"    :subtitle "..." :content plans-content}
   :history  {:title "History"  ;; ← add entry
              :subtitle "View past workouts"
              :content history-content}})
```

The `:content` fn must return hiccup. `:title` can be a string or `(fn [] string)` for dynamic titles.

---

### Add a Settings Action

**File:** `src/rp/ui.cljs` → `settings-actions`

```clojure
(def ^:private settings-actions
  [{:label "Export All Data" :class "secondary" :on-click export-fn}
   {:label "Clear Logs" :class "secondary.outline" 
    :confirm "Clear all logs?" :on-click storage/clear-db!}
   {:label "Import Data" :class "secondary"   ;; ← add entry
    :on-click import-fn}])
```

| Key | Required | Description |
|-----|----------|-------------|
| `:label` | ✓ | Button text |
| `:class` | ✓ | CSS classes (e.g., `"secondary.outline"`) |
| `:on-click` | ✓ | Handler function |
| `:confirm` | | Confirmation prompt before action |

---

### Add a Feedback Popup Type

**File:** `src/rp/ui.cljs` → `feedback-types`

```clojure
(def ^:private feedback-types
  [{:type :soreness
    :pending-fn state/pending-soreness-feedback
    :component feedback/soreness-popup
    :log-fn #(db/log-soreness-reported! (assoc %1 :muscle-group %2 :soreness %3))}
   {:type :session ...}
   {:type :difficulty  ;; ← add entry
    :pending-fn state/pending-difficulty-feedback
    :component feedback/difficulty-popup
    :log-fn #(db/log-difficulty! (assoc %1 :muscle-group %2 :difficulty %3))}])
```

Order matters - first pending type wins.

---

### Add Feedback Options

**File:** `src/rp/ui/feedback.cljs`

```clojure
;; Radio options
(def ^:private soreness-options
  [{:value :never-sore :label "Never got sore"}
   {:value :healed-early :label "Healed a while ago"}
   ...])

;; Slider labels
(def ^:private pump-labels
  ["None" "Mild" "Moderate" "Great" "Best ever"])
```

Use with `radio-field` or `slider-field` helpers.

---

### Add an Event Type

**File:** `src/rp/db.cljs`

1. Add transaction function:
```clojure
(defn log-difficulty!
  [{:keys [mesocycle microcycle workout muscle-group difficulty]}]
  (transact-event!
   {:type :difficulty-reported
    :mesocycle mesocycle :microcycle microcycle :workout workout
    :muscle-group muscle-group :difficulty difficulty}))
```

2. (Optional) Add state reconstruction in `src/rp/state.cljs` if events need processing.

---

### Add a Plan Template

**File:** `src/rp/plan.cljs` → `available-templates`

```clojure
(def available-templates
  [ppl-template
   upper-lower-template
   my-new-template])  ;; ← add here
```

Template structure:
```clojure
{:name "My Plan"
 :n-microcycles 4
 :workouts [{:day :monday
             :exercises [{:name "Squat" :sets 3 :muscle-groups [:quads]}]}
            ...]}
```

---

## File Responsibilities

| File | Purpose | Key exports |
|------|---------|-------------|
| `db.cljs` | Event store | `log-set!`, `get-all-events` |
| `state.cljs` | Event → state reconstruction | `view-progress-in-plan` |
| `plan.cljs` | Plan templates | `get-plan`, `available-templates` |
| `progression.cljs` | Prescription algorithm | `prescribe` |
| `ui.cljs` | App shell + pages | `app` |
| `ui/components.cljs` | Generic primitives | `radio-group`, `modal-dialog` |
| `ui/feedback.cljs` | Feedback popups | `soreness-popup`, `session-rating-popup` |
| `ui/workout.cljs` | Set/exercise display | `set-row`, `exercise-card` |

---

## Event Sourcing Flow

```
User action
    ↓
db/log-*! (creates event with auto id+timestamp)
    ↓
DataScript transact
    ↓
storage/auto-save (localStorage)
    ↓
db-version atom increments → triggers re-render
    ↓
state/view-progress-in-plan (events + plan → progress)
    ↓
UI renders merged data
```

**Key principle:** Events are facts. State is derived. Never mutate past events.

---

## Styling

Uses [Pico CSS](https://picocss.com/). Key patterns:

```clojure
;; Container width
[:main.container ...]

;; Button variants
[:button "Primary"]
[:button.secondary "Secondary"]
[:button.outline "Outline"]
[:button.secondary.outline "Secondary Outline"]

;; Active nav link
[:a {:class (when active? "contrast")} label]
```
