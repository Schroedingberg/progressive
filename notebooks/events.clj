;; # Event Reference
;;
;; Complete reference for all event types in the system.

^{:nextjournal.clerk/visibility {:code :hide}}
(ns events
  {:nextjournal.clerk/toc true}
  (:require [nextjournal.clerk :as clerk]))

;; ## Event Types Overview

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Type" "Description" "Logged By"]
  :rows [[:set-completed "User performed a set" "rp.db/log-set!"]
         [:set-skipped "User skipped a planned set" "rp.db/skip-set!"]
         [:set-rejected "User rejected excessive volume" "rp.db/reject-set!"]
         [:exercise-swapped "User swapped exercise mid-workout" "rp.db/swap-exercise!"]
         [:soreness-reported "User reported muscle soreness" "rp.db/log-soreness-reported!"]
         [:workload-reported "User reported session workload" "rp.db/log-workload-reported!"]
         [:joint-pain-reported "User reported joint pain" "rp.db/log-joint-pain-reported!"]]})

;; ## Set Events
;;
;; These events track individual set completions within a workout.

;; ### `:set-completed`
;;
;; Logged when user checks off a set with weight and reps.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def set-completed-example
  {:type :set-completed
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :exercise "Bench Press"
   :set-index 0
   :performed-weight 135
   :performed-reps 10
   :prescribed-weight 130
   :prescribed-reps 10})

;; | Key | Required | Description |
;; |-----|----------|-------------|
;; | `:mesocycle` | ✓ | Training block name |
;; | `:microcycle` | ✓ | Week within block |
;; | `:workout` | ✓ | Workout keyword (`:push-a`, `:pull-b`, etc.) |
;; | `:exercise` | ✓ | Exercise name string |
;; | `:set-index` | ✓ | Zero-based set number |
;; | `:performed-weight` | ✓ | Actual weight used |
;; | `:performed-reps` | ✓ | Actual reps completed |
;; | `:prescribed-weight` | | Planned weight (for deviation tracking) |
;; | `:prescribed-reps` | | Planned reps |

;; ### `:set-skipped`
;;
;; Logged when user intentionally skips a set (fatigue, time, etc.).

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def set-skipped-example
  {:type :set-skipped
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :exercise "Bench Press"
   :set-index 2})

;; Skip events use the same location keys but no performed values.

;; ### `:set-rejected`
;;
;; Logged when algorithm prescribes excessive volume and user rejects.
;; Signals to progression system that volume cap was hit.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def set-rejected-example
  {:type :set-rejected
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :exercise "Overhead Press"
   :set-index 4})

;; ## Exercise Events

;; ### `:exercise-swapped`
;;
;; Logged when user swaps an exercise mid-workout (equipment unavailable, etc.).

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def exercise-swapped-example
  {:type :exercise-swapped
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :original-exercise "Barbell Bench Press"
   :replacement-exercise "Dumbbell Bench Press"
   :muscle-groups ["chest" "front-delt" "tricep"]})

;; Swap events preserve muscle groups so progression tracking continues.

;; ## Feedback Events
;;
;; These capture subjective feedback for progression adjustments.

;; ### `:soreness-reported`
;;
;; Muscle soreness level (1-4 scale) for a specific muscle group.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def soreness-example
  {:type :soreness-reported
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :muscle-group "chest"
   :soreness 2})

;; | Soreness | Meaning | Weight Modifier |
;; |----------|---------|-----------------|
;; | 1 | Not sore | +2.5% |
;; | 2 | Slightly sore | 0% |
;; | 3 | Quite sore | -2.5% |
;; | 4 | Very sore | -5% |

;; ### `:workload-reported`
;;
;; Session difficulty rating (1-4 scale).

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def workload-example
  {:type :workload-reported
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :muscle-group "chest"
   :workload 3})

;; | Workload | Meaning | Weight Modifier |
;; |----------|---------|-----------------|
;; | 1 | Too easy | +5% |
;; | 2 | Just right | 0% |
;; | 3 | Hard | -2.5% |
;; | 4 | Too hard | -5% |

;; ### `:joint-pain-reported`
;;
;; Joint pain level (1-4 scale).

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def joint-pain-example
  {:type :joint-pain-reported
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :muscle-group "chest"
   :joint-pain 1})

;; | Pain | Meaning | Weight Modifier |
;; |------|---------|-----------------|
;; | 1 | No pain | 0% |
;; | 2 | Mild | -2.5% |
;; | 3 | Moderate | -5% |
;; | 4 | Severe | -10% |

;; ## Using Events in UI
;;
;; The UI queries events from DataScript and reconstructs state:
;;
;; ```clojure
;; ;; In component
;; (let [events (db/get-all-events)
;;       plan (plan/current-plan)
;;       progress (state/view-progress-in-plan events plan)]
;;   ;; render based on progress...
;;   )
;; ```

;; ---
;;
;; [← Back to Index](./index)
