(ns eponai.web.ui.project.add-transaction
  (:require
    [cljsjs.moment]
    [cljsjs.pikaday]
    [cljsjs.react-select]
    [datascript.core :as d]
    [eponai.client.ui :refer [map-all] :refer-macros [style opts]]
    [eponai.client.parser.message :as message]
    [eponai.common.format.date :as date]
    [eponai.web.ui.daterangepicker :refer [->DateRangePicker]]
    [eponai.web.ui.select :as sel]
    [eponai.web.ui.utils :as utils]
    [garden.core :refer [css]]
    [om.dom :as dom]
    [goog.string :as gstring]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [warn debug]]))

(defn- delete-tag-fn [component tag]
  (om/update-state! component update-in [:input-transaction :transaction/tags] disj tag))

(defn- add-tag [component tag]
  (om/update-state! component
                    #(-> %
                         (assoc :input-tag "")
                         (update-in [:input-transaction :transaction/tags] conj tag))))

(defn transaction-fee-element [fee on-delete]
  (let [fee-type (:transaction.fee/type fee)
        fee-value (gstring/format "%.2f" (:transaction.fee/value fee))
        title (if (= fee-type :transaction.fee.type/absolute) "Fixed value" "Relative value")
        value (if (= fee-type :transaction.fee.type/absolute) (str "$ " fee-value) (str fee-value " %"))]
    [:div.transaction-fee
     ;[:div.content]
     [:strong value]
     [:span title]
     [:a.float-right
      {:on-click on-delete}
      "x"]]))

(defui OptionSelector
  Object
  (render [this]
    (let [{:keys [options]
           option-name :name} (om/props this)]
      (html
        [:div.option-selector
         (map (fn [opt i]
                (let [id (str option-name "-" i)]
                  [:div.option
                   [:input {:id id :type "radio" :name option-name}]
                   [:label {:for id}
                    [:span (:label opt)]]
                   ]))
              options
              (range))]))))

(def ->OptionSelector (om/factory OptionSelector))

(defui AddTransactionFee
  Object
  (save [this]
    (let [{:keys [on-save]} (om/get-computed this)
          {:keys [transaction-fee]} (om/get-state this)]
      (when on-save
        (on-save (-> transaction-fee
                     (update :transaction.fee/type #(:value %)))))))
  (initLocalState [_]
    {:transaction-fee {:transaction.fee/type {:label "$" :value :transaction.fee.type/absolute}}})
  (componentDidMount [this]
    (let [value-input (js/ReactDOM.findDOMNode (om/react-ref this "fee-value-input"))]
      (.focus value-input)))
  (render [this]
    (let [{:keys [all-currencies default]} (om/get-computed this)
          {:keys [transaction-fee]} (om/get-state this)]
      (debug "Will add transaction fee: " transaction-fee)
      (html
        [:div.add-new-transaction-fee
         [:div
          (sel/->SegmentedOption (om/computed {:options [{:label "$" :value :transaction.fee.type/absolute}
                                                         {:label "%" :value :transaction.fee.type/relative}]
                                               :name    "fee-selector"
                                               :value   (:transaction.fee/type transaction-fee)}
                                              {:on-select #(om/update-state! this assoc-in [:transaction-fee :transaction.fee/type] %)}))]
         [:div
          [:input {:type        "number"
                   :placeholder "Value"
                   :ref         "fee-value-input"
                   :on-change   #(om/update-state! this assoc-in [:transaction-fee :transaction.fee/value] (.. % -target -value))}]]
         (when (= (get-in transaction-fee [:transaction.fee/type :value]) :transaction.fee.type/absolute)
           [:div
            (sel/->Select {:options (map (fn [{:keys [currency/code db/id]}]
                                           {:label code
                                            :value id})
                                         all-currencies)
                           :value   (or (:transaction.fee/currency transaction-fee) default)})])
         [:a.button.small
          {:on-click
           #(.save this)}
          "Add"]]))))

(def ->AddTransactionFee (om/factory AddTransactionFee))

(defui AddTransaction
  static om/IQuery
  (query [_]
    [:query/message-fn
     {:query/all-currencies [:currency/code]}
     {:query/all-tags [:tag/name]}
     {:query/all-categories [:category/name]}])
  Object
  (add-transaction [this]
    (let [st (om/get-state this)
          update-category (fn [tx]
                               (let [{:keys [transaction/category]} tx]
                                 (if (nil? (:label category))
                                   (dissoc tx :transaction/category)
                                   (update tx :transaction/category (fn [{:keys [label _]}]
                                                                      {:category/name label})))))
          message-id (message/om-transact! this
                                           `[(transaction/create
                                               ~(-> (:input-transaction st)
                                                    (assoc :transaction/uuid (d/squuid))
                                                    (update :transaction/currency :value)
                                                    update-category
                                                    (update :transaction/tags (fn [ts]
                                                                                (map (fn [{:keys [label _]}]
                                                                                       {:tag/name label}) ts)))
                                                    ;(update :transaction/category (fn [{:keys [label _]}]
                                                    ;                                {:category/name label}))
                                                    (assoc :transaction/created-at (.getTime (js/Date.)))))
                                             :routing/project])]
      (om/update-state! this assoc :pending-transaction message-id)))
  (initLocalState [this]
    (let [{:keys [query/all-currencies query/all-categories]} (om/props this)
          {:keys [project-id]} (om/get-computed this)
          usd-entity (some #(when (= (:currency/code %) "USD") %) all-currencies)
          category-entity (first all-categories)]
      {:input-transaction {:transaction/date     (date/date-map (date/today))
                           :transaction/tags     #{}
                           :transaction/currency {:label (:currency/code usd-entity)
                                                  :value (:db/id usd-entity)}
                           ;:transaction/category {:label (:category/name category-entity)
                           ;                       :value (:db/id category-entity)}
                           :transaction/project  project-id
                           :transaction/type     :transaction.type/expense
                           :transaction/fee []}
       :type              :expense
       :computed/date-range-picker-on-apply #(om/update-state! this assoc-in [:input-transaction :transaction/date] %)}))
  (componentDidUpdate [this _ _]
    (when-let [history-id (:pending-transaction (om/get-state this))]
      (let [{:keys [query/message-fn]} (om/props this)
            {:keys [on-close]} (om/get-computed this)
            message (message-fn history-id 'transaction/create)]
        (comment
          "Here's something we could do with the message if we want"
          " it to be syncronous."
          (when message
            (when on-close
              (on-close)))))))

  (render [this]
    (let [{:keys [type input-transaction computed/date-range-picker-on-apply add-fee?]} (om/get-state this)
          {:keys [transaction/date transaction/currency transaction/category transaction/fee]} input-transaction
          {:keys [query/all-currencies
                  query/all-tags
                  query/all-categories]} (om/props this)]
      (debug "Input transaction: " input-transaction)
      (html
        [:div#add-transaction
         [:h4.header "New Transaction"]
         [:div.top-bar-container.subnav
          [:div.top-bar
           [:div.top-bar-left.menu
            [:a
             {:class    (when (= type :expense) "active")
              :on-click #(om/update-state! this (fn [st]
                                                  (-> st
                                                      (assoc :type :expense)
                                                      (assoc-in [:input-transaction :transaction/type] :transaction.type/expense))))}
             "Expense"]
            [:a
             {:class    (when (= type :income) "active")
              :on-click #(om/update-state! this (fn [st]
                                                  (-> st
                                                      (assoc :type :income)
                                                      (assoc-in [:input-transaction :transaction/type] :transaction.type/income))))}
             "Income"]
            [:a
             {:class    (when (= type :accomodation) "active")
              :on-click #(om/update-state! this assoc :type :accomodation)}
             "Accomodation"]
            [:a
             {:class    (when (= type :transport) "active")
              :on-click #(om/update-state! this assoc :type :transport)}
             "Transport"]
            [:a
             {:class    (when (= type :atm) "active")
              :on-click #(om/update-state! this assoc :type :atm)}
             "ATM"]]]]
         [:div.content


          [:div.content-section
           [:div.row
            [:div.columns.small-3.text-right
             [:label "Date:"]]

            [:div.columns.small-4.end
             (->DateRangePicker (om/computed {:single-calendar? true
                                              :start-date       (date/date-time date)}
                                             {:on-apply date-range-picker-on-apply
                                              :format   "MMM dd"}))]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Amount:"]]
            [:div.columns.small-4
             [:input
              {:type        "number"
               :min         "0"
               :placeholder "0.00"
               :on-change   #(om/update-state! this assoc-in [:input-transaction :transaction/amount] (.. % -target -value))}]]
            [:div.columns.small-2.text-right
             [:label "Currency:"]]
            [:div.columns.small-3
             (sel/->Select (om/computed {:options (map (fn [{:keys [currency/code db/id]}]
                                                                {:label code
                                                                 :value id})
                                                              all-currencies)
                                         :value currency}
                                        {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/currency] %)}))]]
           [:div.row
            [:div.columns.small-3.text-right
             [:label "Category:"]]
            [:div.columns.small-9
             (sel/->Select (om/computed {:options (map (fn [c]
                                                         {:label (:category/name c)
                                                          :value (:db/id c)})
                                                       all-categories)
                                         :value   category
                                         :clearable true}
                                        {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/category] %)}))]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Tags:"]]
            [:div.columns.small-9
             (sel/->SelectTags (om/computed {:options (map (fn [{:keys [tag/name db/id]}]
                                                             {:label name
                                                              :value id})
                                                           all-tags)
                                             :value   (:transaction/tags input-transaction)}
                                            {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/tags] %)}))]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Note:"]]
            [:div.columns.small-9
             [:textarea
              {:type      "text"
               :value     (:transaction/title input-transaction "")
               :on-change #(om/update-state! this assoc-in [:input-transaction :transaction/title] (.. % -target -value))}]]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Fees:"]]
            [:div.columns.small-9.transaction-fee
             [:div
              (when-not (empty? fee)
                (map (fn [f i]
                       [:div.transaction-fee-container
                        {:key (str "fee " i)}
                        (transaction-fee-element f
                                                 #(om/update-state! this update-in [:input-transaction :transaction/fee] (fn [fees]
                                                                                                                           (vec (concat
                                                                                                                                  (subvec fees 0 i)
                                                                                                                                  (subvec fees (inc i) (count fees)))))))])
                     fee
                     (range)))]
             [:div
              [:a
               {:on-click #(om/update-state! this assoc :add-fee? true)}
               "+ Add new fee"]]
             (when add-fee?
               (utils/popup {:on-close #(om/update-state! this assoc :add-fee? false)}
                            (->AddTransactionFee (om/computed {}
                                                              {:all-currencies all-currencies
                                                               :default        currency
                                                               :on-save        #(om/update-state! this (fn [st]
                                                                                                         (-> st
                                                                                                             (update-in [:input-transaction :transaction/fee] conj %)
                                                                                                             (assoc :add-fee? false))))}))))
             ]]]



          [:div.content-section.clearfix
           [:a.button.hollow.float-right
            {:on-click #(do (.add-transaction this)
                            (let [on-close (:on-close (om/get-computed this))]
                              (on-close)))}
            "Save"]]]]))))

(def ->AddTransaction (om/factory AddTransaction))

(defui QuickAddTransaction
  static om/IQuery
  (query [_]
    [:query/message-fn
     {:query/all-categories [:category/name]}
     {:query/all-currencies [:currency/code]}
     {:query/all-tags [:tag/name]}])
  Object
  (new-transaction [this]
    (let [{:keys [query/all-currencies]} (om/props this)
          {:keys [project]} (om/get-computed this)
          usd-entity (some #(when (= (:currency/code %) "USD") %) all-currencies)]
      (debug "Add transaction to project: " project)
      {:transaction/date     (date/date-map (date/today))
       :transaction/tags     #{}
       :transaction/currency {:label (:currency/code usd-entity)
                              :value (:db/id usd-entity)}
       :transaction/project  (:db/id project)
       :transaction/type     :transaction.type/expense}))
  (initLocalState [this]
    {:is-open?          false
     :on-close-fn       #(.close this %)
     :on-keydown-fn     #(do
                          (when (= 13 (or (.-which %) (.-keyCode %)))
                            (.save this)))
     :input-transaction (.new-transaction this)})

  (open [this]
    (let [{:keys [is-open? on-close-fn on-keydown-fn]} (om/get-state this)]
      (when-not is-open?
        (om/update-state! this assoc :is-open? true)
        (.. js/document (addEventListener "click" on-close-fn)))))

  (mouse-event-outside [_ event]
    (let [includes-class-fn (fn [class-name class-names-str]
                              (let [class-array (clojure.string/split class-names-str #" ")]
                                (some #(when (= % class-name) %) class-array)))]
      (debug "Includes quick-add-input-section: " (some #(includes-class-fn "quick-add-input-section" (.-className %))
                                                        (.-path event)))
      (not (some #(includes-class-fn "quick-add-input-section" (.-className %))
                 (.-path event)))))

  (close [this event]
    (let [{:keys [on-close-fn is-open?]} (om/get-state this)
          should-close? (.mouse-event-outside this event)]
      (when (and is-open? should-close?)
        (om/update-state! this assoc :is-open? false)
        (.. js/document (removeEventListener "click" on-close-fn)))))

  (save [this]
    (let [st (om/get-state this)
          update-category (fn [tx]
                            (let [{:keys [transaction/category]} tx]
                              (if (nil? (:label category))
                                (dissoc tx :transaction/category)
                                (update tx :transaction/category (fn [{:keys [label _]}]
                                                                   {:category/name label})))))
          message-id (message/om-transact! this
                                           `[(transaction/create
                                               ~(-> (:input-transaction st)
                                                    (assoc :transaction/uuid (d/squuid))
                                                    (update :transaction/currency :value)
                                                    update-category
                                                    (update :transaction/tags (fn [ts]
                                                                                (map (fn [{:keys [label _]}]
                                                                                       {:tag/name label}) ts)))
                                                    ;(update :transaction/category (fn [{:keys [label _]}]
                                                    ;                                {:category/name label}))
                                                    (assoc :transaction/created-at (.getTime (js/Date.)))))
                                             :routing/project])
          new-transaction (.new-transaction this)
          ]
      (debug "Save new transaction: " (:input-transaction st) " input " (:input-amount st))
      (debug "Set new transaction: " new-transaction)
      (om/update-state! this assoc :is-open? false
                        :pending-transaction message-id
                        :input-transaction new-transaction)
      ))

  (componentDidUpdate [this _ _]
    (when-let [history-id (:pending-transaction (om/get-state this))]
      (let [{:keys [query/message-fn]} (om/props this)
            {:keys [on-close]} (om/get-computed this)
            message (message-fn history-id 'transaction/create)]
        (comment
          "Here's something we could do with the message if we want"
          " it to be syncronous."
          (when message
            (when on-close
              (on-close)))))))

  (render [this]
    (let [{:keys [query/all-categories query/all-currencies query/all-tags]} (om/props this)
          {:keys [is-open? input-amount input-transaction on-keydown-fn]} (om/get-state this)]
      (debug "input-amount: '" input-amount "'")
      (debug "Input Transaction: " input-transaction)
      (html
        [:div.quick-add-container
         {:on-key-down on-keydown-fn}
         [:div.row.column.quick-add
          [:ul.menu.quick-add-input-section
           {:class    (when is-open? "is-open")
            :on-click #(.open this)}
           [:li.attribute.note
            [:input {:value       (if is-open? (or (:transaction/amount input-transaction) "") "")
                     :type        "number"
                     :placeholder (if is-open? "0.00" "Quick add expense for today...")
                     :tabIndex    0
                     :on-key-down #(do
                                    (debug "keycode: " (.-keyCode %) " which: " (.-which %))
                                    (when (= 13 (or (.-which %) (.-keyCode %)))
                                      (debug "Blurring yes: ")
                                      (.blur (.-target %))
                                      ))
                     ;:on-change     #(om/update-state! this assoc :input-amount (.. % -target -value))
                     :on-change   #(om/update-state! this assoc-in [:input-transaction :transaction/amount] (.. % -target -value))
                     }]]
           [:li.attribute.currency
            (sel/->Select (om/computed {:value       (:transaction/currency input-transaction)
                                        :options     (map (fn [c]
                                                            {:label (:currency/code c)
                                                             :value (:db/id c)})
                                                          all-currencies)
                                        :placeholder "USD"
                                        :tab-index 0}
                                       {:on-select #(do
                                                     (debug "Got new value: " %)
                                                     (om/update-state! this assoc-in [:input-transaction :transaction/currency] %))}))]
           [:li.attribute.category
            (sel/->Select (om/computed {:value       (:transaction/category input-transaction)
                                        :options     (map (fn [c]
                                                            {:label (:category/name c)
                                                             :value (:db/id c)})
                                                          all-categories)
                                        :placeholder "Category..."
                                        :tab-index 0}
                                       {:on-select #(do
                                                     (debug "category event: " %)
                                                     (om/update-state! this assoc-in [:input-transaction :transaction/category] %))}))]
           [:li.attribute.tags
            (sel/->SelectTags (om/computed {:value             (:transaction/tags input-transaction)
                                            :options           (map (fn [t]
                                                                      {:label (:tag/name t)
                                                                       :value (:db/id t)})
                                                                    all-tags)
                                            :on-input-key-down #(do
                                                                 (debug "Selec tags input key event: " %)
                                                                 (.startPropagation %))
                                            :tab-index 0}
                                           {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/tags] %)}))]]

          [:div.actions
           {:class (when is-open? "show")}
           [:a.save-button
            {:on-click #(.save this)
             :tabIndex 0}
            [:i.fa.fa-check.fa-fw]]
           [:a.cancel-button
            {:on-click #(om/update-state! this assoc :input-transaction (.new-transaction this))
             :tabIndex 0}
            [:i.fa.fa-times.fa-fw]]]]]))))

(def ->QuickAddTransaction (om/factory QuickAddTransaction))