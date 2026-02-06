(ns rp.progression
  "Compute workout prescriptions from training history.
  
  Core principles:
  - No planning ahead — prescriptions computed on-the-fly from events
  - Weight-first progression — add weight each session
  - Work preservation — if user overrides weight, adjust reps to maintain
    similar total work increase
  
  Work model: work ≈ weight × reps (simplified)
  
  Example:
    Last session: 100kg × 10 = 1000 work
    Prescribed:   102.5kg × 10 = 1025 work (+2.5%)
    User picks 110kg → reps adjusted to 1025/110 ≈ 9 reps")

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(def ^:private weight-increment 2.5)  ; kg to add each session

;; -----------------------------------------------------------------------------
;; History queries
;; -----------------------------------------------------------------------------

(defn- same-slot?
  "Check if event is for the same workout slot (exercise + set-index on same day)."
  [event {:keys [mesocycle workout exercise set-index]}]
  (and (= (:mesocycle event) mesocycle)
       (= (keyword (:workout event)) (keyword workout))
       (= (:exercise event) exercise)
       (= (:set-index event) set-index)))

(defn last-performance
  "Find the most recent completed set for the same slot in a PREVIOUS microcycle.
  Returns nil if no history exists."
  [events {:keys [microcycle] :as location}]
  (->> events
       (filter #(= (:type %) :set-completed))
       (filter #(same-slot? % location))
       (filter #(< (:microcycle %) microcycle))  ; only previous weeks
       (sort-by :timestamp)
       last))

(defn all-performances
  "Get all completed sets for this slot, across all microcycles."
  [events location]
  (->> events
       (filter #(= (:type %) :set-completed))
       (filter #(same-slot? % location))
       (sort-by :timestamp)))

;; -----------------------------------------------------------------------------
;; Prescription
;; -----------------------------------------------------------------------------

(defn prescribe-weight
  "Suggest weight for next set: last weight + increment.
  Returns nil if no history (first workout of meso)."
  [events location]
  (when-let [last-perf (last-performance events location)]
    (+ (:performed-weight last-perf) weight-increment)))

(defn prescribe-reps
  "Suggest reps for next set.
  
  If actual-weight is provided and differs from prescribed weight,
  adjusts reps to maintain similar total work.
  
  Returns nil if no history."
  ([events location]
   (prescribe-reps events location nil))
  ([events location actual-weight]
   (when-let [last-perf (last-performance events location)]
     (let [last-weight (:performed-weight last-perf)
           last-reps (:performed-reps last-perf)
           prescribed-weight (+ last-weight weight-increment)
           target-work (* prescribed-weight last-reps)]
       (if (and actual-weight (not= actual-weight prescribed-weight))
         ;; User overrode weight → adjust reps to maintain work
         (max 1 (js/Math.round (/ target-work actual-weight)))
         ;; Use last reps as target
         last-reps)))))

(defn prescribe
  "Get full prescription for a set location.
  Returns {:weight ... :reps ...} or nil for each if no history."
  ([events location]
   (prescribe events location nil))
  ([events location actual-weight]
   {:weight (prescribe-weight events location)
    :reps (prescribe-reps events location actual-weight)}))

;; -----------------------------------------------------------------------------
;; Analysis (for future feedback-based volume adjustment)
;; -----------------------------------------------------------------------------

(defn exercise-volume
  "Count completed sets for an exercise in a microcycle."
  [events {:keys [mesocycle microcycle workout exercise]}]
  (->> events
       (filter #(= (:type %) :set-completed))
       (filter #(and (= (:mesocycle %) mesocycle)
                     (= (:microcycle %) microcycle)
                     (= (keyword (:workout %)) (keyword workout))
                     (= (:exercise %) exercise)))
       count))
