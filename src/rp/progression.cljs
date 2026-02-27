(ns rp.progression
  "Compute workout prescriptions from training history.
  
  Design: Build a context map, then thread through pure transformations.
  
  Context map contains:
    :last-weight, :last-reps     - previous performance
    :increment                   - feedback-adjusted weight step
    :one-rep-max                 - estimated from last performance
    :prescribed-weight           - last-weight + increment
    :actual-weight               - user's chosen weight (optional)
  
  Invariant: lower weight → equal or more reps")

;; =============================================================================
;; 1RM Curve (pure math, no events)
;; =============================================================================

(def ^:private reps-percentage-table
  "Known reps↔%1RM anchor points."
  [[1 1.00] [2 0.95] [3 0.93] [4 0.90] [5 0.87] [6 0.85] [7 0.83]
   [8 0.80] [9 0.77] [10 0.75] [11 0.72] [12 0.70] [15 0.65]
   [20 0.60] [25 0.55] [30 0.50]])

(defn- interpolate
  "Linear interpolation: given sorted [x y] points, find y at x."
  [points x]
  (let [[lo hi] (reduce (fn [[lo hi] [px _ :as point]]
                          (cond
                            (<= px x) [point hi]
                            (nil? hi) [lo point]
                            :else [lo hi]))
                        [nil nil]
                        points)
        [x1 y1] (or lo (first points))
        [x2 y2] (or hi (last points))]
    (if (= x1 x2)
      y1
      (let [t (/ (- x x1) (- x2 x1))]
        (+ y1 (* t (- y2 y1)))))))

(def ^:private percentage-reps-table
  "Inverted table: %1RM → reps (sorted ascending by %)."
  (vec (sort-by first (map (fn [[r p]] [p r]) reps-percentage-table))))

(defn- reps->percentage
  "What %1RM corresponds to this rep count?"
  [reps]
  (interpolate reps-percentage-table (max 1 (min 30 reps))))

(defn- percentage->reps
  "How many reps at this %1RM?"
  [percentage]
  (interpolate percentage-reps-table (max 0.5 (min 1.0 percentage))))

(defn- estimate-one-rep-max
  "Estimate 1RM from a weight×reps performance."
  [weight reps]
  (/ weight (reps->percentage reps)))

(defn- reps-at-weight
  "How many reps should be possible at this weight, given a 1RM?"
  [one-rep-max weight]
  (-> (/ weight one-rep-max)
      percentage->reps
      js/Math.round
      (max 1)))

;; =============================================================================
;; History queries
;; =============================================================================

(defn- same-slot?
  "Does event match this location (ignoring microcycle)?"
  [{:keys [mesocycle workout exercise set-index]} event]
  (and (= mesocycle (:mesocycle event))
       (= (keyword workout) (keyword (:workout event)))
       (= exercise (:exercise event))
       (= set-index (:set-index event))))

(defn last-performance
  "Most recent completed set for this slot from a previous microcycle."
  [events {:keys [microcycle] :as location}]
  (->> events
       (filter #(and (= :set-completed (:type %))
                     (same-slot? location %)
                     (< (:microcycle %) microcycle)))
       (sort-by :timestamp)
       last))

(defn all-performances
  "All completed sets for this slot, across all microcycles."
  [events location]
  (->> events
       (filter #(and (= :set-completed (:type %)) (same-slot? location %)))
       (sort-by :timestamp)))

;; =============================================================================
;; Feedback → increment multiplier (pure data transform)
;; =============================================================================

(def ^:private base-increment 2.5)

(def ^:private soreness-multiplier
  {:never-sore 1.5, :healed-early 1.25, :healed-just-in-time 1.0, :still-sore 0.5})

(def ^:private workload-multiplier
  {:easy 1.25, :just-right 1.0, :pushed-limits 1.0, :too-much 0.75})

(def ^:private joint-pain-multiplier
  {:none 1.0, :some 0.75, :severe 0.0})

(defn- feedback-multiplier
  "Compute increment multiplier from soreness and session feedback.
   Joint pain overrides other factors when present."
  [{:keys [soreness joint-pain workload]}]
  (if (and joint-pain (not= joint-pain :none))
    (joint-pain-multiplier joint-pain)
    (* (get soreness-multiplier soreness 1.0)
       (get workload-multiplier workload 1.0))))

(defn- find-feedback
  "Find relevant feedback events for a muscle group from previous microcycle."
  [events {:keys [mesocycle microcycle]} muscle-group]
  (let [prev-micro (dec microcycle)
        matching (fn [event-type]
                   (->> events
                        (filter #(and (= event-type (:type %))
                                      (= mesocycle (:mesocycle %))
                                      (= prev-micro (:microcycle %))
                                      (= muscle-group (:muscle-group %))))
                        (sort-by :timestamp)
                        last))]
    {:soreness   (:soreness (matching :soreness-reported))
     :joint-pain (:joint-pain (matching :session-rated))
     :workload   (:sets-workload (matching :session-rated))}))

(defn- compute-increment
  "Compute feedback-adjusted increment for this context."
  [events location muscle-groups]
  (if (or (nil? muscle-groups) (<= (:microcycle location) 0))
    base-increment
    (let [feedback (find-feedback events location (first muscle-groups))]
      (* base-increment (feedback-multiplier feedback)))))

;; =============================================================================
;; Context building & prescription (threaded design)
;; =============================================================================

(defn- build-context
  "Build prescription context from events and location.
   Returns nil if no history exists."
  [events location muscle-groups actual-weight]
  (when-let [{:keys [performed-weight performed-reps]} (last-performance events location)]
    (let [increment (compute-increment events location muscle-groups)
          one-rep-max (estimate-one-rep-max performed-weight performed-reps)]
      {:last-weight      performed-weight
       :last-reps        performed-reps
       :increment        increment
       :one-rep-max      one-rep-max
       :prescribed-weight (+ performed-weight increment)
       :actual-weight    actual-weight})))

(defn- compute-reps
  "Compute prescribed reps from context.
   Invariant: lower weight → at least as many reps as last time."
  [{:keys [last-reps one-rep-max prescribed-weight actual-weight] :as context}]
  (when context
    (cond
      (nil? actual-weight)
      last-reps

      (<= actual-weight prescribed-weight)
      (max last-reps (reps-at-weight one-rep-max actual-weight))

      :else
      (reps-at-weight one-rep-max actual-weight))))

(defn- compute-weight
  "Extract prescribed weight from context."
  [{:keys [prescribed-weight] :as context}]
  (when context prescribed-weight))

;; =============================================================================
;; Public API
;; =============================================================================

(defn prescribe-weight
  "Prescribed weight: last + feedback-adjusted increment. Nil if no history."
  ([events location] (prescribe-weight events location nil))
  ([events location muscle-groups]
   (-> (build-context events location muscle-groups nil)
       compute-weight)))

(defn prescribe-reps
  "Prescribed reps, adjusted for actual weight if provided.
   Invariant: lower weight → at least as many reps."
  ([events location] (prescribe-reps events location nil nil))
  ([events location actual-weight] (prescribe-reps events location actual-weight nil))
  ([events location actual-weight muscle-groups]
   (-> (build-context events location muscle-groups actual-weight)
       compute-reps)))

(defn prescribe
  "Full prescription {:weight :reps}."
  ([events location] (prescribe events location nil nil))
  ([events location actual-weight] (prescribe events location actual-weight nil))
  ([events location actual-weight muscle-groups]
   (let [context (build-context events location muscle-groups actual-weight)]
     {:weight (compute-weight context)
      :reps   (compute-reps context)})))

;; -----------------------------------------------------------------------------
;; Analysis
;; -----------------------------------------------------------------------------

(defn exercise-volume
  "Completed sets for an exercise in a microcycle."
  [events {:keys [mesocycle microcycle workout exercise]}]
  (->> events
       (filter #(and (= :set-completed (:type %))
                     (= mesocycle (:mesocycle %))
                     (= microcycle (:microcycle %))
                     (= (keyword workout) (keyword (:workout %)))
                     (= exercise (:exercise %))))
       count))