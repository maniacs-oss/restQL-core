(ns restql.core.async-request-builder
  (:require [restql.core.query :as query]
            [restql.core.encoders.core :as encoders]
            [restql.core.log :refer [info debug]]
            [restql.core.url-matcher :as matcher]
            [restql.core.extractor :refer [traverse has-multiples extract-multiple]]
            [cheshire.core :as json]
            [slingshot.slingshot :refer [throw+]]
            ))

(defn- filter-keys [fun map]
  (reduce-kv (fn [m k v]
               (if (fun v)
                 (assoc m k v)
                 m)) {} map))

(defn- attach-meta [data origin]
  (if (coll? data)
    (with-meta data (meta origin))
    data))

(defn- map-values [func a-map]
  (reduce-kv (fn [m k v]
               (assoc m k (attach-meta (func v) v))) {} a-map))

(defn should-expand-literal? [literal]
  (let [meta-param (-> literal meta :expand)]
    (cond
      (nil? meta-param) true
      :else             meta-param)))

(defn get-literal-vectors [query-item-data]
  (->> query-item-data
       :with
       vals
       (filter vector?)
       (filter should-expand-literal?)
       (filter (complement query/vector-of-keywords?))))

(defn get-higher-status [responses]
  (->> responses (sort-by :status) last))

(defn reduce-to-higher-status [responses]
  (let [higher-status (get-higher-status responses)
        body (map :body responses)]
    (assoc higher-status :body body)))

(defn format-entity-data [entity-data]
  (let [value (second entity-data)]
    (if (sequential? value)
      (reduce-to-higher-status value)
      value)))

(defn get-multiple-path-data [[entity & path] state]
  (let [entity-data (query/find-query-item entity (:done state))
        entity-data-value (format-entity-data entity-data)
        entity-value (extract-multiple entity-data-value (into [:body] path)) ]
    (assoc entity-value :entity entity)))

(defn is-multiple-entity? [[entity & path] state]
  (let [[entity-name entity-value] (query/find-query-item entity (:done state))]
    (or
      (has-multiples entity-value path)
      (has-multiples (:body entity-value) path))))

(defn is-expandable? [[param value]]
  (if (contains? (meta value) :expand)
    (-> value meta :expand)
    true))

(defn remove-non-expandable-params [with]
  (into (with-meta {} (meta with)) (filter is-expandable? with)))

(defn get-multiple-paths [query-item-data state]
  (->> (query/get-dependency-paths
         (update-in query-item-data [:with] remove-non-expandable-params))
       (filter #(is-multiple-entity? % state))
       (into #{})))

(defn format-for-expansion [multiple-item]
  {:body     (:body multiple-item)
   :path     (:path multiple-item)
   :fullpath (reduce into [(:entity multiple-item)]
                     [(-> multiple-item :base rest)
                      (:path multiple-item)])})

(defn get-multiple-data [query-item-data state]
  (let [paths              (get-multiple-paths query-item-data state)
        multiple-literals  (get-literal-vectors query-item-data)
        values             (map #(get-multiple-path-data % state) paths)
        chained-values     (map format-for-expansion values)
        formatted-literals (map (fn [lit] {:body lit
                                           :literal true}) multiple-literals)]
    (reduce into #{} [chained-values formatted-literals])))

(defn requests-from-header? [path]
  (and (= :headers (first path)) (string? (second path))))

(defn get-value-from-header [path headers]
  ((-> path second keyword) headers))

(defn get-value-from-body [path body]
  (traverse body path))

(defn get-reference-from-value [data path]
  (if (requests-from-header? path)
    (get-value-from-header path (:headers data))
    (get-value-from-body (conj path :body) data)))

(defn get-reference-from-state [[ref-key & path] state]
  (-> (filter (fn [[k _]] (= ref-key k)) (:done state))
      first
      second
      (get-reference-from-value path)))

(defn interpolate-map-item [multiple-entities value entity-item]
  (map-values (fn [map-item]
                (let [x (->> multiple-entities (filter #(= map-item (:fullpath %))) first)]
                  (if (nil? x)
                    map-item
                    (traverse entity-item (:path x))))) value))

(declare interpolate-item)
(defn interpolate-map-items [multiple-entities value state]
  (let [entity-body   (->> multiple-entities (map :body) first)
        resolved-maps (map (partial interpolate-map-item multiple-entities value) entity-body)]
    (map (fn [item]
           (map-values (partial interpolate-item state) item)) resolved-maps)))

(defn interpolate-map [state value]
  (let [multiple-items (get-multiple-data {:with value} state)
        chained-items  (filter #(not (:literal %)) multiple-items)]
    (case (count (->> chained-items (map :body) (into #{}) ))
      0 (map-values (partial interpolate-item state) value)
      1 (interpolate-map-items chained-items value state)
      (throw+ {:type :invalid-parameter-repetition
               :message (str "Repeating a parameter based on more than one list is not allowed: Details\n"
                             (pr-str (count (->> chained-items (map :body) (into #{}))))
                             )}))))

(defn interpolate-item [state value]
  (cond
    (query/vector-of-keywords? value) (get-reference-from-state value state)
    (vector? value) (into []  (map #(interpolate-item state %) value))
    (map? value) (interpolate-map state value)
    :else value))

(defn transform [state encoders value]
  (let [resolved (attach-meta (interpolate-item state value) value)]
    (if (sequential? resolved)
      (into []  (map #(encoders/encode encoders (attach-meta % value)) resolved))
      (encoders/encode encoders resolved))))

(defn resolve-query-item [{params :with} encoders state]
  (map-values (partial transform state encoders) params))

(defn has-nil? [m]
  (cond
    (and (coll? m) (empty? m)) false
    (map? m) (reduce-kv (fn [has _ v] (or has (has-nil? v))) false m)
    (sequential? m) (->> m (map has-nil?) (some #{true}))
    :else (nil? m)))

(defn is-forced-url [query-item-data]
  (string? (:from query-item-data)))

(defn build-query-params [query-item-data url resolved-query-item]
  (if (is-forced-url query-item-data)
    nil
    (matcher/dissoc-params url resolved-query-item)))



(defn strip-nils [item]
  (cond
    (sequential? item) (->> item
                            (filter #(not (nil? %)))
                            (map strip-nils)
                            (into []))
    (map? item) (->> item
                     (filter-keys #(not (nil? %)))
                     (map-values strip-nils))
    :else item))

(defn success? [state-item]
  (let [value (second state-item)
        status (:status value)]
    (cond
      (nil? status)   false
      (< status 200)  false
      (>= status 300) false
      :else           true)))

(defn all-true [values]
  (reduce (fn [result item] (and result item)) true values))

(defn are-dependencies-ok? [query-item-data state]
  (->> (query/get-dependencies-from-data query-item-data)
       (map #(query/find-query-item % (:done state)))
       (map success?)
       (all-true)))

(defn build-request [url query-item-data encoders state]
  (if (are-dependencies-ok? query-item-data state)
    (let [resolved-query-item (strip-nils (resolve-query-item query-item-data encoders state))
        timeout             (:timeout query-item-data)]
      {:url          (matcher/interpolate url resolved-query-item)
       :metadata     (meta query-item-data)
       :resource     (:from query-item-data)
       :query-params (build-query-params query-item-data url resolved-query-item)
       :timeout      timeout
       :headers      (:with-headers query-item-data)})

    nil))


(defn get-multiple-requests [query-item-data state]
  (->> (get-multiple-paths query-item-data state)
       (map first)
       (into #{})))

(defn get-multiple-entities [query-item-data state]
  (->> (get-multiple-data query-item-data state)
       (map :body)
       (into #{})))

(defn interpolate-template-value [multiple-item multiple-data template-value]
  (let [definition  (->> multiple-data
                         (filter #(= template-value (:fullpath %)))
                         first)]
    (if (nil? definition)
      template-value
      (traverse multiple-item (:path definition)))))

(defn substitute-expansion-literal [multiple-item multiple-data template-value]
  (let [definition (->> multiple-data
                        (filter :literal)
                        first)]
    (cond
      (nil? definition) template-value
      (= (:body definition) template-value) multiple-item
      :else template-value)))

(defn interpolate-template-item
  ([multiple-item multiple-data template-item]
   (interpolate-template-item multiple-item multiple-data template-item true))

  ([multiple-item multiple-data template-item expand-literal]
   (cond
     (map? template-item)
     (map-values #(interpolate-template-item multiple-item multiple-data % false) template-item)

     (query/vector-of-keywords? template-item)
     (interpolate-template-value multiple-item multiple-data template-item)

     (sequential? template-item)
     (if expand-literal
       (substitute-expansion-literal multiple-item multiple-data template-item)
       (into []  (map #(interpolate-template-item multiple-item multiple-data % false) template-item)))

     :else template-item)))

(defn resolve-template-query [multiple-item multiple-data template-query]
  (map-values (fn [value]
                (interpolate-template-item multiple-item multiple-data value))
              template-query))

(defn build-query [query-item-data multiple-item multiple-data]
  (update-in query-item-data [:with]
             #(resolve-template-query multiple-item multiple-data %)))

(defn build-multiple-requests [url query-item-data encoders state]
  (let [multiple-data (get-multiple-data query-item-data state)
        entity-body   (->> multiple-data (map :body) first)
        requests (map #(build-request url (build-query query-item-data % multiple-data) encoders state) entity-body)]
    requests))

(defn build-requests
  "takes a url as a string;
   a query-item-data structure, that looks like the following: {:from :endpoint :with {:id 123}}; and a state, representing the current resolved query items.  Returns a list of requests, that are simply maps with {:keys [url timeout query-items headers]"
  [url query-item-data encoders state]
  (let [multiple-entities (get-multiple-entities query-item-data state)
        expanded (count multiple-entities)]
    (cond
      (= 0 expanded) (build-request url query-item-data encoders state)
      (= 1 expanded) (build-multiple-requests url
                                              query-item-data
                                              encoders
                                              state)
      :else          (throw+ {:type :expansion-error :message "Query tried to expand based on multiple entities. This is not allowed."} ))))
