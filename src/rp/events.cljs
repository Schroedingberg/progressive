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
