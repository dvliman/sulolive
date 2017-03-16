(ns eponai.server.system
  (:require
    [com.stuartsierra.component :as c]
    [compojure.core :as compojure]
    [taoensso.timbre :refer [debug]]
    [eponai.server.middleware :as m]
    [eponai.common.parser :as parser]
    [eponai.server.routes :as server-routes]
    [environ.core :as environ]
    [eponai.server.external.aleph :as aleph]
    [eponai.server.external.aws-ec2 :as ec2]
    [eponai.server.external.aws-elb :as elb]
    [eponai.server.external.host :as server-address]
    [eponai.server.external.datomic :as datomic]
    [eponai.server.external.chat :as chat]
    [eponai.server.external.auth0 :as auth0]
    [eponai.server.websocket :as websocket]
    [eponai.server.external.wowza :as wowza]
    [eponai.server.external.mailchimp :as mailchimp]
    [eponai.server.external.stripe :as stripe]
    [eponai.server.external.aws-s3 :as s3]))

;; Leaving this here because it's hard to say where it should go?
(defrecord RequestHandler [in-production? auth0 datomic cljs-build-id
                           disable-ssl disable-anti-forgery system-atom]
  c/Lifecycle
  (start [this]
    (let [conn (:conn datomic)
          handler (-> (compojure/routes server-routes/site-routes)
                      (cond-> (not in-production?) (m/wrap-node-modules))
                      m/wrap-post-middlewares
                      (m/wrap-authenticate conn auth0)
                      m/wrap-format
                      (m/wrap-state {::m/conn                conn
                                     ::m/in-production?      in-production?
                                     ::m/empty-datascript-db (m/init-datascript-db conn)
                                     ::m/parser              (parser/server-parser)
                                     ::m/cljs-build-id       (or cljs-build-id "dev")}
                                    {::m/system (fn [] (deref system-atom))})
                      (m/wrap-defaults in-production? disable-anti-forgery)
                      m/wrap-trace-request
                      (cond-> (and in-production? (not disable-ssl))
                              m/wrap-ssl)
                      (m/wrap-error in-production?))]
      (assoc this :handler handler)))
  (stop [this]
    (dissoc this :handler)))

(defn- system [in-prod? {:keys [env] :as config}]
  (let [system-atom (atom nil)
        system-map (c/system-map
                     :system/aleph (c/using (aleph/map->Aleph (select-keys config [:handler :port :netty-options]))
                                            {:handler :system/handler})
                     :system/auth0 (c/using (auth0/map->Auth0 {:client-id     (:auth0-client-id env)
                                                               :client-secret (:auth0-client-secret env)})
                                            {:server-address :system/server-address})
                     :system/aws-ec2 (ec2/aws-ec2)
                     :system/aws-elb (c/using (elb/map->AwsElasticBeanstalk {})
                                              {:aws-ec2 :system/aws-ec2})
                     :system/aws-s3 (s3/map->AwsS3 {:bucket     (:aws-s3-bucket-photos env)
                                                    :zone       (:aws-s3-bucket-photos-zone env)
                                                    :access-key (:aws-access-key-id env)
                                                    :secret     (:aws-secret-access-key env)})
                     :system/chat (c/using (chat/map->DatomicChat {})
                                           {:datomic :system/datomic})
                     :system/chat-websocket (c/using (websocket/map->StoreChatWebsocket {})
                                                     {:chat :system/chat})
                     :system/datomic (datomic/map->Datomic {:db-url (:db-url env)
                                                            :fork?  (not in-prod?)})
                     :system/handler (c/using (map->RequestHandler {:disable-ssl          (::disable-ssl config)
                                                                    :disable-anti-forgery (::disable-anti-forgery config)
                                                                    :in-production?       in-prod?
                                                                    :cljs-build-id        (:cljs-build-id env "dev")
                                                                    :system-atom          system-atom})
                                       {:auth0   :system/auth0
                                        :datomic :system/datomic})
                     :system/mailchimp (mailchimp/mail-chimp (:mail-chimp-api-key env))
                     :system/server-address (c/using (server-address/map->ServerAddress {:schema (:server-url-schema env)
                                                                                         :host   (:server-url-host env)})
                                                     {:aws-elb :system/aws-elb})
                     :system/stripe (stripe/stripe (env :stripe-secret-key))
                     :system/wowza (wowza/wowza {:secret         (:wowza-jwt-secret env)
                                                 :subscriber-url (:wowza-subscriber-url env)
                                                 :publisher-url  (:wowza-publisher-url env)}))]
    (reset! system-atom system-map)
    ;; Returns the system wrapped in an atom in case we want to adjust it more.
    ;; The final state of the system atom will be the one assoc'ed in the request.
    system-atom))

(defn old-system-keys []
  #{:system/auth0
    :system/chat
    :system/chat-websocket
    :system/wowza
    :system/mailchimp
    :system/stripe
    :system/aws-ec2
    :system/aws-elb
    :system/aws-s3})

(defn prod-system [config]
  {:post [(every? (set (keys %)) (old-system-keys))]}
  (let [config (assoc config :env environ/env)
        system (system true config)
        in-aws? (some? (get-in config [:env :aws-elb]))]
    (when (not in-aws?)
      (swap! system assoc
             :system/aws-elb (elb/aws-elastic-beanstalk-stub)
             :system/aws-ec2 (ec2/aws-ec2-stub)))
    @system))

(defn dev-system [config]
  (let [{:keys [env] :as config} (assoc config :env environ/env
                                               ::disable-ssl true
                                               ::disable-anti-forgery true)
        dev-system (system false config)]
    (swap! dev-system assoc
           ;; Comment out things to use the production ones.
           :system/auth0 (c/using (auth0/map->FakeAuth0 {})
                                  {:datomic :system/datomic})
           :system/aws-elb (elb/aws-elastic-beanstalk-stub)
           :system/aws-ec2 (ec2/aws-ec2-stub)
           :system/aws-s3 (s3/aws-s3-stub)
           :system/wowza (wowza/wowza-stub {:secret (:wowza-jwt-secret env)})
           :system/mailchimp (mailchimp/mail-chimp-stub)
           ;; :system/stripe (stripe/stripe-stub)
           )
    @dev-system))