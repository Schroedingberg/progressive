(ns rp.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [rp.state :as state]
            [rp.plan :as plan]
            [rp.util :as util]))

;; --- Test data ---

(def sample-plan
  {"My Plan"
   {0 {:monday {"Squat" [{:exercise-name "Squat" :muscle-groups [:quads]}
                         {:exercise-name "Squat" :muscle-groups [:quads]}]
                "Press" [{:exercise-name "Press" :muscle-groups [:shoulders]}]}}}})

(def sample-events
  [{:mesocycle "My Plan"
    :microcycle 0
    :workout :monday
    :exercise "Squat"
    :set-index 0
    :performed-weight 100
    :performed-reps 8}
   {:mesocycle "My Plan"
    :microcycle 0
    :workout :monday
    :exercise "Squat"
    :set-index 1
    :performed-weight 105
    :performed-reps 6}])

;; --- util tests ---

(deftest deep-merge-with-test
  (testing "merges nested maps"
    (let [base {:a {:b 1}}
          overlay {:a {:c 2}}]
      (is (= {:a {:b 1 :c 2}}
             (util/deep-merge-with (fn [_ x] x) overlay base)))))

  (testing "applies function at leaf nodes"
    (let [base {:a [1 2]}
          overlay {:a [3 4]}]
      ;; overlay first, base second - so base comes first in result
      (is (= {:a [3 4 1 2]}
             (util/deep-merge-with into overlay base))))))

;; --- state tests ---

(deftest view-progress-in-plan-test
  (testing "returns plan unchanged when no events"
    (is (= sample-plan
           (state/view-progress-in-plan [] sample-plan))))

  (testing "merges performed data into plan"
    (let [result (state/view-progress-in-plan sample-events sample-plan)
          squat-sets (get-in result ["My Plan" 0 :monday "Squat"])]
      ;; First set should have performed data merged
      (is (= 100 (:performed-weight (first squat-sets))))
      (is (= 8 (:performed-reps (first squat-sets))))
      ;; Second set too
      (is (= 105 (:performed-weight (second squat-sets))))
      ;; Original data should still be there
      (is (= [:quads] (:muscle-groups (first squat-sets))))))

  (testing "preserves unperformed sets"
    (let [result (state/view-progress-in-plan sample-events sample-plan)
          press-sets (get-in result ["My Plan" 0 :monday "Press"])]
      ;; Press wasn't logged, should be unchanged
      (is (= 1 (count press-sets)))
      (is (nil? (:performed-weight (first press-sets)))))))

;; --- plan tests ---

(deftest expand-exercises-test
  (testing "expands n-sets into vector of set maps"
    (let [template {:exercises {"Squat" {:n-sets 3 :muscle-groups [:quads]}}}
          expanded (#'plan/expand-exercises template)]
      (is (= 3 (count (get expanded "Squat"))))
      (is (every? #(= "Squat" (:exercise-name %)) (get expanded "Squat")))
      (is (every? #(= [:quads] (:muscle-groups %)) (get expanded "Squat"))))))

(deftest plan-expansion-test
  (testing "->plan creates full structure"
    (let [template {:name "Test"
                    :n-microcycles 2
                    :workouts {:monday {:exercises {"Squat" {:n-sets 2}}}}}
          plan (plan/->plan template)]
      ;; Has the plan name as key
      (is (contains? plan "Test"))
      ;; Has both microcycles
      (is (= 2 (count (get plan "Test"))))
      ;; Each microcycle has the workout
      (is (contains? (get-in plan ["Test" 0]) :monday))
      (is (contains? (get-in plan ["Test" 1]) :monday)))))
