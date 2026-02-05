(ns rp.ui
  "Reagent components for the workout tracking UI.
  
  Component hierarchy:
    app
    ├── nav-menu (navigation)
    └── current page:
        ├── workouts-page
        │   └── microcycle-section (week)
        │       └── workout-section (day)
        │           └── exercise-card
        │               └── set-row (weight/reps input)
        ├── plans-page
        └── settings-page"
  (:require [reagent.core :as r]
            [rp.db :as db]
            [rp.plan :as plan]
            [rp.state :as state]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Navigation state
;; -----------------------------------------------------------------------------

(defonce current-page (r/atom :workouts))

(defn- set-row
  "A single set with weight/reps inputs."
  [mesocycle microcycle workout exercise set-index set-data]
  (let [weight (r/atom "")
        reps (r/atom "")]
    (fn [mesocycle microcycle workout exercise set-index set-data]
      (let [{:keys [performed-weight performed-reps prescribed-weight prescribed-reps]} set-data
            completed? (some? performed-weight)]
        [:form {:style {:display "flex" :gap "0.5rem" :align-items "center" :margin-bottom "0.5rem"}}
         [:input {:type "number"
                  :placeholder (if prescribed-weight (str prescribed-weight " kg") "kg")
                  :value (if completed? performed-weight @weight)
                  :disabled completed?
                  :on-change #(reset! weight (-> % .-target .-value))
                  :style {:width "5rem"}}]
         [:span "×"]
         [:input {:type "number"
                  :placeholder (if prescribed-reps (str prescribed-reps) "reps")
                  :value (if completed? performed-reps @reps)
                  :disabled completed?
                  :on-change #(reset! reps (-> % .-target .-value))
                  :style {:width "4rem"}}]
         [:input {:type "checkbox"
                  :checked completed?
                  :disabled completed?
                  :on-change (fn [_]
                               (when (and (seq @weight) (seq @reps))
                                 (db/log-set!
                                  {:mesocycle mesocycle
                                   :microcycle microcycle
                                   :workout workout
                                   :exercise exercise
                                   :set-index set-index
                                   :weight (js/parseFloat @weight)
                                   :reps (js/parseInt @reps)
                                   :prescribed-weight prescribed-weight
                                   :prescribed-reps prescribed-reps})))}]
         (when completed? [:span "✓"])]))))

(defn- exercise-card
  "An exercise with its sets."
  [mesocycle microcycle workout-key exercise-name sets]
  (let [muscle-groups (some :muscle-groups sets)]
    [:article {:key exercise-name}
     [:h4 exercise-name
      (when muscle-groups
        [:small {:style {:font-weight "normal" :margin-left "0.5rem" :color "var(--pico-muted-color)"}}
         (str/join ", " (map name muscle-groups))])]
     (for [[idx set-data] (map-indexed vector sets)]
       ^{:key idx}
       [set-row mesocycle microcycle workout-key exercise-name idx set-data])]))

(defn- workout-section
  "A workout day with its exercises."
  [mesocycle microcycle workout-key exercises-map]
  [:section {:key (name workout-key)}
   [:h3 (str/capitalize (name workout-key))]
   (for [[exercise-name sets] exercises-map]
     ^{:key exercise-name}
     [exercise-card mesocycle microcycle workout-key exercise-name sets])])

(defn- microcycle-section
  "A week with its workouts."
  [mesocycle-name microcycle-idx workouts-map]
  [:section {:key microcycle-idx}
   [:h2 (str "Week " (inc microcycle-idx))]
   (for [[workout-key exercises-map] workouts-map]
     ^{:key workout-key}
     [workout-section mesocycle-name microcycle-idx workout-key exercises-map])])

;; -----------------------------------------------------------------------------
;; Navigation menu
;; -----------------------------------------------------------------------------

(defn- nav-menu
  "Top navigation bar with page links."
  []
  (let [nav-item (fn [page label]
                   [:li [:a {:href "#"
                             :class (when (= @current-page page) "contrast")
                             :on-click (fn [e]
                                         (.preventDefault e)
                                         (reset! current-page page))}
                         label]])]
    [:nav.container
     [:ul
      [:li [:strong "RP"]]]
     [:ul
      [nav-item :workouts "Workouts"]
      [nav-item :plans "Plans"]
      [nav-item :settings "Settings"]]]))

;; -----------------------------------------------------------------------------
;; Pages
;; -----------------------------------------------------------------------------

(defn- workouts-page
  "Main workout tracking page."
  []
  (let [events (db/get-all-events)
        plan (plan/get-plan)
        plan-name (plan/get-plan-name)
        progress (state/view-progress-in-plan events plan)
        mesocycle-data (get progress plan-name)]
    [:<>
     [:header
      [:h1 plan-name]
      [:p "Track your workout progression"]]

     (for [[microcycle-idx workouts-map] (sort-by first mesocycle-data)]
       ^{:key microcycle-idx}
       [microcycle-section plan-name microcycle-idx workouts-map])]))

(defn- plans-page
  "Plan management page - view, import, create plans."
  []
  (let [current-template (plan/get-template)
        current-name (:name current-template)]
    [:<>
     [:header
      [:h1 "Plans"]
      [:p "Manage your workout plans"]]

     [:section
      [:h2 "Current Plan"]
      [:p [:strong current-name]]]

     [:section
      [:h2 "Available Plans"]
      (for [template plan/available-templates
            :let [template-name (:name template)
                  is-current? (= template-name current-name)]]
        ^{:key template-name}
        [:article {:style {:margin-bottom "1rem"}}
         [:header [:strong template-name]]
         [:p (str (:n-microcycles template) " weeks • "
                  (count (:workouts template)) " days/week")]
         (if is-current?
           [:button.secondary {:disabled true} "Current"]
           [:button {:on-click #(do (plan/set-template! template)
                                    (reset! current-page :workouts))}
            "Use This Plan"])])]

     [:section
      [:h2 "Import Plan"]
      [:p "Import a plan from EDN file"]
      [:input {:type "file" :accept ".edn"}]]]))

(defn- settings-page
  "App settings page."
  []
  [:<>
   [:header
    [:h1 "Settings"]
    [:p "Configure the app"]]

   [:section
    [:h2 "Data"]
    [:button.secondary {:on-click #(js/console.log "Export data")}
     "Export All Data"]
    [:button.secondary.outline {:style {:margin-left "0.5rem"}
                                :on-click #(when (js/confirm "Clear all workout logs?")
                                             (js/console.log "Clear data"))}
     "Clear Logs"]]

   [:section
    [:h2 "About"]
    [:p "Romance Progression"]
    [:small "Local-first PWA for workout tracking"]]])

(defn app
  "Main app component - renders navigation and current page."
  []
  [:div
   [nav-menu]
   [:main.container
    (case @current-page
      :workouts [workouts-page]
      :plans    [plans-page]
      :settings [settings-page]
      [workouts-page])

    [:footer {:style {:margin-top "2rem" :text-align "center"}}
     [:small "Romance Progression • Local-first PWA"]]]])
