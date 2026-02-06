(ns rp.ui.feedback
  "Feedback popup components for soreness and session rating.
  
  Data-driven pattern:
  - Options defined as data vectors
  - Form fields defined as data maps  
  - Components interpret data to render"
  (:require [reagent.core :as r]
            [rp.ui.components :as c]))

;; -----------------------------------------------------------------------------
;; Option data (what the user can choose)
;; -----------------------------------------------------------------------------

(def ^:private soreness-options
  [{:value :never-sore        :label "Never got sore"}
   {:value :healed-early      :label "Healed a while ago"}
   {:value :healed-just-in-time :label "Healed just in time"}
   {:value :still-sore        :label "Still sore"}])

(def ^:private workload-options
  [{:value :easy          :label "Easy"}
   {:value :just-right    :label "Just right"}
   {:value :pushed-limits :label "Pushed my limits"}
   {:value :too-much      :label "Too much"}])

(def ^:private joint-pain-options
  [{:value :none   :label "No pain"}
   {:value :some   :label "Some discomfort"}
   {:value :severe :label "Significant pain"}])

(def ^:private pump-labels
  ["None" "Mild" "Moderate" "Great" "Best ever"])

;; -----------------------------------------------------------------------------
;; Form field renderers
;; -----------------------------------------------------------------------------

(defn- slider-field [{:keys [label value labels on-change]}]
  [:div {:style {:margin-bottom "1rem"}}
   [:label label " " (get labels @value)]
   [:input {:type "range" :min 0 :max (dec (count labels)) :value @value
            :on-change #(on-change (js/parseInt (-> % .-target .-value)))}]])

(defn- radio-field [{:keys [label name options value direction on-change]}]
  [:div {:style {:margin-bottom "1rem"}}
   [:label label]
   [c/radio-group {:name name :options options :direction (or direction :column)
                   :selected @value :on-change on-change}]])

;; -----------------------------------------------------------------------------
;; Popup components
;; -----------------------------------------------------------------------------

(defn soreness-popup
  "Single-question popup: how sore is the muscle group?"
  [{:keys [on-submit on-dismiss]}]
  (let [selected (r/atom nil)]
    (fn [{:keys [muscle-group]}]
      [c/modal-dialog
       {:title (str "How's your " (name muscle-group) "?")
        :subtitle "Since your last session..."
        :submit-disabled? (nil? @selected)
        :on-submit #(on-submit muscle-group @selected)
        :on-dismiss #(on-dismiss muscle-group)}
       [c/radio-group {:name "soreness" :options soreness-options
                       :selected @selected :on-change #(reset! selected %)}]])))

(defn session-rating-popup
  "Multi-field popup: rate pump, joint pain, and workload."
  [{:keys [on-submit on-dismiss]}]
  (let [pump (r/atom 2)
        joint-pain (r/atom :none)
        workload (r/atom :just-right)]
    (fn [{:keys [muscle-group]}]
      [c/modal-dialog
       {:title (str "Rate your " (name muscle-group) " session")
        :max-width "450px"
        :on-submit #(on-submit muscle-group {:pump @pump :joint-pain @joint-pain :sets-workload @workload})
        :on-dismiss #(on-dismiss muscle-group)}
       [slider-field {:label "Pump:" :value pump :labels pump-labels
                      :on-change #(reset! pump %)}]
       [radio-field {:label "Joint pain:" :name "joint-pain" :options joint-pain-options
                     :direction :row :value joint-pain :on-change #(reset! joint-pain %)}]
       [radio-field {:label "Number of sets felt:" :name "workload" :options workload-options
                     :value workload :on-change #(reset! workload %)}]])))
