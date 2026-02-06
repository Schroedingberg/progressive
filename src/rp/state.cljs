(ns rp.state
  "Reconstruct workout progress from an event log.
  
  The key function is `view-progress-in-plan` which merges:
  - A flat list of workout events (what you did)
  - A structured plan (what you should do)
  
  Into a single nested map showing both planned and performed data."
  (:require [rp.util :as util]))

;; -----------------------------------------------------------------------------
;; State reconstruction
;; -----------------------------------------------------------------------------

(defn- set-location [event]
  (select-keys event [:mesocycle :microcycle :workout :exercise :set-index]))

(defn- dedupe-by-latest
  "Keep only the latest event for each set position (enables corrections)."
  [events]
  (->> events
       (group-by set-location)
       vals
       (map #(apply max-key :timestamp %))))

(defn- events->plan-map
  "Transform flat events into nested plan structure."
  [events]
  (reduce
   (fn [acc {:keys [mesocycle microcycle workout exercise set-index] :as event}]
     (update-in acc [mesocycle microcycle (keyword workout) exercise]
                (fn [sets]
                  (let [sets (or sets [])
                        padded (into sets (repeat (max 0 (- (inc set-index) (count sets))) {}))]
                    (assoc padded set-index event)))))
   {}
   events))

(defn- merge-sets [performed planned]
  (let [n (max (count performed) (count planned))]
    (mapv #(merge (nth planned % {}) (nth performed % {}))
          (range n))))

(defn view-progress-in-plan
  "Merge event log with plan to show progress."
  [events plan]
  (let [set-events (filter :set-index events)
        event-map (-> set-events
                      dedupe-by-latest
                      events->plan-map)]
    (util/deep-merge-with merge-sets event-map plan)))

;; -----------------------------------------------------------------------------
;; Feedback detection
;; -----------------------------------------------------------------------------

(defn- workout-location
  "Normalize to {:mesocycle :microcycle :workout}."
  [{:keys [mesocycle microcycle workout]}]
  {:mesocycle mesocycle :microcycle microcycle :workout (keyword workout)})

(defn- same-workout? [a b]
  (= (workout-location a) (workout-location b)))

(defn- set-done? [set-data]
  (or (:performed-weight set-data) (= (:type set-data) :set-skipped)))

(defn feedback-reported?
  "Has feedback of `event-type` been reported for this muscle-group in this workout?"
  [events event-type {:keys [muscle-group] :as loc}]
  (some #(and (= (:type %) event-type)
              (= (:muscle-group %) muscle-group)
              (same-workout? % loc))
        events))

(defn muscle-group-sets
  "Get all sets for a muscle group in a workout."
  [progress {:keys [mesocycle microcycle workout]} muscle-group]
  (->> (get-in progress [mesocycle microcycle (keyword workout)])
       vals
       (mapcat identity)
       (filter #(some #{muscle-group} (:muscle-groups %)))))

(defn- muscle-group-started? [progress loc muscle-group]
  (some set-done? (muscle-group-sets progress loc muscle-group)))

(defn- muscle-group-finished? [progress loc muscle-group]
  (let [sets (muscle-group-sets progress loc muscle-group)]
    (and (seq sets) (every? set-done? sets))))

(defn last-active-workout
  "Get workout context from most recently logged set."
  [events]
  (when-let [last-set (->> events (filter :set-index) (sort-by :timestamp) last)]
    (workout-location last-set)))

(defn pending-feedback
  "Return muscle groups needing feedback of given type.
  `check-fn` is (fn [progress loc mg] -> bool) for when feedback is needed."
  [events progress loc muscle-groups event-type check-fn]
  (->> muscle-groups
       (filter #(check-fn progress loc %))
       (remove #(feedback-reported? events event-type (assoc loc :muscle-group %)))))

(defn pending-soreness-feedback
  "Muscle groups that need soreness feedback (started but not reported)."
  [events progress loc muscle-groups]
  (pending-feedback events progress loc muscle-groups :soreness-reported muscle-group-started?))

(defn pending-session-rating
  "Muscle groups that need session rating (finished but not rated)."
  [events progress loc muscle-groups]
  (pending-feedback events progress loc muscle-groups :session-rated muscle-group-finished?))
