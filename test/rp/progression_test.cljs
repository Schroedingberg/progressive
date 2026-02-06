(ns rp.progression-test
  (:require [cljs.test :refer [deftest is testing]]
            [rp.progression :as prog]))

;; -----------------------------------------------------------------------------
;; Test data
;; -----------------------------------------------------------------------------

(def sample-events
  [{:type :set-completed
    :mesocycle "Plan" :microcycle 0 :workout :monday :exercise "Squat" :set-index 0
    :performed-weight 100 :performed-reps 10 :timestamp 1000}
   {:type :set-completed
    :mesocycle "Plan" :microcycle 0 :workout :monday :exercise "Squat" :set-index 1
    :performed-weight 100 :performed-reps 9 :timestamp 1001}
   {:type :set-completed
    :mesocycle "Plan" :microcycle 0 :workout :monday :exercise "Press" :set-index 0
    :performed-weight 60 :performed-reps 8 :timestamp 1002}])

;; -----------------------------------------------------------------------------
;; last-performance tests
;; -----------------------------------------------------------------------------

(deftest last-performance-test
  (testing "finds last performance from previous microcycle"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/last-performance sample-events location)]
      (is (some? result))
      (is (= 100 (:performed-weight result)))
      (is (= 10 (:performed-reps result)))))

  (testing "returns nil when no previous microcycle exists"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (nil? (prog/last-performance sample-events location)))))

  (testing "returns nil for unknown exercise"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Deadlift" :set-index 0}]
      (is (nil? (prog/last-performance sample-events location)))))

  (testing "distinguishes between set indices"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 1}
          result (prog/last-performance sample-events location)]
      (is (= 9 (:performed-reps result))))))

;; -----------------------------------------------------------------------------
;; prescribe-weight tests
;; -----------------------------------------------------------------------------

(deftest prescribe-weight-test
  (testing "adds increment to last weight"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 102.5 (prog/prescribe-weight sample-events location)))))

  (testing "returns nil when no history"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (nil? (prog/prescribe-weight sample-events location))))))

;; -----------------------------------------------------------------------------
;; prescribe-reps tests
;; -----------------------------------------------------------------------------

(deftest prescribe-reps-test
  (testing "returns last reps when no weight override"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 10 (prog/prescribe-reps sample-events location)))))

  (testing "returns last reps when weight matches prescribed"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 10 (prog/prescribe-reps sample-events location 102.5)))))

  (testing "adjusts reps down when weight increased beyond prescribed"
    ;; Last: 100kg × 10 = 1000 work
    ;; Prescribed: 102.5kg × 10 = 1025 target work  
    ;; User picks 110kg → 1025/110 ≈ 9.3 → 9 reps
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 9 (prog/prescribe-reps sample-events location 110)))))

  (testing "adjusts reps up when weight decreased"
    ;; Prescribed: 102.5kg × 10 = 1025 target work
    ;; User picks 95kg → 1025/95 ≈ 10.8 → 11 reps
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 11 (prog/prescribe-reps sample-events location 95)))))

  (testing "returns nil when no history"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (nil? (prog/prescribe-reps sample-events location))))))

;; -----------------------------------------------------------------------------
;; prescribe tests
;; -----------------------------------------------------------------------------

(deftest prescribe-test
  (testing "returns both weight and reps"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe sample-events location)]
      (is (= 102.5 (:weight result)))
      (is (= 10 (:reps result)))))

  (testing "adjusts reps when actual weight provided"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe sample-events location 110)]
      (is (= 102.5 (:weight result)))  ; prescribed weight unchanged
      (is (= 9 (:reps result)))))      ; reps adjusted

  (testing "returns nils when no history"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe sample-events location)]
      (is (nil? (:weight result)))
      (is (nil? (:reps result))))))
