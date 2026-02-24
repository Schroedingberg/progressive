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
(def ^:private menu-style {:position "relative" :display "inline-block"})
(def ^:private menu-dropdown-style {:position "absolute" :right 0 :top "100%" :z-index 10
                                    :background "var(--pico-background-color)"
                                    :border "1px solid var(--pico-muted-border-color)"
                                    :border-radius "0.25rem" :padding "0.25rem 0"
                                    :min-width "8rem" :box-shadow "0 2px 8px rgba(0,0,0,0.15)"})
(def ^:private menu-item-style {:display "block" :width "100%" :padding "0.375rem 0.75rem"
                                :background "none" :border "none" :text-align "left"
                                :cursor "pointer" :font-size "0.875rem"
                                :color "inherit"})

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

(defn- action-menu
  "Dropdown menu triggered by ⋯ button. Items is [{:label :on-click}]."
  []
  (let [open? (r/atom false)]
    (fn [{:keys [items]}]
      [:div {:style menu-style}
       [:button.secondary.outline {:type "button" :style btn-style
                                   :on-click #(swap! open? not)}
        "⋯"]
       (when @open?
         [:div {:style menu-dropdown-style}
          (for [{:keys [label on-click]} items]
            ^{:key label}
            [:div {:style menu-item-style
                   :role "button"
                   :tab-index 0
                   :on-click #(do (reset! open? false) (on-click))}
             label])])])))

;; -----------------------------------------------------------------------------
;; Set display variants (stateless)
;; -----------------------------------------------------------------------------

(defn- skipped-set
  "A set that was skipped - display only."
  [{:keys [weight reps]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps :disabled? true :struck? true
                        :on-weight identity :on-reps identity}]
   [:div {:style {:margin-left "auto"}}
    [icon-btn "secondary.outline" "⊘" {:title "Skipped" :style faded}]]])

(defn- rejected-set
  "A set that was rejected (volume cap) - display only."
  [{:keys [weight reps]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps :disabled? true :struck? true
                        :on-weight identity :on-reps identity}]
   [:div {:style {:margin-left "auto"}}
    [icon-btn "secondary.outline" "✕" {:title "Rejected (volume cap)" :style faded}]]])

(defn- completed-set
  "A set that was completed - display with edit action."
  [{:keys [weight reps on-edit]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps :disabled? true
                        :on-weight identity :on-reps identity}]
   [:div {:style {:margin-left "auto"}}
    [icon-btn "outline" "✓" {:on-click on-edit}]]])

(defn- editable-set
  "A set being entered or edited."
  [{:keys [weight reps weight-placeholder reps-placeholder
           on-weight on-reps on-submit on-skip on-reject on-swap on-cancel show-cancel?]}]
  [:form {:style row-style}
   [weight-reps-inputs {:weight weight :reps reps
                        :weight-placeholder weight-placeholder
                        :reps-placeholder reps-placeholder
                        :on-weight on-weight :on-reps on-reps}]
   [:input {:type "checkbox" :checked false :on-change on-submit}]
   [:div {:style {:margin-left "auto"}}
    (if show-cancel?
      [icon-btn "secondary.outline" "✕" {:on-click on-cancel}]
      [action-menu {:items [{:label "Skip set"
                             :on-click #(when (js/confirm "Skip this set?")
                                          (on-skip))}
                            {:label "Reject (volume cap)"
                             :on-click #(when (js/confirm "Reject this set? (volume cap reached)")
                                          (on-reject))}
                            {:label "Swap exercise"
                             :on-click on-swap}]}])]])

;; -----------------------------------------------------------------------------
;; Set row (stateful orchestrator)
;; -----------------------------------------------------------------------------

(defn set-row
  "Stateful wrapper that chooses which set variant to render."
  [_mesocycle _microcycle _workout _exercise _set-index _set-data _original-exercise _muscle-groups]
  (let [input-weight (r/atom "")
        input-reps (r/atom "")
        editing? (r/atom false)
        swapping? (r/atom false)
        swap-name (r/atom "")]
    (fn [mesocycle microcycle workout exercise set-index set-data original-exercise muscle-groups]
      (let [{:keys [performed-weight performed-reps type]} set-data
            loc {:mesocycle mesocycle :microcycle microcycle :workout workout
                 :exercise exercise :set-index set-index}

            ;; Prescription (with feedback-based increment)
            events (db/get-all-events)
            user-wt (when (seq @input-weight) (js/parseFloat @input-weight))
            {:keys [weight reps]} (prog/prescribe events loc user-wt muscle-groups)]

        (if @swapping?
          ;; Swap UI inline
          [:div {:style {:display "flex" :gap "0.5rem" :margin-bottom "0.5rem" :align-items "center"}}
           [:input {:type "text"
                    :placeholder "Replacement exercise name"
                    :value @swap-name
                    :style {:flex 1}
                    :on-change #(reset! swap-name (-> % .-target .-value))}]
           [:button.secondary {:type "button"
                               :disabled (empty? @swap-name)
                               :on-click #(when (js/confirm (str "Swap " exercise " for " @swap-name "?"))
                                            (db/swap-exercise!
                                             {:mesocycle mesocycle
                                              :microcycle microcycle
                                              :workout workout
                                              :original-exercise (or original-exercise exercise)
                                              :replacement-exercise @swap-name
                                              :muscle-groups muscle-groups})
                                            (reset! swapping? false)
                                            (reset! swap-name ""))}
            "Confirm"]
           [:button.secondary.outline {:type "button"
                                       :on-click #(do (reset! swapping? false)
                                                      (reset! swap-name ""))}
            "Cancel"]]

          ;; Normal set display
          (cond
            ;; Skipped
            (= type :set-skipped)
            [skipped-set {:weight performed-weight :reps performed-reps}]

            ;; Rejected
            (= type :set-rejected)
            [rejected-set {:weight performed-weight :reps performed-reps}]

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
                           :on-reject #(db/reject-set! loc)
                           :on-swap #(reset! swapping? true)
                           :on-cancel #(reset! editing? false)
                           :show-cancel? @editing?}]))))))

;; -----------------------------------------------------------------------------
;; Exercise card
;; -----------------------------------------------------------------------------

(defn exercise-card [_mesocycle _microcycle _workout-key _exercise-name _sets]
  (let [extra-sets (r/atom 0)]
    (fn [mesocycle microcycle workout-key exercise-name sets]
      (let [muscle-groups (some :muscle-groups sets)
            original-exercise (some :original-exercise sets)
            total-sets (+ (count sets) @extra-sets)]
        [:article
         [:h4 {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
          exercise-name
          (when original-exercise
            [:small {:style {:opacity 0.5}} (str "(was: " original-exercise ")")])
          (when muscle-groups
            [:small {:style {:font-weight "normal" :margin-left "0.5rem" :color "var(--pico-muted-color)"}}
             (str/join ", " (map name muscle-groups))])]
         (doall
          (for [idx (range total-sets)
                :let [set-data (get sets idx {})]]
            ^{:key idx}
            [set-row mesocycle microcycle workout-key exercise-name idx set-data original-exercise muscle-groups]))
         [:button.secondary.outline
          {:type "button"
           :style {:margin-top "0.5rem" :padding "0.25rem 0.75rem"}
           :on-click #(swap! extra-sets inc)}
          "+ Add set"]]))))
