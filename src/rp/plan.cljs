(ns rp.plan
  "Training plan templates and expansion.
  
  A template defines workouts with exercises and set counts.
  The `->plan` function expands this into the full structure
  used by the app: {plan-name {week# {day {exercise [sets...]}}}}
  
  Plans are persisted to localStorage."
  (:require [cljs.reader :as reader]))

(def ^:private PLAN-KEY "rp-plan-template")

;; Default template - exercises with metadata
(def default-template
  {:name "Twice a week upper body focus"
   :n-microcycles 4
   :workouts
   {:monday
    {:exercises {"Dumbbell Press (Incline)" {:n-sets 2 :muscle-groups [:chest]}
                 "Cable Triceps Pushdown (Bar)" {:n-sets 3 :muscle-groups [:triceps]}
                 "Seated Cable Row" {:n-sets 3 :muscle-groups [:back]}
                 "Lying Biceps Dumbbell Curl" {:n-sets 2 :muscle-groups [:biceps]}
                 "Barbell Upright Row" {:n-sets 3 :muscle-groups [:shoulders]}
                 "Barbell Squat (High Bar)" {:n-sets 2 :muscle-groups [:quads]}
                 "Bodyweight Squat" {:n-sets 1 :muscle-groups [:quads]}
                 "Back Raise" {:n-sets 1 :muscle-groups [:hamstrings]}}}
    :thursday
    {:exercises {"Pulldown (Narrow Grip)" {:n-sets 2 :muscle-groups [:back]}
                 "Cable Flexion Row" {:n-sets 2 :muscle-groups [:back]}
                 "Barbell Curl (Narrow Grip)" {:n-sets 3 :muscle-groups [:biceps]}
                 "Cable Overhead Triceps Extension" {:n-sets 3 :muscle-groups [:triceps]}
                 "Pushup (Deficit)" {:n-sets 2 :muscle-groups [:chest]}
                 "Dumbbell Shoulder Press" {:n-sets 4 :muscle-groups [:shoulders]}
                 "Back Raise" {:n-sets 1 :muscle-groups [:hamstrings]}
                 "Barbell Squat (High Bar)" {:n-sets 2 :muscle-groups [:quads]}
                 "Bodyweight Squat" {:n-sets 1 :muscle-groups [:quads]}}}}})

(def other-template
  {:name "2x Minimal Full Body"
   :n-microcycles 4
   :workouts
   {:monday
    {:exercises {"Dumbbell Row" {:n-sets 2 :muscle-groups [:back]}
                 "Dumbbell Press (Incline)" {:n-sets 2 :muscle-groups [:chest :shoulders]}
                 "Lying Dumbbell Curl" {:n-sets 3 :muscle-groups [:biceps]}
                 "Back Raise" {:n-sets 1 :muscle-groups [:hamstrings]}
                 "Reverse Lunge Dumbbell" {:n-sets 2 :muscle-groups [:glutes :quads]}
                 "Sissy squat" {:n-sets 2 :muscle-groups [:quads]}}}
    :thursday
    {:exercises {"Back Raise" {:n-sets 1 :muscle-groups [:hamstrings]}
                 "Barbell Squat" {:n-sets 2 :muscle-groups [:glutes :quads]}
                 "Bench press (Narrow Grip)" {:n-sets 2 :muscle-groups [:chest :triceps]}
                 "Pullup (Underhand Grip)" {:n-sets 2 :muscle-groups [:back :biceps]}}}}})

(defn- expand-exercises
  "Expand {:n-sets 3 ...} into a vector of 3 set maps."
  [{:keys [exercises]}]
  (reduce-kv
   (fn [m name {:keys [n-sets] :as ex}]
     (assoc m name (vec (repeat n-sets (-> ex (dissoc :n-sets) (assoc :exercise-name name))))))
   (array-map)
   exercises))

(defn ->plan
  "Expand a template into the full plan structure."
  [{:keys [name n-microcycles workouts]}]
  (let [expanded (update-vals workouts expand-exercises)]
    {name (into (sorted-map)
                (zipmap (range n-microcycles)
                        (repeat n-microcycles expanded)))}))

;; --- Storage ---

(defonce ^:private template-atom (atom nil))

(defn load-template []
  (or (some-> (.getItem js/localStorage PLAN-KEY) reader/read-string)
      default-template))

(defn get-template []
  (when (nil? @template-atom)
    (reset! template-atom (load-template)))
  @template-atom)

(defn get-plan []
  (->plan (get-template)))

(defn get-plan-name []
  (:name (get-template)))

(def available-templates
  "All built-in plan templates."
  [default-template other-template])

(defn set-template!
  "Switch to a different plan template and persist to localStorage."
  [template]
  (.setItem js/localStorage PLAN-KEY (pr-str template))
  (reset! template-atom template))
