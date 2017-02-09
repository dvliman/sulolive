(ns eponai.server.api.store
  (:require
    [eponai.common.database :as db]
    [eponai.server.datomic.format :as f]
    [eponai.server.external.aws-s3 :as s3]
    [eponai.server.external.stripe :as stripe]
    [taoensso.timbre :refer [debug info]]))

(defn create-product [{:keys [state system]} store-id {:keys [id photo] product-name :name :as params}]
  {:pre [(string? product-name) (uuid? id)]}
  (let [{:keys [stripe/secret]} (stripe/pull-stripe (db/db state) store-id)
        stripe-p (stripe/create-product (:system/stripe system) secret params)
        photo-upload (when photo (s3/upload-photo (:system/aws-s3 system) photo))

        db-product (f/product params)
        txs (cond-> [db-product
                     [:db/add store-id :store/items (:db/id db-product)]]
                    (some? photo-upload)
                    (conj photo-upload [:db/add (:db/id db-product) :store.item/photos (:db/id photo-upload)]))]
    (debug "Created product in stripe: " stripe-p)
    ;(debug "Uploaded item photo: " photo-upload)
    ;(info "Transacting new product: " txs)
    (db/transact state txs)))

(defn update-product [{:keys [state system]} store-id product-id {:keys [photo] :as params}]
  (let [{:keys [store.item/uuid store.item/photos]} (db/pull (db/db state) [:store.item/uuid :store.item/photos] product-id)
        {:keys [stripe/secret]} (stripe/pull-stripe (db/db state) store-id)
        old-photo (first photos)

        ;; Update product in Stripe
        stripe-p (stripe/update-product (:system/stripe system)
                                        secret
                                        (str uuid)
                                        params)
        new-item {:store.item/uuid uuid
                  :store.item/name (:name params)}

        ;; Upload photo
        photo-upload (when photo (s3/upload-photo (:system/aws-s3 system) photo))
        txs (if (some? photo-upload)
              (if (some? old-photo)
                [new-item
                 (assoc photo-upload :db/id (:db/id old-photo))]
                [new-item
                 photo-upload
                 [:db/add [:store.item/uuid uuid] :store.item/photos (:db/id photo-upload)]])
              [new-item])]
    (debug "Updated product in stripe: " stripe-p)
    (debug "Transaction into datomic: " txs)
    (db/transact state txs)))

(defn delete-product [{:keys [state system]} product-id]
  (let [{:keys [store.item/uuid]} (db/pull (db/db state) [:store.item/uuid] product-id)
        {:keys [stripe/secret]} (db/pull-one-with (db/db state) [:stripe/secret] {:where   '[[?s :store/items ?p]
                                                                                             [?s :store/stripe ?e]]
                                                                                  :symbols {'?p product-id}})
        stripe-p (stripe/delete-product (:system/stripe system)
                                        secret
                                        (str uuid))]
    (debug "Deleted product in stripe: " stripe-p)
    (db/transact state [[:db.fn/retractEntity product-id]])))