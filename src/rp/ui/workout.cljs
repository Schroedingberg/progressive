(ns rp.ui.workout
  "Workout tracking: set rows and exercise cards."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [rp.db :as db]
            [rp.progression :as prog]))

(def ^:private row-style
  {:display "flex" :gap "0.5rem" :align-items "center" :margin-bottom "0.5rem"})

(defn set-row
  "Single set input/display row."
  [_loc _set-data _muscle-groups]
  (let [weight (r/atom "")
        reps (r/atom "")]
    (fn [loc set-data muscle-groups]
      (let [{:keys [performed-weight performed-reps type]} set-data
            done? (or performed-weight (#{:set-skipped :set-rejected} type))
            prescription (prog/prescribe (db/get-all-events) loc
                                         (when (seq @weight) (js/parseFloat @weight))
                                         muscle-groups)]
        [:div {:style row-style}
         ;; Weight input
         [:input {:type "number"
                  :style {:width "5rem"}
                  :value (if done? (str performed-weight) @weight)
                  :placeholder (str (:weight prescription) " kg")
                  :disabled done?
                  :on-change #(reset! weight (-> % .-target .-value))}]
         [:span "×"]
         ;; Reps input
         [:input {:type "number"
                  :style {:width "4rem"}
                  :value (if done? (str performed-reps) @reps)
                  :placeholder (str (:reps prescription))
                  :disabled done?
                  :on-change #(reset! reps (-> % .-target .-value))}]
         ;; Action buttons
         (cond
           (= type :set-skipped)
           [:span {:style {:opacity 0.5}} "skipped"]

           (= type :set-rejected)
           [:span {:style {:opacity 0.5}} "rejected"]

           performed-weight
           [:span "✓"]

           :else
           [:<>
            [:input {:type "checkbox"
                     :checked false
                     :on-change #(when (and (seq @weight) (seq @reps))
                                   (db/log-set! (assoc loc
                                                       :weight (js/parseFloat @weight)
                                                       :reps (js/parseInt @reps)
                                                       :prescribed-weight (:weight prescription)
                                                       :prescribed-reps (:reps prescription))))}]
            [:button.secondary.outline
             {:type "button"
              :style {:padding "0.25rem 0.5rem" :margin 0}
              :on-click #(when (js/confirm "Skip?") (db/skip-set! loc))}
             "skip"]])]))))

(defn exercise-card
  "Card showing all sets for one exercise."
  [_mesocycle _microcycle _workout-key _exercise-name _sets]
  (let [extra-sets (r/atom 0)]
    (fn [mesocycle microcycle workout-key exercise-name sets]
      (let [muscle-groups (some :muscle-groups sets)
            total (+ (count sets) @extra-sets)]
        [:article
         [:h4 exercise-name
          (when muscle-groups
            [:small {:style {:font-weight "normal" :margin-left "0.5rem" :opacity 0.6}}
             (str/join ", " (map name muscle-groups))])]
         (for [idx (range total)]
           ^{:key idx}
           [set-row
            {:mesocycle mesocycle :microcycle microcycle :workout workout-key
             :exercise exercise-name :set-index idx}
            (get sets idx {})
            muscle-groups])
         [:button.outline
          {:type "button"
           :style {:padding "0.25rem 0.75rem"}
           :on-click #(swap! extra-sets inc)}
          "+ Add set"]]))))
