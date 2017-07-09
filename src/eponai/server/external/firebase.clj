(ns eponai.server.external.firebase
  (:require
    [clj-http.client :as http]
    [taoensso.timbre :refer [debug]]
    [cheshire.core :as cheshire]
    [clojure.java.io :as io]
    [eponai.common :as c]
    [eponai.common.format.date :as date]
    [buddy.core.codecs.base64 :as b64])
  (:import (com.google.firebase FirebaseOptions FirebaseOptions$Builder FirebaseApp)
           (com.google.firebase.auth FirebaseAuth FirebaseCredential FirebaseCredentials)
           (com.google.firebase.database FirebaseDatabase DatabaseReference ValueEventListener DataSnapshot DatabaseError DatabaseReference$CompletionListener)
           (com.google.firebase.tasks OnSuccessListener)))

(def firebase-db "https://leafy-firmament-160421.firebaseio.com/")


(defonce database (atom nil))

(defn db-ref->value [^DatabaseReference db cb]
  "Returns the value snapshot for a db ref"
  (do (-> db
          (.addValueEventListener
            (reify ValueEventListener
              (^void onDataChange [this ^DataSnapshot snapshot]
                (cb snapshot))
              (^void onCancelled [this ^DatabaseError error]
                (debug "FIREBASE - Error retrieving data: " error)))))))


(defprotocol IFirebaseNotifications
  (-send [this user-id data])
  (-register-token [this user-id token])
  (-get-token [this user-id cb]))

(defprotocol IFirebaseAuth
  (-generateAuthToken [this user-id claims]))

(defprotocol IFirebaseChat
  (-user-online [this store-id]))

(defrecord Firebase [server-key private-key private-key-id]
  IFirebaseChat
  (-user-online [this user-id]
    (debug "Check online status: " user-id)
    (let [ref (.getReference @database (str "presence/" user-id))
          p (promise)]
      (db-ref->value ref (fn [snapshot]
                           (let [v (.getValue snapshot)]
                             (deliver p v))))
      (deref p 2000 :firebase/store-online-timeout)))

  IFirebaseAuth
  (-generateAuthToken [this user-id claims]
    (let [fb-instance (FirebaseAuth/getInstance)
          p (promise)]
      (-> (.createCustomToken fb-instance (str user-id) (clojure.walk/stringify-keys claims))
          (.addOnSuccessListener
            (reify OnSuccessListener
              (^void onSuccess [this customtoken]
                (debug "FIREBASE GOT TOKEN: " customtoken)
                (deliver p customtoken)))))
      (deref p 2000 :firebase/token-timeout)))

  IFirebaseNotifications
  (-send [this user-id {:keys [title message subtitle] :as params}]
    (let [new-notification {:timestamp (date/current-millis)
                            :title     (c/substring title 0 100)
                            :subtitle  (c/substring subtitle 0 100)
                            :message   (c/substring message 0 100)}
          user-notifications-ref (.getReference @database (str "notifications/" user-id))]
      (-> (.push user-notifications-ref)
          (.setValue (clojure.walk/stringify-keys new-notification)))

      ;; TODO enable when we are ready to send web push notifications
      (comment
        (-get-token this user-id (fn [token]
                                   (debug "FIREBASE - send chat notification: " new-notification)
                                   (http/post "https://fcm.googleapis.com/fcm/send"
                                              {:form-params {:to   token
                                                             :data new-notification}
                                               :headers     {"Authorization" (str "key=" server-key)}}))))))
  (-register-token [this user-id token]
    (when user-id

      (let [tokens-ref (.getReference @database "tokens")]
        (.updateChildren tokens-ref {(str user-id) token}))))
  (-get-token [this user-id cb]
    (db-ref->value
      (.getReference @database "tokens")
      (fn [tokens]
        (let [token (some-> (.getValue tokens)
                            (.get (str user-id)))]
          (cb token))))))

(defn firebase [{:keys [server-key private-key private-key-id service-account]}]
  (let [db (when (and (nil? @database) (some? service-account))
             (let [service-account-dec (b64/decode service-account)]
               (with-open
                 [service-account (io/input-stream service-account-dec)]
                 (let [opts (->
                              (FirebaseOptions$Builder.)
                              (.setCredential (FirebaseCredentials/fromCertificate service-account))
                              (.setDatabaseUrl firebase-db)
                              (.build))]
                   (FirebaseApp/initializeApp opts)
                   (FirebaseDatabase/getInstance)))))]
    (when db (reset! database db)))
  (map->Firebase {:server-key server-key :private-key private-key :private-key-id private-key-id}))

(defn firebase-stub []
  (reify
    IFirebaseChat
    (-user-online [this store-id])
    IFirebaseAuth
    (-generateAuthToken [this user-id claims]
      "some-token")
    IFirebaseNotifications
    (-send [this user-id data])
    (-register-token [this user-id token])
    (-get-token [this user-id cb]
      (cb "some token"))))