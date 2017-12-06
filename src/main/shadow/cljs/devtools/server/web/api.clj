(ns shadow.cljs.devtools.server.web.api
  (:require
    [clojure.edn :as edn]
    [clojure.java.shell :as sh]
    [clojure.tools.logging :as log]
    [clojure.java.io :as io]
    [clojure.pprint :refer (pprint)]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.build.closure :as closure]
    [shadow.http.router :as http]
    [shadow.repl :as repl]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.util :as util]
    [fulcro.server :as fs]
    [shadow.cljs.devtools.config :as config]))

(defmethod fs/read-root :q/builds [env _ params]
  {:value
   (->> (:builds (config/load-cljs-edn))
        (vals)
        (into []))})

(defn fulcro-request [{:keys [transit-read transit-str fulcro-parser ring-request] :as req}]
  (let [body (-> req :ring-request :body slurp transit-read)
        result (fulcro-parser
                 {:app req
                  :parser fulcro-parser
                  :ast body}
                 body)]

    (prn [:request body])
    (let [res (fs/handle-api-request fulcro-parser {:app req :parser fulcro-parser} body)]
      (pprint res)
      (-> res
          (update :body transit-str)))))

(defn transform-level
  [{::repl/keys [root-id level-id lang] :as level}]
  (-> level
      (select-keys [::repl/root-id ::repl/level-id ::repl/lang])
      (assoc ::repl/ops (->> level
                             (keys)
                             (into #{})))))

(defn transform-root
  [{::repl/keys [type levels] :as root}]
  (-> root
      (select-keys [::repl/root-id ::repl/type])
      (assoc ::repl/levels
        (->> levels
             (map transform-level)
             (into [])))))

(defn repl-roots [req]
  (common/edn req
    (->> (repl/roots)
         (vals)
         (map transform-root)
         (into []))))

(defn repl-root [req root-id]
  (common/edn req
    (-> (repl/root root-id)
        (transform-root))))

(defn repl-level [req root-id level-id]
  (common/edn req
    (-> (repl/level root-id level-id)
        (transform-level))))

(defn open-file [{:keys [config ring-request] :as req}]
  (let [data
        (-> req
            (get-in [:ring-request :body])
            (slurp)
            (edn/read-string))

        {:keys [open-file-command]}
        config

        result
        (if-not open-file-command
          {:exit 1
           :err "no :open-file-command"}
          (let [launch-args
                (server-util/make-open-args data open-file-command)

                result
                (server-util/launch launch-args {})]
            (log/debug ::open-file launch-args result)
            result))]

    {:status 200
     :headers {"content-type" "application/edn; charset=utf-8"}
     :body (pr-str result)}))

(defn get-bundle-info [{:keys [config] :as req} build-id]
  (try
    (let [file (io/file (:cache-root config) "builds" (name build-id) "release" "bundle-info.edn")]
      (if (.exists file)
        {:status 200
         :header {"content-type" "application/edn"}
         :body (slurp file)}

        ;; could generate this on-depend but that might take a long time
        {:status 404
         :body "Report not found. Run shadow-cljs release."}))
    (catch Exception e
      {:status 503
       :body "Build failed."})))

(defn root* [req]
  (http/route req
    (:POST "" fulcro-request)
    (:GET "/repl" repl-roots)
    (:GET "/repl/{root-id:long}" repl-root root-id)
    (:GET "/repl/{root-id:long}/{level-id:long}" repl-level root-id level-id)
    (:GET "/bundle-info/{build-id:keyword}" get-bundle-info build-id)
    (:POST "/open-file" open-file)
    common/not-found))

(defn root [{:keys [ring-request] :as req}]
  ;; not the cleanest CORS implementation but I want to call API methods from the client
  (let [headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/edn; charset=utf-8"}]

    (if-not (= :options (:request-method ring-request))
      (-> req
          (root*)
          (update :headers merge headers))
      {:status 200
       :headers headers
       :body ""}
      )))

