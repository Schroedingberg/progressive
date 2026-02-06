(ns rp.progression
  "Compute workout prescriptions from training history.
  
  Core principles:
  - No planning ahead — prescriptions computed on-the-fly from events
  - Weight-first progression — add weight each session
  - Work preservation — if user overrides weight, adjust reps to maintain
    similar total work increase
  - Feedback-driven — adjust weight increment based on recovery and session feedback
  
  Work model: work ≈ weight × reps (simplified)
  
  Example:
    Last session: 100kg × 10 = 1000 work
    Prescribed:   102.5kg × 10 = 1025 work (+2.5%)
    User picks 110kg → reps adjusted to 1025/110 ≈ 9 reps")

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(def ^:private base-weight-increment 2.5)  ; kg to add each session

;; Feedback-based increment modifiers
(def ^:private soreness-modifiers
  {:never-sore         1.5    ; Recovered fast, push harder
   :healed-early       1.25   ; Recovered well, slight increase
   :healed-just-in-time 1.0   ; Perfect recovery, maintain
   :still-sore         0.5})  ; Still recovering, back off

(def ^:private workload-modifiers
  {:easy          1.25   ; Session felt easy, push harder
   :just-right    1.0    ; Perfect effort
   :pushed-limits 1.0    ; Good challenge, maintain
   :too-much      0.75}) ; Overreached, reduce

(def ^:private joint-pain-override
  {:none   nil      ; No override
   :some   0.75     ; Reduce due to discomfort
   :severe 0.0})    ; Zero increase with pain

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
;; Feedback queries
;; -----------------------------------------------------------------------------

(defn- get-feedback
  "Get the latest feedback of given type for a muscle group from previous microcycle."
  [events event-type {:keys [mesocycle microcycle]} muscle-group]
  (->> events
       (filter #(= (:type %) event-type))
       (filter #(= (:mesocycle %) mesocycle))
       (filter #(= (:microcycle %) (dec microcycle))) ; previous week's feedback
       (filter #(some #{(:muscle-group %)} (if (keyword? muscle-group)
                                             [muscle-group]
                                             muscle-group)))
       (sort-by :timestamp)
       last))

(defn- compute-weight-increment
  "Calculate weight increment based on feedback from previous microcycle.
  Returns the adjusted increment (may be 0 if joint pain is severe)."
  [events location muscle-groups]
  (if (or (nil? muscle-groups) (<= (:microcycle location) 0))
    ;; No muscle groups or first week: use base increment
    base-weight-increment
    ;; Check feedback for any of the exercise's muscle groups
    (let [mg (first muscle-groups)  ; Use primary muscle group
          soreness (get-feedback events :soreness-reported location mg)
          session (get-feedback events :session-rated location mg)

          ;; Calculate modifiers
          soreness-mod (get soreness-modifiers (:soreness soreness) 1.0)
          workload-mod (get workload-modifiers (:sets-workload session) 1.0)
          pain-override (get joint-pain-override (:joint-pain session))]

      ;; Joint pain overrides everything
      (if (some? pain-override)
        (* base-weight-increment pain-override)
        ;; Otherwise combine modifiers
        (* base-weight-increment soreness-mod workload-mod)))))

;; -----------------------------------------------------------------------------
;; Prescription
;; -----------------------------------------------------------------------------

(defn prescribe-weight
  "Suggest weight for next set: last weight + feedback-adjusted increment.
  Returns nil if no history (first workout of meso).
  
  muscle-groups is optional - if provided, feedback for those groups affects increment."
  ([events location]
   (prescribe-weight events location nil))
  ([events location muscle-groups]
   (when-let [last-perf (last-performance events location)]
     (let [increment (compute-weight-increment events location muscle-groups)]
       (+ (:performed-weight last-perf) increment)))))

(defn prescribe-reps
  "Suggest reps for next set.
  
  If actual-weight is provided and differs from prescribed weight,
  adjusts reps to maintain similar total work.
  
  Returns nil if no history."
  ([events location]
   (prescribe-reps events location nil nil))
  ([events location actual-weight]
   (prescribe-reps events location actual-weight nil))
  ([events location actual-weight muscle-groups]
   (when-let [last-perf (last-performance events location)]
     (let [last-weight (:performed-weight last-perf)
           last-reps (:performed-reps last-perf)
           increment (compute-weight-increment events location muscle-groups)
           prescribed-weight (+ last-weight increment)
           target-work (* prescribed-weight last-reps)]
       (if (and actual-weight (not= actual-weight prescribed-weight))
         ;; User overrode weight → adjust reps to maintain work
         (max 1 (js/Math.round (/ target-work actual-weight)))
         ;; Use last reps as target
         last-reps)))))

(defn prescribe
  "Get full prescription for a set location.
  Returns {:weight ... :reps ...} or nil for each if no history.
  
  muscle-groups is optional - if provided, feedback for those groups affects increment."
  ([events location]
   (prescribe events location nil nil))
  ([events location actual-weight]
   (prescribe events location actual-weight nil))
  ([events location actual-weight muscle-groups]
   {:weight (prescribe-weight events location muscle-groups)
    :reps (prescribe-reps events location actual-weight muscle-groups)}))

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
