(ns rp.ui
  "Main app shell: navigation and page routing.
  
  Components are split into modules:
    - rp.ui.components - Generic form components
    - rp.ui.feedback   - Soreness/session rating popups
    - rp.ui.workout    - Set rows, exercise cards"
  (:require [reagent.core :as r]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rp.db :as db]
            [rp.plan :as plan]
            [rp.state :as state]
            [rp.storage :as storage]
            [rp.ui.feedback :as feedback]
            [rp.ui.workout :as workout]))

(defonce current-page (r/atom :workouts))

;; -----------------------------------------------------------------------------
;; Navigation
;; -----------------------------------------------------------------------------

(defn- nav-menu []
  [:nav.container
   [:ul [:li [:strong "RP"]]]
   [:ul
    (doall
     (for [[page label] [[:workouts "Workouts"] [:plans "Plans"] [:settings "Settings"]]]
       ^{:key page}
       [:li [:a {:href "#" :class (when (= @current-page page) "contrast")
                 :on-click #(do (.preventDefault %) (reset! current-page page))}
             label]]))]])

;; -----------------------------------------------------------------------------
;; Pages
;; -----------------------------------------------------------------------------

(defonce ^:private dismissed-feedback (r/atom #{}))

(defn- workout-muscle-groups [exercises-map]
  (->> exercises-map vals (mapcat identity) (mapcat :muscle-groups) (remove nil?) distinct))

(defn- workouts-page []
  (let [events (db/get-all-events)
        plan (plan/get-plan)
        plan-name (plan/get-plan-name)
        progress (state/view-progress-in-plan events plan)
        mesocycle-data (get progress plan-name)
        
        active (state/last-active-workout events)
        workout-ex (when active (get-in progress [(:mesocycle active) (:microcycle active) (:workout active)]))
        muscle-groups (when workout-ex (workout-muscle-groups workout-ex))
        dismissed @dismissed-feedback
        
        dismiss-key (fn [type mg] [type (:mesocycle active) (:microcycle active) (:workout active) mg])
        
        pending-soreness (when active
                           (->> (state/pending-soreness-feedback events progress active muscle-groups)
                                (remove #(contains? dismissed (dismiss-key :soreness %)))
                                first))
        pending-session (when (and active (not pending-soreness))
                          (->> (state/pending-session-rating events progress active muscle-groups)
                               (remove #(contains? dismissed (dismiss-key :session %)))
                               first))]
    [:<>
     (when pending-soreness
       [feedback/soreness-popup
        {:muscle-group pending-soreness
         :on-submit (fn [mg s] (db/log-soreness-reported! (assoc active :muscle-group mg :soreness s)))
         :on-dismiss (fn [mg] (swap! dismissed-feedback conj (dismiss-key :soreness mg)))}])
     
     (when pending-session
       [feedback/session-rating-popup
        {:muscle-group pending-session
         :on-submit (fn [mg data] (db/log-session-rated! (merge active {:muscle-group mg} data)))
         :on-dismiss (fn [mg] (swap! dismissed-feedback conj (dismiss-key :session mg)))}])
     
     [:header [:h1 plan-name] [:p "Track your workout progression"]]
     
     (doall
      (for [[week workouts] (sort-by first mesocycle-data)]
        ^{:key week}
        [:section
         [:h2 (str "Week " (inc week))]
         (doall
          (for [[day exercises] workouts]
            ^{:key day}
            [:section
             [:h3 (str/capitalize (name day))]
             (doall
              (for [[ex-name sets] exercises]
                ^{:key ex-name}
                [workout/exercise-card plan-name week day ex-name sets]))]))]))]))

(defn- plans-page []
  (let [current-template (plan/get-template)
        current-name (:name current-template)]
    [:<>
     [:header [:h1 "Plans"] [:p "Manage your workout plans"]]
     [:section [:h2 "Current Plan"] [:p [:strong current-name]]]
     [:section
      [:h2 "Available Plans"]
      (doall
       (for [t plan/available-templates]
         ^{:key (:name t)}
         [:article {:style {:margin-bottom "1rem"}}
          [:header [:strong (:name t)]]
          [:p (str (:n-microcycles t) " weeks • " (count (:workouts t)) " days/week")]
          (if (= (:name t) current-name)
            [:button.secondary {:disabled true} "Current"]
            [:button {:on-click #(do (plan/set-template! t) (reset! current-page :workouts))} "Use This Plan"])]))]
     [:section
      [:h2 "Import Plan"]
      [:input {:type "file" :accept ".edn"
               :on-change (fn [e]
                            (when-let [f (-> e .-target .-files (aget 0))]
                              (-> (.text f)
                                  (.then (fn [text]
                                           (try
                                             (let [t (reader/read-string text)]
                                               (if-let [err (plan/validate-template t)]
                                                 (js/alert (str "Invalid: " err))
                                                 (do (plan/set-template! t)
                                                     (js/alert (str "Imported: " (:name t)))
                                                     (reset! current-page :workouts))))
                                             (catch :default ex
                                               (js/alert (str "Parse error: " (.-message ex))))))))))}]]]))

(defn- settings-page []
  [:<>
   [:header [:h1 "Settings"] [:p "Configure the app"]]
   [:section
    [:h2 "Data"]
    [:button.secondary {:on-click #(js/console.log "Export data")} "Export All Data"]
    [:button.secondary.outline {:style {:margin-left "0.5rem"}
                                :on-click #(when (js/confirm "Clear all workout logs?") (storage/clear-db!))}
     "Clear Logs"]]
   [:section [:h2 "About"] [:p "Romance Progression"] [:small "Local-first PWA for workout tracking"]]])

(defn app []
  [:div
   [nav-menu]
   [:main.container
    (case @current-page :workouts [workouts-page] :plans [plans-page] :settings [settings-page] [workouts-page])
    [:footer {:style {:margin-top "2rem" :text-align "center"}}
     [:small "Romance Progression • Local-first PWA"]]]])
