(ns rp.app
  "Main app: UI rendering with vanilla DOM.
  
  No React, no Reagent — just ClojureScript and the DOM.
  
  Architecture:
  - State changes trigger full re-render (simple, fast enough for this app)
  - Event handlers call domain functions and trigger re-render
  - HTML built with helper functions, then set via innerHTML"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [rp.config :as config]
            [rp.events :as events]
            [rp.plan :as plan]
            [rp.progression :as prog]
            [rp.state :as state]))

;; -----------------------------------------------------------------------------
;; State
;; -----------------------------------------------------------------------------

(def ^:private app-state
  (atom {:page :workouts
         :dismissed-feedback #{}
         :inputs {}}))  ; {[exercise set-index] {:weight "" :reps ""}}

;; -----------------------------------------------------------------------------
;; DOM helpers
;; -----------------------------------------------------------------------------

(defn- $ [sel] (.querySelector js/document sel))
(defn- $$ [sel] (array-seq (.querySelectorAll js/document sel)))

(defn- el
  "Create element: (el :div.class {:onclick f} \"text\" child-el)"
  [tag attrs & children]
  (let [[tag-name & classes] (str/split (name tag) #"\.")
        elem (.createElement js/document tag-name)]
    (doseq [c classes] (.add (.-classList elem) c))
    (doseq [[k v] attrs]
      (case k
        :style (doseq [[sk sv] v] (aset (.-style elem) (name sk) sv))
        :class (.add (.-classList elem) v)
        :disabled (when v (set! (.-disabled elem) true))
        :checked (set! (.-checked elem) v)
        :value (set! (.-value elem) (str v))
        :placeholder (set! (.-placeholder elem) (str v))
        :type (set! (.-type elem) (str v))
        :min (set! (.-min elem) (str v))
        :max (set! (.-max elem) (str v))
        :open (when v (.setAttribute elem "open" ""))
        (aset elem (name k) v)))
    (doseq [child children]
      (when child
        (if (string? child)
          (.appendChild elem (.createTextNode js/document child))
          (.appendChild elem child))))
    elem))

;; -----------------------------------------------------------------------------
;; Rendering
;; -----------------------------------------------------------------------------

(declare render!)

(defn- input-key [exercise idx] [exercise idx])

(defn- get-input [exercise idx field]
  (get-in @app-state [:inputs (input-key exercise idx) field] ""))

(defn- set-input! [exercise idx field value]
  (swap! app-state assoc-in [:inputs (input-key exercise idx) field] value)
  nil)

(defn- render-set-row [meso micro workout exercise idx set-data muscle-groups]
  (let [{:keys [performed-weight performed-reps type]} set-data
        done? (or performed-weight (#{:set-skipped :set-rejected} type))
        loc {:mesocycle meso :microcycle micro :workout workout
             :exercise exercise :set-index idx}
        weight-val (get-input exercise idx :weight)
        reps-val (get-input exercise idx :reps)
        user-wt (when (seq weight-val) (js/parseFloat weight-val))
        prescription (prog/prescribe (events/get-all-events) loc user-wt muscle-groups)]
    (el :div {:style {:display "flex" :gap "0.5rem" :alignItems "center" :marginBottom "0.5rem"}}
        (el :input {:type "number"
                    :style {:width "5rem"}
                    :value (if done? (str performed-weight) weight-val)
                    :placeholder (str (:weight prescription) " kg")
                    :disabled done?
                    :oninput (fn [e] (set-input! exercise idx :weight (.-value (.-target e))) (render!))})
        (el :span {} "×")
        (el :input {:type "number"
                    :style {:width "4rem"}
                    :value (if done? (str performed-reps) reps-val)
                    :placeholder (str (:reps prescription))
                    :disabled done?
                    :oninput (fn [e] (set-input! exercise idx :reps (.-value (.-target e))) (render!))})
        (cond
          (= type :set-skipped)
          (el :span {:style {:opacity "0.5"}} "skipped")

          (= type :set-rejected)
          (el :span {:style {:opacity "0.5"}} "rejected")

          performed-weight
          (el :span {} "✓")

          :else
          (el :span {}
              (el :input {:type "checkbox"
                          :checked false
                          :onchange (fn [_]
                                      (when (and (seq weight-val) (seq reps-val))
                                        (events/log-set! (assoc loc
                                                                :weight (js/parseFloat weight-val)
                                                                :reps (js/parseInt reps-val)
                                                                :prescribed-weight (:weight prescription)
                                                                :prescribed-reps (:reps prescription)))
                                        (set-input! exercise idx :weight "")
                                        (set-input! exercise idx :reps "")
                                        (render!)))})
              (el :button.secondary.outline
                  {:type "button"
                   :style {:padding "0.25rem 0.5rem" :margin "0" :marginLeft "0.5rem"}
                   :onclick (fn [_]
                              (when (js/confirm "Skip this set?")
                                (events/skip-set! loc)
                                (render!)))}
                  "skip"))))))

(defn- render-exercise [meso micro workout-key exercise sets extra-count]
  (let [muscle-groups (some :muscle-groups sets)
        total (+ (count sets) extra-count)]
    (el :article {}
        (el :h4 {} exercise
            (when muscle-groups
              (el :small {:style {:fontWeight "normal" :marginLeft "0.5rem" :opacity "0.6"}}
                  (str/join ", " (map name muscle-groups)))))
        (apply el :div {}
               (for [idx (range total)]
                 (render-set-row meso micro workout-key exercise idx (get sets idx {}) muscle-groups)))
        (el :button.outline
            {:type "button"
             :style {:padding "0.25rem 0.75rem"}
             :onclick (fn [_]
                        (swap! app-state update-in [:extra-sets exercise] (fnil inc 0))
                        (render!))}
            "+ Add set"))))

(defn- render-workouts []
  (let [all-events (events/get-all-events)
        plan-name (plan/get-plan-name)
        progress (state/view-progress-in-plan all-events (plan/get-plan))
        mesocycle-data (get progress plan-name)]
    (apply el :div {}
           (for [[week workouts] (sort-by first mesocycle-data)]
             (el :section {}
                 (el :h2 {} (str "Week " (inc week)))
                 (apply el :div {}
                        (for [[day exercises] workouts]
                          (let [loc {:mesocycle plan-name :microcycle week :workout day}
                                swaps (state/get-swaps all-events loc)
                                swapped (state/apply-swaps exercises swaps)]
                            (el :section {}
                                (el :h3 {} (str/capitalize (name day)))
                                (apply el :div {}
                                       (for [[ex-name sets] swapped]
                                         (render-exercise plan-name week day ex-name sets
                                                          (get-in @app-state [:extra-sets ex-name] 0)))))))))))))

(defn- render-plans []
  (let [current-name (:name (plan/get-template))]
    (el :div {}
        (el :section {}
            (el :h2 {} "Current Plan")
            (el :p {} (el :strong {} current-name)))
        (el :section {}
            (el :h2 {} "Available Plans")
            (apply el :div {}
                   (for [t plan/available-templates]
                     (el :article {:style {:marginBottom "1rem"}}
                         (el :header {} (el :strong {} (:name t)))
                         (el :p {} (str (:n-microcycles t) " weeks • " (count (:workouts t)) " days/week"))
                         (if (= (:name t) current-name)
                           (el :button.secondary {:disabled true} "Current")
                           (el :button {:onclick (fn [_]
                                                   (plan/set-template! t)
                                                   (swap! app-state assoc :page :workouts)
                                                   (render!))}
                               "Use This Plan")))))))))

(defn- render-settings []
  (el :div {}
      (el :section {}
          (el :h2 {} "Data")
          (el :div {:style {:display "flex" :gap "0.5rem"}}
              (el :button.secondary
                  {:onclick (fn [_]
                              (let [data (events/db->edn)
                                    blob (js/Blob. #js [data] #js {:type "application/edn"})
                                    url (.createObjectURL js/URL blob)
                                    link (.createElement js/document "a")]
                                (set! (.-href link) url)
                                (set! (.-download link) (str "rp-workout-" (.toISOString (js/Date.)) ".edn"))
                                (.click link)
                                (.revokeObjectURL js/URL url)))}
                  "Export All Data")
              (el :button.secondary.outline
                  {:onclick (fn [_]
                              (when (js/confirm "Clear all workout logs?")
                                (events/clear-all!)
                                (render!)))}
                  "Clear Logs")))
      (el :section {}
          (el :h2 {} "About")
          (el :p {} "Romance Progression")
          (el :small {} "Local-first PWA for workout tracking"))))

(defn- render-nav []
  (let [page (:page @app-state)
        nav-items [[:workouts "Workouts"] [:plans "Plans"] [:settings "Settings"]]]
    (el :nav.container {:style {:position "sticky" :top "0" :zIndex "100"
                                :background "var(--pico-background-color)"}}
        (el :ul {}
            (el :li {}
                (el :strong {} "RP")
                (el :small {:style {:marginLeft "0.5rem" :opacity "0.6"}}
                    (str "v" config/VERSION))))
        (apply el :ul {}
               (for [[k label] nav-items]
                 (el :li {}
                     (el :a {:href "#"
                             :class (when (= page k) "contrast")
                             :onclick (fn [e]
                                        (.preventDefault e)
                                        (swap! app-state assoc :page k)
                                        (render!))}
                         label)))))))

(defn- render-page []
  (let [page (:page @app-state)]
    (el :main.container {}
        (case page
          :workouts (el :div {}
                        (el :header {}
                            (el :h1 {} (plan/get-plan-name))
                            (el :p {} "Track your workout progression"))
                        (render-workouts))
          :plans    (el :div {}
                        (el :header {}
                            (el :h1 {} "Plans")
                            (el :p {} "Manage your workout plans"))
                        (render-plans))
          :settings (el :div {}
                        (el :header {}
                            (el :h1 {} "Settings")
                            (el :p {} "Configure the app"))
                        (render-settings)))
        (el :footer {:style {:marginTop "2rem" :textAlign "center"}}
            (el :small {} "Romance Progression • Local-first PWA")))))

(defn render! []
  (let [app-el ($ "#app")]
    (set! (.-innerHTML app-el) "")
    (.appendChild app-el (render-nav))
    (.appendChild app-el (render-page))))

;; -----------------------------------------------------------------------------
;; Storage
;; -----------------------------------------------------------------------------

(def ^:private DB-KEY "rp-workout-db")

(defn- save-db! []
  (try
    (.setItem js/localStorage DB-KEY (events/db->edn))
    (catch :default e
      (js/console.error "Failed to save:" e))))

(defn- load-db! []
  (when-let [data (.getItem js/localStorage DB-KEY)]
    (events/load-from-edn! data)))

;; -----------------------------------------------------------------------------
;; Init
;; -----------------------------------------------------------------------------

(defn- register-service-worker []
  (when (.-serviceWorker js/navigator)
    (-> js/navigator .-serviceWorker (.register "sw.js"))))

(defn init! []
  (load-db!)
  (add-watch events/version :auto-save (fn [_ _ _ _] (save-db!)))
  (render!)
  (register-service-worker))
