(ns rp.events
  "Event store for workout logging.
  
  Simple atom-based store replacing DataScript.
  Events are immutable facts with auto-generated id/timestamp.
  
  Public API matches the original rp.db namespace for compatibility."
  (:require [cljs.reader :as reader]))

;; -----------------------------------------------------------------------------
;; State
;; -----------------------------------------------------------------------------

(defonce events (atom []))
(defonce version (atom 0))

(add-watch events :version-bump (fn [_ _ _ _] (swap! version inc)))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- make-event [data]
  (assoc data
         :id (str (random-uuid))
         :timestamp (js/Date.now)))

;; -----------------------------------------------------------------------------
;; Transactions (same API as rp.db)
;; -----------------------------------------------------------------------------

(defn log-set!
  "Log a completed set."
  [{:keys [mesocycle microcycle workout exercise set-index weight reps
           prescribed-weight prescribed-reps]}]
  (swap! events conj
         (make-event
          (cond-> {:type :set-completed
                   :mesocycle mesocycle :microcycle microcycle :workout workout
                   :exercise exercise :set-index set-index
                   :performed-weight weight :performed-reps reps}
            prescribed-weight (assoc :prescribed-weight prescribed-weight)
            prescribed-reps (assoc :prescribed-reps prescribed-reps)))))

(defn skip-set!
  "Log a skipped set."
  [{:keys [mesocycle microcycle workout exercise set-index]}]
  (swap! events conj
         (make-event {:type :set-skipped
                      :mesocycle mesocycle :microcycle microcycle :workout workout
                      :exercise exercise :set-index set-index})))

(defn reject-set!
  "Log a rejected set (volume cap)."
  [{:keys [mesocycle microcycle workout exercise set-index]}]
  (swap! events conj
         (make-event {:type :set-rejected
                      :mesocycle mesocycle :microcycle microcycle :workout workout
                      :exercise exercise :set-index set-index})))

(defn swap-exercise!
  "Log an exercise swap."
  [{:keys [mesocycle microcycle workout original-exercise replacement-exercise muscle-groups]}]
  (swap! events conj
         (make-event {:type :exercise-swapped
                      :mesocycle mesocycle :microcycle microcycle :workout workout
                      :original-exercise original-exercise
                      :replacement-exercise replacement-exercise
                      :muscle-groups muscle-groups})))

(defn log-soreness-reported!
  "Log soreness feedback for a muscle group."
  [{:keys [mesocycle microcycle workout muscle-group soreness]}]
  (swap! events conj
         (make-event {:type :soreness-reported
                      :mesocycle mesocycle :microcycle microcycle :workout workout
                      :muscle-group muscle-group :soreness soreness})))

(defn log-session-rated!
  "Log session feedback for a muscle group."
  [{:keys [mesocycle microcycle workout muscle-group pump joint-pain sets-workload]}]
  (swap! events conj
         (make-event {:type :session-rated
                      :mesocycle mesocycle :microcycle microcycle :workout workout
                      :muscle-group muscle-group
                      :pump pump :joint-pain joint-pain :sets-workload sets-workload})))

;; -----------------------------------------------------------------------------
;; Queries
;; -----------------------------------------------------------------------------

(defn get-all-events
  "Get all events, sorted by timestamp."
  []
  @version  ; Touch for reactivity
  (sort-by :timestamp @events))

;; -----------------------------------------------------------------------------
;; Serialization (same format as before for backwards compat)
;; -----------------------------------------------------------------------------

(defn db->edn []
  (pr-str @events))

(defn- js-array->vec
  "Convert JS array to Clojure vector recursively."
  [arr]
  (when arr
    (mapv (fn [x]
            (if (array? x)
              (js-array->vec x)
              x))
          arr)))

(defn- migrate-datascript-js
  "Convert DataScript's JS serialization format to event vector.
  
  Format: JS object with:
    attrs: array of attribute name strings e.g. [\":event/exercise\" \":event/id\" ...]
    keywords: array of keyword value strings e.g. [\":set-completed\" \":monday\" ...]  
    eavt: array of datoms [eid attr-idx value tx]
          where value can be a keyword reference as [0 idx]"
  [^js js-obj]
  (let [attrs-arr (.-attrs js-obj)
        keywords-arr (.-keywords js-obj)
        eavt-arr (.-eavt js-obj)]
    (when (and attrs-arr eavt-arr)
      (let [attrs (js-array->vec attrs-arr)
            keywords (js-array->vec keywords-arr)
            eavt (js-array->vec eavt-arr)

            decode-attr (fn [idx]
                          (when-let [s (get attrs idx)]
                            (keyword (subs s 7)))) ; Strip ":event/" prefix (7 chars)

            decode-val (fn [v]
                         (cond
                           ;; Keyword reference: [0 idx] 
                           (and (vector? v) (= 2 (count v)) (= 0 (first v)))
                           (when-let [s (get keywords (second v))]
                             (keyword (subs s 1))) ; Strip leading ":"

                           ;; Plain value
                           :else v))]

        (->> eavt
             ;; Group by entity id
             (group-by first)
             vals
             ;; Convert each entity's datoms to event map
             (map (fn [entity-datoms]
                    (reduce (fn [acc [_eid attr-idx val _tx]]
                              (when-let [attr (decode-attr attr-idx)]
                                (assoc acc attr (decode-val val))))
                            {}
                            entity-datoms)))
             ;; Filter to actual events
             (filter :type)
             vec)))))

(defn- migrate-datascript-edn
  "Convert old DataScript EDN format (with :datoms key) to event vector."
  [{:keys [datoms]}]
  (when (seq datoms)
    (->> datoms
         (group-by first)
         vals
         (map (fn [entity-datoms]
                (reduce (fn [acc [_eid attr val _tx]]
                          (let [k (keyword (name attr))]
                            (assoc acc k val)))
                        {}
                        entity-datoms)))
         (filter :type)
         vec)))

(defn load-from-edn! [edn-str]
  (when edn-str
    (let [data (reader/read-string edn-str)]
      (cond
        ;; New format: vector of events
        (vector? data)
        (reset! events data)

        ;; DataScript JS format: object with .-eavt (from #js reader)
        (and (object? data) (.-eavt ^js data))
        (when-let [migrated (migrate-datascript-js data)]
          (js/console.log "Migrated" (count migrated) "events from DataScript JS format")
          (reset! events migrated))

        ;; DataScript EDN format: map with :datoms key
        (and (map? data) (:datoms data))
        (when-let [migrated (migrate-datascript-edn data)]
          (js/console.log "Migrated" (count migrated) "events from DataScript EDN format")
          (reset! events migrated))

        ;; Unknown format
        :else
        (js/console.warn "Unknown data format, starting fresh")))))

(defn clear-all! []
  (reset! events [])
  (swap! version inc))

;; -----------------------------------------------------------------------------
;; Deep merge utilities (merged from state.cljs)
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
;; State reconstruction (merged from state.cljs)
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
  [all-events plan]
  (let [set-events (filter #(number? (:set-index %)) all-events)
        event-map (-> set-events
                      dedupe-by-latest
                      events->plan-map)]
    (deep-merge-with merge-sets event-map plan)))

;; -----------------------------------------------------------------------------
;; Feedback detection (merged from state.cljs)
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
  [all-events event-type {:keys [muscle-group] :as loc}]
  (some #(and (= (:type %) event-type)
              (= (:muscle-group %) muscle-group)
              (same-workout? % loc))
        all-events))

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
  [all-events]
  (when-let [last-set (->> all-events
                           (filter #(and (number? (:set-index %))
                                         (= (:type %) :set-completed)))
                           (sort-by :timestamp)
                           last)]
    (workout-location last-set)))

(defn pending-feedback
  "Return muscle groups needing feedback of given type.
  `check-fn` is (fn [progress loc mg] -> bool) for when feedback is needed."
  [all-events progress loc muscle-groups event-type check-fn]
  (->> muscle-groups
       (filter #(check-fn progress loc %))
       (remove #(feedback-reported? all-events event-type (assoc loc :muscle-group %)))))

(defn pending-soreness-feedback
  "Muscle groups that need soreness feedback (started but not reported)."
  [all-events progress loc muscle-groups]
  (pending-feedback all-events progress loc muscle-groups :soreness-reported muscle-group-started?))

(defn pending-session-rating
  "Muscle groups that need session rating (finished but not rated)."
  [all-events progress loc muscle-groups]
  (pending-feedback all-events progress loc muscle-groups :session-rated muscle-group-finished?))

;; -----------------------------------------------------------------------------
;; Exercise swaps (merged from state.cljs)
;; -----------------------------------------------------------------------------

(defn get-swaps
  "Get map of original->replacement exercise names for a workout location."
  [all-events {:keys [mesocycle microcycle workout]}]
  (->> all-events
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