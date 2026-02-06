(ns rp.ui.components
  "Reusable generic UI components.")

(defn radio-group
  "Render radio buttons. Options: [{:value :key :label \"text\"}...]"
  [{:keys [name options selected on-change direction]
    :or {direction :column}}]
  [:div {:style {:display "flex"
                 :flex-direction (if (= direction :row) "row" "column")
                 :gap (if (= direction :row) "1rem" "0.25rem")}}
   (doall
    (for [{:keys [value label]} options]
      ^{:key value}
      [:label {:style {:display "flex" :align-items "center" :gap "0.5rem"}}
       [:input {:type "radio" :name name :checked (= selected value)
                :on-change #(on-change value)}]
       label]))])

(defn modal-dialog
  "Wrapper for modal dialogs with header and footer."
  [{:keys [title subtitle max-width on-submit on-dismiss submit-label submit-disabled?]} & children]
  [:dialog {:open true :style {:max-width (or max-width "400px")}}
   [:article
    [:header
     [:h3 title]
     (when subtitle [:p subtitle])]
    (into [:<>] children)
    [:footer {:style {:margin-top "1rem"}}
     [:button {:disabled submit-disabled? :on-click on-submit} (or submit-label "Submit")]
     [:button.secondary {:on-click on-dismiss :style {:margin-left "0.5rem"}} "Skip"]]]])
