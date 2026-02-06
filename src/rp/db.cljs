(ns rp.db
  "DataScript-based event store for workout logging.
  
  Events are stored as immutable facts with automatic id/timestamp.
  Use `get-all-events` to retrieve for state reconstruction."
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [cljs.reader :as reader]))

(def ^:private schema
  {:event/id {:db/unique :db.unique/identity}})

(defonce conn (d/create-conn schema))
(defonce db-version (r/atom 0))

(d/listen! conn :ui (fn [_] (swap! db-version inc)))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- ns-keys
  "Add :event/ namespace to all keys."
  [m]
  (into {} (map (fn [[k v]] [(keyword "event" (name k)) v])) m))

(defn- transact-event!
  "Transact an event with auto-generated id and timestamp."
  [event]
  (d/transact! conn [(-> event
                         ns-keys
                         (assoc :event/id (str (random-uuid))
                                :event/timestamp (js/Date.now)))]))

(defn- entity->event
  "Convert DataScript entity to plain map (strip :db/id and :event/ prefix)."
  [e]
  (into {} (keep (fn [[k v]]
                   (when (not= k :db/id)
                     [(keyword (name k)) v])))
        e))

;; -----------------------------------------------------------------------------
;; Transactions
;; -----------------------------------------------------------------------------

(defn log-set!
  "Log a completed set."
  [{:keys [mesocycle microcycle workout exercise set-index weight reps
           prescribed-weight prescribed-reps]}]
  (transact-event!
   (cond-> {:type :set-completed
            :mesocycle mesocycle :microcycle microcycle :workout workout
            :exercise exercise :set-index set-index
            :performed-weight weight :performed-reps reps}
     prescribed-weight (assoc :prescribed-weight prescribed-weight)
     prescribed-reps (assoc :prescribed-reps prescribed-reps))))

(defn skip-set!
  "Log a skipped set."
  [{:keys [mesocycle microcycle workout exercise set-index]}]
  (transact-event!
   {:type :set-skipped
    :mesocycle mesocycle :microcycle microcycle :workout workout
    :exercise exercise :set-index set-index}))

(defn reject-set!
  "Log a rejected set (volume cap - algorithm prescribed too many)."
  [{:keys [mesocycle microcycle workout exercise set-index]}]
  (transact-event!
   {:type :set-rejected
    :mesocycle mesocycle :microcycle microcycle :workout workout
    :exercise exercise :set-index set-index}))

(defn swap-exercise!
  "Log an exercise swap (keep muscle groups, change movement)."
  [{:keys [mesocycle microcycle workout original-exercise replacement-exercise muscle-groups]}]
  (transact-event!
   {:type :exercise-swapped
    :mesocycle mesocycle :microcycle microcycle :workout workout
    :original-exercise original-exercise
    :replacement-exercise replacement-exercise
    :muscle-groups muscle-groups}))

(defn log-soreness-reported!
  "Log soreness feedback for a muscle group."
  [{:keys [mesocycle microcycle workout muscle-group soreness]}]
  (transact-event!
   {:type :soreness-reported
    :mesocycle mesocycle :microcycle microcycle :workout workout
    :muscle-group muscle-group :soreness soreness}))

(defn log-session-rated!
  "Log session feedback for a muscle group."
  [{:keys [mesocycle microcycle workout muscle-group pump joint-pain sets-workload]}]
  (transact-event!
   {:type :session-rated
    :mesocycle mesocycle :microcycle microcycle :workout workout
    :muscle-group muscle-group
    :pump pump :joint-pain joint-pain :sets-workload sets-workload}))

;; -----------------------------------------------------------------------------
;; Queries
;; -----------------------------------------------------------------------------

(defn get-all-events
  "Get all logged events, sorted by timestamp."
  []
  @db-version  ; Subscribe to changes
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :event/type]]
            @conn)
       (map entity->event)
       (sort-by :timestamp)))

;; -----------------------------------------------------------------------------
;; Serialization
;; -----------------------------------------------------------------------------

(defn db->edn []
  (pr-str (d/serializable @conn)))

(defn load-from-edn! [edn-str]
  (when edn-str
    (reset! conn (d/from-serializable (reader/read-string edn-str) schema))))

(defn clear-all!
  "Reset the database to empty state."
  []
  (reset! conn (d/empty-db schema))
  (swap! db-version inc))
