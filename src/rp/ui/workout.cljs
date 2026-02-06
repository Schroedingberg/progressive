(ns rp.ui.workout
  "Workout tracking components: set rows, exercise cards.
  
  Component hierarchy:
    exercise-card
    └── set-row (stateful orchestrator)
        ├── skipped-set
        ├── completed-set  
        └── editable-set
            ├── weight-reps-inputs
            └── set-actions"
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [rp.db :as db]
            [rp.progression :as prog]))

;; -----------------------------------------------------------------------------
;; Styles
;; -----------------------------------------------------------------------------

(def ^:private btn-style {:padding "0.25rem 0.5rem" :margin 0})
(def ^:private row-style {:display "flex" :gap "0.5rem" :align-items "center" :margin-bottom "0.5rem"})
(def ^:private faded {:opacity "0.5"})
(def ^:private struck {:text-decoration "line-through" :opacity "0.5"})

;; -----------------------------------------------------------------------------
;; Primitives
;; -----------------------------------------------------------------------------

(defn- weight-reps-inputs
  "Paired weight × reps inputs."
  [{:keys [weight reps weight-placeholder reps-placeholder disabled? struck? on-weight on-reps]}]
  [:<>
   [:input {:type "number" :style (merge {:width "5rem"} (when struck? struck))
            :value weight :placeholder weight-placeholder
            :disabled disabled? :on-change #(on-weight (-> % .-target .-value))}]
   [:span "×"]
   [:input {:type "number" :style (merge {:width "4rem"} (when struck? struck))
            :value reps :placeholder reps-placeholder
            :disabled disabled? :on-change #(on-reps (-> % .-target .-value))}]])

(defn- icon-btn [class icon opts]
  [(keyword (str "button." class))
   (merge {:type "button" :style btn-style} opts)
   icon])

;; -----------------------------------------------------------------------------
;; Set display variants (stateless)
;; -----------------------------------------------------------------------------

(defn- skipped-set
  "A set that was skipped - display only."
  [{:keys [weight reps]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps :disabled? true :struck? true
                        :on-weight identity :on-reps identity}]
   [icon-btn "secondary.outline" "⊘" {:title "Skipped" :style faded}]])

(defn- completed-set
  "A set that was completed - display with edit action."
  [{:keys [weight reps on-edit]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps :disabled? true
                        :on-weight identity :on-reps identity}]
   [icon-btn "outline" "✓" {:on-click on-edit}]])

(defn- editable-set
  "A set being entered or edited."
  [{:keys [weight reps weight-placeholder reps-placeholder
           on-weight on-reps on-submit on-skip on-cancel show-cancel?]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps
                        :weight-placeholder weight-placeholder
                        :reps-placeholder reps-placeholder
                        :on-weight on-weight :on-reps on-reps}]
   [:input {:type "checkbox" :checked false :on-change on-submit}]
   (if show-cancel?
     [icon-btn "secondary.outline" "✕" {:on-click on-cancel}]
     [icon-btn "secondary.outline" "Skip" {:on-click on-skip :style {:font-size "0.8rem"}}])])

;; -----------------------------------------------------------------------------
;; Set row (stateful orchestrator)
;; -----------------------------------------------------------------------------

(defn set-row
  "Stateful wrapper that chooses which set variant to render."
  [_mesocycle _microcycle _workout _exercise _set-index _set-data]
  (let [input-weight (r/atom "")
        input-reps (r/atom "")
        editing? (r/atom false)]
    (fn [mesocycle microcycle workout exercise set-index set-data]
      (let [{:keys [performed-weight performed-reps type]} set-data
            loc {:mesocycle mesocycle :microcycle microcycle :workout workout
                 :exercise exercise :set-index set-index}

            ;; Prescription
            events (db/get-all-events)
            user-wt (when (seq @input-weight) (js/parseFloat @input-weight))
            {:keys [weight reps]} (prog/prescribe events loc user-wt)]

        (cond
          ;; Skipped
          (= type :set-skipped)
          [skipped-set {:weight performed-weight :reps performed-reps}]

          ;; Completed (not editing)
          (and performed-weight (not @editing?))
          [completed-set {:weight performed-weight :reps performed-reps
                          :on-edit #(do (reset! input-weight (str performed-weight))
                                        (reset! input-reps (str performed-reps))
                                        (reset! editing? true))}]

          ;; Entry or editing
          :else
          [editable-set {:weight @input-weight
                         :reps @input-reps
                         :weight-placeholder (when weight (str weight " kg"))
                         :reps-placeholder (when reps (str reps))
                         :on-weight #(reset! input-weight %)
                         :on-reps #(reset! input-reps %)
                         :on-submit #(when (and (seq @input-weight) (seq @input-reps))
                                       (db/log-set! (assoc loc
                                                           :weight (js/parseFloat @input-weight)
                                                           :reps (js/parseInt @input-reps)
                                                           :prescribed-weight weight
                                                           :prescribed-reps reps))
                                       (reset! editing? false))
                         :on-skip #(db/skip-set! loc)
                         :on-cancel #(reset! editing? false)
                         :show-cancel? @editing?}])))))

;; -----------------------------------------------------------------------------
;; Exercise card
;; -----------------------------------------------------------------------------

(defn exercise-card [mesocycle microcycle workout-key exercise-name sets]
  (let [muscle-groups (some :muscle-groups sets)]
    [:article
     [:h4 exercise-name
      (when muscle-groups
        [:small {:style {:font-weight "normal" :margin-left "0.5rem" :color "var(--pico-muted-color)"}}
         (str/join ", " (map name muscle-groups))])]
     (doall
      (for [[idx set-data] (map-indexed vector sets)]
        ^{:key idx}
        [set-row mesocycle microcycle workout-key exercise-name idx set-data]))]))
