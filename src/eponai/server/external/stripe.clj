(ns eponai.server.external.stripe
  (:require [eponai.common.database.transact :as t]
            [eponai.common.database.pull :as p]
            [taoensso.timbre :refer [debug error info]]
            [datomic.api :as d]
            [clojure.data.json :as json]
            [eponai.server.http :as h]
            [eponai.common.database.transact :as transact]
            [eponai.common.format :as f]
            [eponai.server.email :as email])
  (:import (com.stripe.model Customer)
           (com.stripe Stripe)
           (clojure.lang ExceptionInfo)
           (com.stripe.exception CardException)))

(defn stripe-subscription [customer]
  (-> customer
      (.getSubscriptions)
      (.getData)
      first))

(defn has-subscription?
  "Check if Stripe customer has any subscription registerd in Stripe."
  [customer]
  (some? (stripe-subscription customer)))

(defn obj->subscription-map [stripe-obj]
  {:stripe.subscription/id      (.getId stripe-obj)
   :stripe.subscription/status  (keyword (.getStatus stripe-obj))
   :stripe.subscription/ends-at (* 1000 (.getCurrentPeriodEnd stripe-obj))})

(defn json->subscription-map [{:keys [id period_end status]}]
  {:stripe.subscription/id      id
   :stripe.subscription/status  (keyword status)
   :stripe.subscription/ends-at (* 1000 period_end)})

(declare stripe-action)

(defn stripe [api-key k params]
  (info "Stripe action: " {:action k :params params})
  (try
    (set! (. Stripe apiKey) api-key)
    (stripe-action k params)
    (catch CardException e
      (throw (ex-info (str "Stripe error: " {:action k :class (class e) :code (.getCode e)})
                      {:cause     ::h/unprocessable-entity
                       :message   (.getMessage e)
                       :code      (keyword (.getCode e))
                       :exception e
                       :data      params})))
    (catch Exception e
      (throw (ex-info (str "Stripe error" {:action k :class (class e) :message (.getMessage e)})
                      {:cause     ::h/internal-error
                       :message   (.getMessage e)
                       :exception e
                       :data      params})))))


;;; ########### Stripe actions ###############

(defmulti stripe-action (fn [k _] k))

(defmethod stripe-action :customer/create
  [_ {:keys [params]}]
  {:post [(map? %)]}
  (let [customer (Customer/create params)
        subscription (stripe-subscription customer)]
    (debug "Created customer: " customer)
    {:stripe/customer     (.getId customer)
     :stripe/subscription (obj->subscription-map subscription)}))


(defmethod stripe-action :subscription/create
  [k {:keys [customer-id params]}]
  {:post [(map? %)]}
  (let [customer (Customer/retrieve customer-id)
        created (.createSubscription customer params)]
    (debug "Created subscription: " created)
    (obj->subscription-map created)))

(defmethod stripe-action :subscription/update
  [_ {:keys [subscription-id customer-id params]}]
  {:post [(map? %)]}
  (let [customer (Customer/retrieve customer-id)
        subscription (.retrieve (.getSubscriptions customer) subscription-id)
        updated (.update subscription params)]
    (debug "Updated subscription: " updated)
    (obj->subscription-map updated)))

(defmethod stripe-action :subscription/cancel
  [_ {:keys [customer-id subscription-id]}]
  {:post [(map? %)]}
  (let [customer (Customer/retrieve customer-id)
        subscription (.retrieve (.getSubscriptions customer) subscription-id)
        params {"at_period_end" false}
        canceled (.cancel subscription params)]
    (debug "Canceled subscription: " canceled)
    (obj->subscription-map canceled)))

;; ##################### Webhooooks ####################

; Multi method for Stripe event types passed in via webhooks.
; Reference Events: https://stripe.com/docs/api#events
(defmulti webhook (fn [_ event & _]
                    (info "Stripe event received: " {:type (:type event) :id (:id event)})
                    ; Dispatches on the event type.
                    ; Reference Event Type: https://stripe.com/docs/api#event_types
                    (:type event)))

(defmethod webhook :default
  [_ event & _]
  (info "Received Stripe webhook event type not implemented: " (:type event))
  (debug "Stripe event: " event))

(defmethod webhook "charge.succeeded"
  [conn event & _]
  (let [charge (get-in event [:data :object])]
    (prn "charge.succeeded: " charge)
    (prn "Customer: " (:customer charge))))

(defmethod webhook "charge.failed"
  [conn event & [opts]]
  (let [{:keys [customer] :as charge} (get-in event [:data :object]) ;; customer id
        {:keys [send-email-fn]} opts]
    (when customer
      ;; Find the customer entry in the db.
      (let [db-customer (p/lookup-entity (d/db conn) [:stripe/customer customer])]
        ;; If the customer is not found in the DB, something is wrong and we're out of sync with Stripe.
        (when-not db-customer
          (throw (ex-info (str "Stripe: charged.failed customer not found in db " {:stripe/customer customer})
                          {:cause   ::h/unprocessable-entity
                           :message (str "Stripe: charged.failed customer not found in db " {:stripe/customer customer})
                           :data    {:customer customer :event event}})))

        ;; Find the user corresponding to the specified stripe customer.
        (let [user (p/lookup-entity (d/db conn) (get-in db-customer [:stripe/user :db/id]))]
          ;; If the customer entity has no user, something is wrong in the db entry, throw exception.
          (when-not user
            (throw (ex-info (str "Stripe: charged.failed user not found in db for customer " {:stripe/customer customer})
                            {:cause   ::h/unprocessable-entity
                             :message (str "Stripe: charged.failed user not found in db for customer " {:stripe/customer customer})
                             :data    {:customer customer :event event}})))

          (info "Stripe charge.failed for user " (d/touch user))
          (when send-email-fn
            ;; Notify the user by ending an email to the user for the customer. That payment failed and they should check their payment settings.
            (send-email-fn (or (:user/email user) "test@email.com")
                           {:html-content #(email/html-content %
                                                               "Payment failed"
                                                               "We tried to charge your card, but it failed. Check your payment settings."
                                                               "Update payment preferences"
                                                               "link -message")
                            :text-content #(str "We tried to charge your card, but it failed. Check your payment settings. " %)
                            :subject      "Payment failed"})))))))

(defmethod webhook "customer.deleted"
  [conn event & _]
  (let [{:keys [customer]} (get-in event [:data :object])
        db-entry (p/lookup-entity (d/db conn) [:stripe/customer customer])]
    (when db-entry
      (info "Stripe customer.deleted, retracting entity from db: " (d/touch db-entry))
      (transact/transact-one conn [:db.fn/retractEntity (:db/id db-entry)]))))

(defmethod webhook "customer.subscription.created"
  [conn event & _]
  (let [subscription (get-in event [:data :object])
        {:keys [customer]} subscription
        db-customer (p/lookup-entity (d/db conn) [:stripe/customer customer])]
    (if db-customer
      (let [db-sub (f/add-tempid (json->subscription-map subscription))]
        (transact/transact conn [db-sub
                                 [:db/add (:db/id db-customer) :stripe/subscription (:db/id db-sub)]]))
      (throw (ex-info (str "No :stripe/customer found with value: " customer)
                      {:cause ::h/unprocessable-entity
                       :message (str "No :stripe/customer found with value: " customer)
                       :data {:customer customer
                              :event event}})))))

(defmethod webhook "customer.subscription.deleted"
  [conn event & _]
  (let [subscription (get-in event [:data :object])]
    (prn "Subscription" subscription)))

(defmethod webhook "invoice.payment_succeeded"
  [conn event & _]
  (let [invoice (get-in event [:data :object])
        {:keys [customer]} invoice
        {:keys [stripe/subscription]} (p/pull (d/db conn) [:stripe/subscription] [:stripe/customer customer])
        period-end (* 1000 (:period_end invoice))]
    (if subscription
      (transact/transact-one conn [:db/add (:db/id subscription) :stripe.subscription/ends-at period-end])
      (throw (ex-info (str "No subscription found for customer: " customer)
                      {:cause ::h/unprocessable-entity
                       :message (str "No subscription found for customer: " customer)
                       :data {:customer customer
                              :event event}})))))

;(defmethod webhook "invoice.payment_succeeded")