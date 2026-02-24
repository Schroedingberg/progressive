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

(defn load-from-edn! [edn-str]
  (when edn-str
    (let [data (reader/read-string edn-str)]
      ;; Only load if it's a vector (new format), ignore old DataScript format
      (when (vector? data)
        (reset! events data)))))

(defn clear-all! []
  (reset! events [])
  (swap! version inc))
