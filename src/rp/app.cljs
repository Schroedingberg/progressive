(ns rp.app
  "Main app: UI rendering with vanilla DOM.
  
  No React, no Reagent — just ClojureScript and the DOM.
  
  Architecture:
  - State changes trigger full re-render (simple, fast enough for this app)
  - Event handlers call domain functions and trigger re-render
  - HTML built with helper functions, then set via innerHTML"
  (:require [clojure.string :as str]
            [rp.events :as events]
            [rp.plan :as plan]
            [rp.progression :as prog]))

;; Version injected at build time via :closure-defines
(goog-define VERSION "dev")

;; -----------------------------------------------------------------------------
;; State
;; -----------------------------------------------------------------------------

(def ^:private app-state
  (atom {:page :workouts
         :dismissed-feedback #{}
         :inputs {}       ; {[exercise set-index] {:weight "" :reps ""}}
         :feedback nil    ; {:type :soreness/:session :muscle-group :quads :loc {...}}
         :swapping nil    ; exercise name being swapped
         :swap-input ""
         ;; Popup form state (no more hidden closure atoms)
         :popup-form {:selected nil      ; soreness selection
                      :pump 2            ; session pump rating 0-4
                      :joint-pain :none  ; session joint pain
                      :workload :just-right}}))

;; Feedback options
(def ^:private soreness-options
  [[:never-sore "Never got sore"]
   [:healed-early "Healed a while ago"]
   [:healed-just-in-time "Healed just in time"]
   [:still-sore "Still sore"]])

(def ^:private workload-options
  [[:easy "Easy"] [:just-right "Just right"]
   [:pushed-limits "Pushed my limits"] [:too-much "Too much"]])

(def ^:private joint-pain-options
  [[:none "No pain"] [:some "Some discomfort"] [:severe "Significant pain"]])

(def ^:private pump-labels ["None" "Mild" "Moderate" "Great" "Best ever"])

;; -----------------------------------------------------------------------------
;; DOM helpers
;; -----------------------------------------------------------------------------

(defn- $ [sel] (.querySelector js/document sel))

(defn- el
  "Create element: (el :div.class {:onclick f} \"text\" child-el)"
  [tag attrs & children]
  (let [[tag-name & classes] (str/split (name tag) #"\.")
        elem (.createElement js/document tag-name)]
    (doseq [c classes] (.add (.-classList elem) c))
    (doseq [[k v] attrs]
      (let [attr-name (name k)]
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
          (if (str/starts-with? attr-name "data-")
            (.setAttribute elem attr-name v)
            (aset elem attr-name v)))))
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

;; --- Feedback modals ---

(defn- dismiss-key [loc type mg]
  [type (:mesocycle loc) (:microcycle loc) (:workout loc) mg])

(defn- render-radio-group [name options selected on-change]
  (apply el :div {:style {:display "flex" :flexDirection "column" :gap "0.25rem"}}
         (for [[value label] options]
           (el :label {:style {:display "flex" :alignItems "center" :gap "0.5rem"}}
               (el :input {:type "radio"
                           :name name
                           :checked (= selected value)
                           :onchange (fn [_] (on-change value))})
               label))))

(defn- render-soreness-popup [muscle-group loc]
  (let [selected (get-in @app-state [:popup-form :selected])]
    (el :dialog {:open true :style {:maxWidth "400px"}}
        (el :article {}
            (el :header {}
                (el :h3 {} (str "How's your " (name muscle-group) "?"))
                (el :p {} "Since your last session..."))
            (render-radio-group "soreness" soreness-options selected
                                (fn [v] (swap! app-state assoc-in [:popup-form :selected] v) (render!)))
            (el :footer {:style {:marginTop "1rem"}}
                (el :button {:disabled (nil? selected)
                             :onclick (fn [_]
                                        (events/log-soreness-reported!
                                         (assoc loc :muscle-group muscle-group :soreness selected))
                                        (swap! app-state assoc :feedback nil)
                                        (swap! app-state assoc-in [:popup-form :selected] nil)
                                        (render!))}
                    "Submit")
                (el :button.secondary {:style {:marginLeft "0.5rem"}
                                       :onclick (fn [_]
                                                  (swap! app-state update :dismissed-feedback
                                                         conj (dismiss-key loc :soreness muscle-group))
                                                  (swap! app-state assoc :feedback nil)
                                                  (render!))}
                    "Skip"))))))

(defn- render-session-popup [muscle-group loc]
  (let [{:keys [pump joint-pain workload]} (:popup-form @app-state)]
    (el :dialog {:open true :style {:maxWidth "450px"}}
        (el :article {}
            (el :header {}
                (el :h3 {} (str "Rate your " (name muscle-group) " session")))
            ;; Pump slider
            (el :div {:style {:marginBottom "1rem"}}
                (el :label {} (str "Pump: " (get pump-labels pump)))
                (el :input {:type "range" :min "0" :max "4" :value (str pump)
                            :oninput (fn [e]
                                       (swap! app-state assoc-in [:popup-form :pump]
                                              (js/parseInt (.-value (.-target e))))
                                       (render!))}))
            ;; Joint pain
            (el :div {:style {:marginBottom "1rem"}}
                (el :label {} "Joint pain:")
                (render-radio-group "joint-pain" joint-pain-options joint-pain
                                    (fn [v] (swap! app-state assoc-in [:popup-form :joint-pain] v) (render!))))
            ;; Workload
            (el :div {:style {:marginBottom "1rem"}}
                (el :label {} "Number of sets felt:")
                (render-radio-group "workload" workload-options workload
                                    (fn [v] (swap! app-state assoc-in [:popup-form :workload] v) (render!))))
            (el :footer {:style {:marginTop "1rem"}}
                (el :button {:onclick (fn [_]
                                        (events/log-session-rated!
                                         (assoc loc :muscle-group muscle-group
                                                :pump pump :joint-pain joint-pain :sets-workload workload))
                                        (swap! app-state assoc :feedback nil)
                                        (render!))}
                    "Submit")
                (el :button.secondary {:style {:marginLeft "0.5rem"}
                                       :onclick (fn [_]
                                                  (swap! app-state update :dismissed-feedback
                                                         conj (dismiss-key loc :session muscle-group))
                                                  (swap! app-state assoc :feedback nil)
                                                  (render!))}
                    "Skip"))))))

(defn- get-workout-muscle-groups [exercises-map]
  (->> exercises-map vals (mapcat identity) (mapcat :muscle-groups) (remove nil?) distinct))

(defn- find-pending-feedback [events progress loc dismissed]
  (when loc
    (let [workout-ex (get-in progress [(:mesocycle loc) (:microcycle loc) (:workout loc)])
          muscle-groups (get-workout-muscle-groups workout-ex)]
      ;; Check soreness first
      (if-let [mg (->> (events/pending-soreness-feedback events progress loc muscle-groups)
                       (remove #(contains? dismissed (dismiss-key loc :soreness %)))
                       first)]
        {:type :soreness :muscle-group mg :loc loc}
        ;; Then session rating
        (when-let [mg (->> (events/pending-session-rating events progress loc muscle-groups)
                           (remove #(contains? dismissed (dismiss-key loc :session %)))
                           first)]
          {:type :session :muscle-group mg :loc loc})))))

;; --- Inputs ---

(defn- input-key [exercise idx] [exercise idx])

(defn- get-input [exercise idx field]
  (get-in @app-state [:inputs (input-key exercise idx) field] ""))

(defn- set-input! [exercise idx field value]
  (swap! app-state assoc-in [:inputs (input-key exercise idx) field] value)
  nil)

(defn- update-reps-placeholder!
  "Update the reps placeholder based on entered weight (targeted DOM update)."
  [meso micro workout exercise idx muscle-groups]
  (let [weight-val (get-input exercise idx :weight)
        user-wt (when (seq weight-val) (js/parseFloat weight-val))
        loc {:mesocycle meso :microcycle micro :workout workout
             :exercise exercise :set-index idx}
        prescription (prog/prescribe (events/get-all-events) loc user-wt muscle-groups)
        reps-input-id (str meso "-" micro "-" (name workout) "-" exercise "-" idx "-reps")
        escaped-id (js/CSS.escape reps-input-id)
        selector (str "[data-input='" escaped-id "']")]
    (when-let [reps-el (.querySelector js/document selector)]
      (.setAttribute reps-el "placeholder" (str (:reps prescription))))))

(defn- render-set-row [meso micro workout exercise idx set-data muscle-groups]
  (let [{:keys [performed-weight performed-reps type]} set-data
        done? (or performed-weight (#{:set-skipped :set-rejected} type))
        loc {:mesocycle meso :microcycle micro :workout workout
             :exercise exercise :set-index idx}
        weight-val (get-input exercise idx :weight)
        reps-val (get-input exercise idx :reps)
        user-wt (when (seq weight-val) (js/parseFloat weight-val))
        prescription (prog/prescribe (events/get-all-events) loc user-wt muscle-groups)
        input-prefix (str meso "-" micro "-" (name workout) "-" exercise "-" idx)]
    (el :div {:style {:display "flex" :gap "0.5rem" :alignItems "center" :marginBottom "0.5rem"}}
        (el :input {:type "number"
                    :style {:width "5rem"}
                    :value (if done? (str performed-weight) weight-val)
                    :placeholder (str (:weight prescription) " kg")
                    :disabled done?
                    :data-input (str input-prefix "-weight")
                    :oninput (fn [e]
                               (set-input! exercise idx :weight (.-value (.-target e)))
                               ;; Only update reps placeholder, don't re-render
                               (update-reps-placeholder! meso micro workout exercise idx muscle-groups))})
        (el :span {} "×")
        (el :input {:type "number"
                    :style {:width "4rem"}
                    :value (if done? (str performed-reps) reps-val)
                    :placeholder (str (:reps prescription))
                    :disabled done?
                    :data-input (str input-prefix "-reps")
                    :oninput (fn [e]
                               ;; Just store value, no need to update anything
                               (set-input! exercise idx :reps (.-value (.-target e))))})
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

(defn- render-exercise [meso micro workout-key exercise sets extra-count original-exercise]
  (let [muscle-groups (some :muscle-groups sets)
        total (+ (count sets) extra-count)
        swapping? (= (:swapping @app-state) exercise)
        swap-input (:swap-input @app-state)]
    (el :article {}
        (el :h4 {:style {:display "flex" :alignItems "center" :gap "0.5rem"}}
            exercise
            (when original-exercise
              (el :small {:style {:opacity "0.5"}} (str "(was: " original-exercise ")")))
            (when muscle-groups
              (el :small {:style {:fontWeight "normal" :marginLeft "0.5rem" :opacity "0.6"}}
                  (str/join ", " (map name muscle-groups)))))
        ;; Swap UI
        (when swapping?
          (el :div {:style {:display "flex" :gap "0.5rem" :marginBottom "0.5rem" :alignItems "center"}}
              (el :input {:type "text"
                          :placeholder "Replacement exercise name"
                          :value swap-input
                          :style {:flex "1"}
                          :oninput (fn [e] (swap! app-state assoc :swap-input (.-value (.-target e))))})
              (el :button.secondary {:type "button"
                                     :disabled (empty? swap-input)
                                     :onclick (fn [_]
                                                (when (js/confirm (str "Swap " exercise " for " swap-input "?"))
                                                  (events/swap-exercise!
                                                   {:mesocycle meso :microcycle micro :workout workout-key
                                                    :original-exercise (or original-exercise exercise)
                                                    :replacement-exercise swap-input
                                                    :muscle-groups muscle-groups})
                                                  (swap! app-state assoc :swapping nil :swap-input "")
                                                  (render!)))}
                  "Confirm")
              (el :button.secondary.outline {:type "button"
                                             :onclick (fn [_]
                                                        (swap! app-state assoc :swapping nil :swap-input "")
                                                        (render!))}
                  "Cancel")))
        ;; Sets
        (apply el :div {}
               (for [idx (range total)]
                 (render-set-row meso micro workout-key exercise idx (get sets idx {}) muscle-groups)))
        ;; Buttons
        (el :div {:style {:display "flex" :gap "0.5rem"}}
            (el :button.outline
                {:type "button"
                 :style {:padding "0.25rem 0.75rem"}
                 :onclick (fn [_]
                            (swap! app-state update-in [:extra-sets exercise] (fnil inc 0))
                            (render!))}
                "+ Add set")
            (when-not swapping?
              (el :button.secondary.outline
                  {:type "button"
                   :style {:padding "0.25rem 0.75rem"}
                   :onclick (fn [_]
                              (swap! app-state assoc :swapping exercise :swap-input "")
                              (render!))}
                  "Swap"))))))

(defn- render-workouts []
  (let [all-events (events/get-all-events)
        plan-name (plan/get-plan-name)
        progress (events/view-progress-in-plan all-events (plan/get-plan))
        mesocycle-data (get progress plan-name)
        active (events/last-active-workout all-events)
        pending (find-pending-feedback all-events progress active (:dismissed-feedback @app-state))]
    (el :div {}
        ;; Feedback popup
        (when pending
          (case (:type pending)
            :soreness (render-soreness-popup (:muscle-group pending) (:loc pending))
            :session (render-session-popup (:muscle-group pending) (:loc pending))
            nil))
        ;; Workouts
        (apply el :div {}
               (for [[week workouts] (sort-by first mesocycle-data)]
                 (el :section {}
                     (el :h2 {} (str "Week " (inc week)))
                     (apply el :div {}
                            (for [[day exercises] workouts]
                              (let [loc {:mesocycle plan-name :microcycle week :workout day}
                                    swaps (events/get-swaps all-events loc)
                                    swapped (events/apply-swaps exercises swaps)]
                                (el :section {}
                                    (el :h3 {} (str/capitalize (name day)))
                                    (apply el :div {}
                                           (for [[ex-name sets] swapped]
                                             (render-exercise plan-name week day ex-name sets
                                                              (get-in @app-state [:extra-sets ex-name] 0)
                                                              (some :original-exercise sets))))))))))))))

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
          (el :div {:style {:display "flex" :gap "0.5rem" :flexWrap "wrap"}}
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
                  "Export Data")
              (el :button.secondary
                  {:onclick (fn [_]
                              (let [input (.createElement js/document "input")]
                                (set! (.-type input) "file")
                                (set! (.-accept input) ".edn")
                                (set! (.-onchange input)
                                      (fn [e]
                                        (when-let [file (aget (.-files (.-target e)) 0)]
                                          (-> (.text file)
                                              (.then (fn [text]
                                                       (events/load-from-edn! text)
                                                       (js/alert (str "Imported " (count (events/get-all-events)) " events"))
                                                       (render!)))))))
                                (.click input)))}
                  "Import Data")
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
                    (str "v" VERSION))))
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

(defn- save-focus []
  (when-let [el (.-activeElement js/document)]
    (when (= "INPUT" (.-tagName el))
      {:data-input (.. el -dataset -input)
       :selection-start (.-selectionStart el)
       :selection-end (.-selectionEnd el)})))

(defn- restore-focus [{:keys [data-input selection-start selection-end]}]
  (when data-input
    (js/requestAnimationFrame
     (fn []
       (let [el (.querySelector js/document (str "[data-input='" (js/CSS.escape data-input) "']"))]
         (when (and el (not (.-disabled el)))
           (.focus el)
           (when (and selection-start selection-end (not= "number" (.-type el)))
             (try
               (set! (.-selectionStart el) selection-start)
               (set! (.-selectionEnd el) selection-end)
               (catch :default _)))))))))

(defn render! []
  (let [focus-info (save-focus)
        app-el ($ "#app")]
    (set! (.-innerHTML app-el) "")
    (.appendChild app-el (render-nav))
    (.appendChild app-el (render-page))
    (restore-focus focus-info)))

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
