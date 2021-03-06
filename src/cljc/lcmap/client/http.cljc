(ns lcmap.client.http
  "Our little http client needs to be able to handle three sets of options:
   * those intended for lcmap-rest itself
   * those that get passed to the underlying clj-http client library
   * and finally any options that are passed to Compojure as k/v pairs in
     the request

  As such, we have provided wrapper functions for clj-http that allow us to
  keep each of these separate from the others."
  (:require [clojure.string :as string]
            #?(:clj  [clj-http.client :as http]
               :cljs [cljs-http.client :as http])
            #?(:cljs [cljs.core.async :refer [<!]])
            #?(:clj  [clojure.tools.logging :as log]
               :cljs [taoensso.timbre :as log :include-macros true])
            #?(:clj [clojure.data.json :as json])
            #?(:clj [dire.core :refer [with-handler!]])
            #?(:clj [lcmap.client.config :as config])
            #?(:clj [lcmap.client.status-codes :as status])
            [lcmap.client.util :as util :include-macros true]
            #?(:clj [lcmap.config.helpers :refer [init-cfg]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  (:refer-clojure :exclude [get]))

;;;; XXX The first implementation of this is a bit messy, as it grew while we
;;;; explored the use cases. We definitely need to come back here and clean
;;;; this up.

;;; Functions in this namespace do not use components, so they
;;; have no other way to get config... yet.
(def ^:dynamic *http-config*
  #?(:clj  (:lcmap.client (init-cfg config/defaults))
     :cljs {}))

(def context "/api")
(def server-version "0.5")
(def default-content-type "json")
(def vendor "vnd.usgs.lcmap")

;; XXX once the service goes live, the endpoint will be something like
;;(def endpoint "http://lcmap.usgs.gov")

(def endpoint (*http-config* :endpoint "http://localhost:1077"))
(def client-version (:version (util/slurp "project.clj")))

(def project-url (:url (util/slurp "project.clj")))
(def user-agent (str "LCMAP REST Client/"
                     client-version
                     #?(:clj  " (Clojure "
                        :cljs " (ClojureScript ")
                     #?(:clj  (clojure-version)
                        :cljs *clojurescript-version*)
                     #?@(:clj  ["; Java "
                                (System/getProperty "java.version")])
                     ") (+"
                     project-url
                     ")"))

(def default-options {:endpoint endpoint
                      :return :body
                      :debug false})

(def read-json
  #?(:clj #(json/read-json % :key-fn keyword)
     :cljs #(clj-js %)))

(defn response
  "This primary purpose of this function is to codify a standard data
  structure for LCMAP HTTP responses, used by both the Clojure client as well
  as several server-side components."
  [& {:keys [result errors] :or {result nil errors []}}]
  {:result result :errors errors})

(defn format-accept [vendor version content-type]
  ;; XXX split content-type with "/" and use below, e.g.: "application/json"
  (let [[media-type suffix] (string/split content-type #"/")]
    (str media-type "/" vendor ".v" version "+"
         (or suffix default-content-type))))

(defn default-options-as-symbols []
  (into {}
        (map (fn [[k v]]
               (if-not (symbol? k)
                 [(symbol (name k)) v]
                 [k v]))
             (seq default-options))))

(defn get-base-headers
  ([]
   (get-base-headers nil nil ""))
  ([version]
   (get-base-headers version nil ""))
  ([version content-type]
   (get-base-headers version content-type ""))
  ([version content-type token]
   (log/debug "Getting base headers ...")
   (let [api-version (or version
                          (*http-config* :version server-version))
          api-content-type (or content-type
                               (*http-config* :content-type default-content-type))
          accept (format-accept vendor api-version api-content-type)]
      (log/debug "Request Accept:" accept)
      {:user-agent user-agent
       :accept accept
       :x-authtoken token})))

(defn get-http-func [method]
  #?(:clj  (case method
             :get #'http/get
             :head #'http/head
             :post #'http/post
             :put #'http/put
             :delete #'http/delete
             :options #'http/options
             :copy #'http/copy
             :move #'http/move
             :patch #'http/patch)
     :cljs (case method
             :get #'http/get
             :head #'http/head
             :post #'http/post
             :put #'http/put
             :delete #'http/delete
             :options #'http/options
             :move #'http/move
             :patch #'http/patch)))

(defn update-lcmap-opts
  "This combines the options specific to the LCMAP client in the following
  order of precedence:
   * the default options are the least important, overridden by all
   * an explicit map of options overrides the defaults
   * any keyword args provided override the defaults and an options with the
     same keyword"
  [opts]
  (log/debug "Updating lcmap options:" opts)
  (let [new-opts (into default-options (util/remove-nil opts))]
    (log/trace "Got new opts:" new-opts)
    new-opts))

(defn combine-http-opts [opts headers request & args]
  (log/tracef "Merging the following:\n%s\n%s\n%s\n%s"
              opts headers request args)
  (-> opts
      (util/deep-merge headers)
      (util/deep-merge request)
      (util/deep-merge (apply hash-map args))))

(defn get-keywords [args]
  (util/remove-nil
    (apply dissoc args [:lcmap-opts :clj-http-opts :request])))

(defn- -http-call [method path & {:keys [lcmap-opts clj-http-opts request
                                         headers client]
                                  :or {lcmap-opts {} clj-http-opts {} request {}
                                       headers {} client {}}
                                  :as args}]
  (log/trace "Got args:" args)
  (let [{endpoint :endpoint version :version content-type :content-type
         return :return debug :debug :as opts}
         (update-lcmap-opts lcmap-opts)
        token (or (get-in client [:cred-mgr :creds :token]) (:token opts))
        pool (get-in client [:conn-mgr :pool])
        http-func (get-http-func method)
        url (str (or endpoint (*http-config* :endpoint)) path)
        default-headers (get-base-headers version content-type token)
        request (combine-http-opts clj-http-opts
                                   (into default-headers headers)
                                   request
                                   :debug debug
                                   :coerce :always
                                   :throw-exceptions false
                                   :connection-manager pool)]
    (log/tracef "Making request to %s: %s" url request)
    {:result (http-func url request)
     :return return}))

(defn http-call [method path args]
  (let [{result :result
         return :return} (apply -http-call (into [method path] args))]
    (log/tracef "For return type %s, got result: %s" return result)
    (case return
      :raw result
      :body (:body (read-json (:body result)))
      :result (get-in (read-json (:body result)) [:body :result])
      :errors (get-in (read-json (:body result)) [:body :errors]))))

(defn get [path & args]
  (http-call :get path args))

(defn head [path & args]
  (http-call :head path args))

(defn post [path & args]
  (http-call :post path args))

(defn put [path & args]
  (http-call :put path args))

(defn delete [path & args]
  (http-call :delete path args))

(defn options [path & args]
  (http-call :options path args))

(defn copy [path & args]
  (http-call :copy path args))

(defn move [path & args]
  (http-call :move path args))

(defn patch [path & args]
  (http-call :patch path args))

;;; Special functions

(defn follow-link [client result & {:keys [] :as args}]
  (let [path (get-in result [:result :link :href])]
    (log/tracef "Following path %s with options: %s" path args)
    (get
      path
      :client client
      :lcmap-opts (or args {}))))

;;; Exception Handling

#?(:clj
  (with-handler! #'http-call
    [:status status/no-resource]
    (fn [e & args]
      (log/error e)
      {:status (:status e)
       :result nil
       :errors ["Resource not found"]
       :headers (:headers e)
       :args args})))
