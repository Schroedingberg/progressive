(ns rp.ui.feedback
  "Feedback popup components for soreness and session rating."
  (:require [reagent.core :as r]
            [rp.ui.components :as c]))

(def ^:private soreness-options
  [{:value :never-sore :label "Never got sore"}
   {:value :healed-early :label "Healed a while ago"}
   {:value :healed-just-in-time :label "Healed just in time"}
   {:value :still-sore :label "Still sore"}])

(def ^:private workload-options
  [{:value :easy :label "Easy"} {:value :just-right :label "Just right"}
   {:value :pushed-limits :label "Pushed my limits"} {:value :too-much :label "Too much"}])

(def ^:private joint-pain-options
  [{:value :none :label "No pain"} {:value :some :label "Some discomfort"}
   {:value :severe :label "Significant pain"}])

(defn soreness-popup [{:keys [on-submit on-dismiss]}]
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

(defn session-rating-popup [{:keys [on-submit on-dismiss]}]
  (let [pump (r/atom 2)
        joint-pain (r/atom :none)
        workload (r/atom :just-right)]
    (fn [{:keys [muscle-group]}]
      [c/modal-dialog
       {:title (str "Rate your " (name muscle-group) " session")
        :max-width "450px"
        :on-submit #(on-submit muscle-group {:pump @pump :joint-pain @joint-pain :sets-workload @workload})
        :on-dismiss #(on-dismiss muscle-group)}
       [:div {:style {:margin-bottom "1rem"}}
        [:label "Pump: " (["None" "Mild" "Moderate" "Great" "Best ever"] @pump)]
        [:input {:type "range" :min 0 :max 4 :value @pump
                 :on-change #(reset! pump (js/parseInt (-> % .-target .-value)))}]]
       [:div {:style {:margin-bottom "1rem"}}
        [:label "Joint pain:"]
        [c/radio-group {:name "joint-pain" :options joint-pain-options :direction :row
                        :selected @joint-pain :on-change #(reset! joint-pain %)}]]
       [:div {:style {:margin-bottom "1rem"}}
        [:label "Number of sets felt:"]
        [c/radio-group {:name "workload" :options workload-options
                        :selected @workload :on-change #(reset! workload %)}]]])))
