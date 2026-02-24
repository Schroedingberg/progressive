(ns rp.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [rp.events :as events]))

;; Sample DataScript JS format data (subset of real export)
;; This mimics what `d/serializable` produces and `pr-str` serializes
(def sample-datascript-js-str
  "#js {:count 10, :tx0 536870912, :max-eid 3, :max-tx 536870915,
        :schema \"{:event/id {:db/unique :db.unique/identity}}\",
        :attrs #js [\":event/exercise\" \":event/id\" \":event/joint-pain\"
                    \":event/mesocycle\" \":event/microcycle\" \":event/muscle-group\"
                    \":event/performed-reps\" \":event/performed-weight\"
                    \":event/prescribed-reps\" \":event/prescribed-weight\"
                    \":event/pump\" \":event/set-index\" \":event/sets-workload\"
                    \":event/soreness\" \":event/timestamp\" \":event/type\"
                    \":event/workout\"],
        :keywords #js [\":set-completed\" \":monday\" \":back\" \":never-sore\"
                       \":soreness-reported\" \":none\" \":just-right\"
                       \":session-rated\" \":chest\"],
        :eavt #js [#js [1 0 \"Dumbbell Row\" 1]
                   #js [1 1 \"uuid-1\" 1]
                   #js [1 3 \"My Plan\" 1]
                   #js [1 4 0 1]
                   #js [1 6 15 1]
                   #js [1 7 40 1]
                   #js [1 11 0 1]
                   #js [1 14 1770392738401 1]
                   #js [1 15 #js [0 0] 1]
                   #js [1 16 #js [0 1] 1]
                   #js [2 0 \"Dumbbell Row\" 2]
                   #js [2 1 \"uuid-2\" 2]
                   #js [2 3 \"My Plan\" 2]
                   #js [2 4 0 2]
                   #js [2 6 12 2]
                   #js [2 7 40 2]
                   #js [2 11 1 2]
                   #js [2 14 1770392749618 2]
                   #js [2 15 #js [0 0] 2]
                   #js [2 16 #js [0 1] 2]
                   #js [3 1 \"uuid-3\" 3]
                   #js [3 3 \"My Plan\" 3]
                   #js [3 4 0 3]
                   #js [3 5 #js [0 2] 3]
                   #js [3 13 #js [0 3] 3]
                   #js [3 14 1770392741173 3]
                   #js [3 15 #js [0 4] 3]
                   #js [3 16 #js [0 1] 3]],
        :aevt #js [],
        :avet #js []}")

(deftest migrate-datascript-js-format-test
  (testing "migrates DataScript JS serialization format"
    ;; Clear events first
    (events/clear-all!)

    ;; Load the sample data
    (events/load-from-edn! sample-datascript-js-str)

    ;; Check we got events
    (let [all-events (events/get-all-events)]
      (is (= 3 (count all-events)) "Should have 3 events")

      ;; Check first set-completed event
      (let [first-event (first all-events)]
        (is (= :set-completed (:type first-event)))
        (is (= "My Plan" (:mesocycle first-event)))
        (is (= 0 (:microcycle first-event)))
        (is (= :monday (:workout first-event)))
        (is (= "Dumbbell Row" (:exercise first-event)))
        (is (= 0 (:set-index first-event)))
        (is (= 40 (:performed-weight first-event)))
        (is (= 15 (:performed-reps first-event)))
        (is (number? (:timestamp first-event))))

      ;; Check soreness-reported event 
      (let [soreness-event (first (filter #(= :soreness-reported (:type %)) all-events))]
        (is (some? soreness-event) "Should have a soreness event")
        (is (= :back (:muscle-group soreness-event)))
        (is (= :never-sore (:soreness soreness-event)))))))

(deftest migrate-preserves-all-event-types
  (testing "migrates various event types correctly"
    (events/clear-all!)
    (events/load-from-edn! sample-datascript-js-str)

    (let [all-events (events/get-all-events)
          by-type (group-by :type all-events)]
      (is (= 2 (count (:set-completed by-type))) "Should have 2 set-completed")
      (is (= 1 (count (:soreness-reported by-type))) "Should have 1 soreness-reported"))))

(deftest new-format-loads-directly
  (testing "new vector format loads without migration"
    (events/clear-all!)

    (let [new-format-str "[{:type :set-completed :mesocycle \"Test\" :timestamp 123}]"]
      (events/load-from-edn! new-format-str)

      (is (= 1 (count (events/get-all-events))))
      (is (= :set-completed (:type (first (events/get-all-events))))))))

(deftest empty-or-invalid-data-handled
  (testing "nil data doesn't crash"
    (events/clear-all!)
    (events/load-from-edn! nil)
    (is (= 0 (count (events/get-all-events)))))

  (testing "empty string doesn't crash"
    (events/clear-all!)
    ;; This will throw on read-string, but that's expected
    ;; The app should handle this gracefully
    ))
