(ns rp.db
  "DataScript-based event store for workout logging.
  
  Events are stored as immutable facts. The `log-set!` function appends
  new events, and `get-all-events` returns them for state reconstruction."
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [cljs.reader :as reader]))

(def ^:private schema
  {:event/id               {:db/unique :db.unique/identity}
   :event/type             {}
   :event/mesocycle        {}
   :event/microcycle       {}
   :event/workout          {}
   :event/exercise         {}
   :event/set-index        {}
   :event/performed-weight {}
   :event/performed-reps   {}
   :event/prescribed-weight {}
   :event/prescribed-reps  {}
   :event/timestamp        {}})

(defonce conn (d/create-conn schema))
(defonce db-version (r/atom 0))

;; Trigger re-renders on DB changes
(d/listen! conn :ui (fn [_] (swap! db-version inc)))

;; --- Transactions ---

(defn log-set!
  "Log a completed set. Returns the transaction result."
  [{:keys [mesocycle microcycle workout exercise set-index weight reps
           prescribed-weight prescribed-reps]}]
  (d/transact! conn
               [(cond-> {:event/id (str (random-uuid))
                         :event/type :set-completed
                         :event/mesocycle mesocycle
                         :event/microcycle microcycle
                         :event/workout workout
                         :event/exercise exercise
                         :event/set-index set-index
                         :event/performed-weight weight
                         :event/performed-reps reps
                         :event/timestamp (js/Date.now)}
                  prescribed-weight (assoc :event/prescribed-weight prescribed-weight)
                  prescribed-reps (assoc :event/prescribed-reps prescribed-reps))]))

(defn skip-set!
  "Log a skipped set event."
  [{:keys [mesocycle microcycle workout exercise set-index]}]
  (d/transact! conn
               [{:event/id (str (random-uuid))
                 :event/type :set-skipped
                 :event/mesocycle mesocycle
                 :event/microcycle microcycle
                 :event/workout workout
                 :event/exercise exercise
                 :event/set-index set-index
                 :event/timestamp (js/Date.now)}]))

;; --- Queries ---

(defn- entity->event
  "Convert DataScript entity to domain event map."
  [e]
  (-> e
      (dissoc :db/id)
      (update-keys #(keyword (name %)))))

(defn get-all-events
  "Get all logged events, sorted by timestamp."
  []
  @db-version  ; Subscribe to changes
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :event/type]]
            @conn)
       (map entity->event)
       (sort-by :timestamp)))

;; --- Serialization ---

(defn db->edn []
  (pr-str (d/serializable @conn)))

(defn load-from-edn! [edn-str]
  (when edn-str
    (reset! conn (d/from-serializable (reader/read-string edn-str) schema))))
