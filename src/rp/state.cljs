(ns rp.state
  "Reconstruct workout progress from an event log.
  
  The key function is `view-progress-in-plan` which merges:
  - A flat list of workout events (what you did)
  - A structured plan (what you should do)
  
  Into a single nested map showing both planned and performed data.")

;; -----------------------------------------------------------------------------
;; Deep merge utilities
;; -----------------------------------------------------------------------------

(defn- ordered-merge-with [f a b]
  (reduce-kv
   (fn [acc k v]
     (if (contains? acc k)
       (assoc acc k (f (get acc k) v))
       (assoc acc k v)))
   a b))

(defn deep-merge-with
  "Recursively merge maps, applying f to leaf values."
  [f a b]
  (cond
    (and (map? a) (map? b)) (ordered-merge-with #(deep-merge-with f %1 %2) a b)
    (and (nil? a) (map? b)) b
    (and (map? a) (nil? b)) a
    :else (f a b)))

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
  (let [set-events (filter #(number? (:set-index %)) events)
        event-map (-> set-events
                      dedupe-by-latest
                      events->plan-map)]
    (deep-merge-with merge-sets event-map plan)))

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
  (or (:performed-weight set-data)
      (#{:set-skipped :set-rejected} (:type set-data))))

(defn- set-completed?
  "True only if set was actually performed (not skipped/rejected)."
  [set-data]
  (some? (:performed-weight set-data)))

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
  (some set-completed? (muscle-group-sets progress loc muscle-group)))

(defn- muscle-group-finished? [progress loc muscle-group]
  (let [sets (muscle-group-sets progress loc muscle-group)
        completed-sets (filter set-completed? sets)]
    ;; Need at least one completed set, and all sets must be done (completed, skipped, or rejected)
    (and (seq completed-sets) (every? set-done? sets))))

(defn last-active-workout
  "Get workout context from most recently logged COMPLETED set (not skipped/rejected)."
  [events]
  (when-let [last-set (->> events
                           (filter #(and (number? (:set-index %))
                                         (= (:type %) :set-completed)))
                           (sort-by :timestamp)
                           last)]
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

;; -----------------------------------------------------------------------------
;; Exercise swaps
;; -----------------------------------------------------------------------------

(defn get-swaps
  "Get map of original->replacement exercise names for a workout location."
  [events {:keys [mesocycle microcycle workout]}]
  (->> events
       (filter #(= (:type %) :exercise-swapped))
       (filter #(and (= (:mesocycle %) mesocycle)
                     (= (:microcycle %) microcycle)
                     (= (keyword (:workout %)) (keyword workout))))
       ;; Latest swap wins if same exercise swapped multiple times
       (sort-by :timestamp)
       (reduce (fn [m {:keys [original-exercise replacement-exercise muscle-groups]}]
                 (assoc m original-exercise {:name replacement-exercise
                                             :muscle-groups muscle-groups}))
               {})))

(defn apply-swaps
  "Apply exercise swaps to a workout map. Returns updated workout with swapped names."
  [workout-exercises swaps]
  (reduce-kv
   (fn [acc exercise-name sets]
     (if-let [{:keys [name muscle-groups]} (get swaps exercise-name)]
       ;; Swapped: use new name, preserve muscle groups from swap event
       (assoc acc name (mapv #(assoc % :muscle-groups muscle-groups
                                     :original-exercise exercise-name) sets))
       ;; Not swapped: keep as-is
       (assoc acc exercise-name sets)))
   {}
   workout-exercises))
