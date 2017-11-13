(ns shadow.cljs.devtools.server.worker.ws
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
  (:require [clojure.core.async :as async :refer (go <! >! thread alt!! >!!)]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [aleph.http :as aleph]
            [manifold.deferred :as md]
            [manifold.stream :as ms]
            [clojure.edn :as edn]
            [shadow.build.output :as output]
            [shadow.cljs.util :as util]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.web.common :as common]
            [shadow.build :as comp]
            [shadow.http.router :as http]
            [shadow.build.data :as data]
            [shadow.build.resource :as rc])
  (:import (java.util UUID)))

(defn ws-loop!
  [{:keys [worker-proc watch-chan eval-out in out result-chan] :as client-state}]
  (let [{:keys [system-bus]} worker-proc]

    (worker/watch worker-proc watch-chan true)

    ;; FIXME: the client should probably trigger this
    ;; a node-repl isn't interested in this at all
    (sys-bus/sub system-bus ::sys-msg/css-reload out false)

    (loop [client-state client-state]

      (alt!!
        eval-out
        ([msg]
          (when-not (nil? msg)
            (>!! out msg)
            (recur client-state)))

        ;; forward some build watch messages to the client
        watch-chan
        ([msg]
          (when-not (nil? msg)
            (>!! out msg)
            (recur client-state)
            ))

        in
        ([msg]
          (when-not (nil? msg)
            (>!! result-chan msg)
            (recur client-state))
          )))

    (async/close! result-chan)))

(defn ws-connect
  [{:keys [ring-request] :as ctx}
   {:keys [output] :as worker-proc} client-id client-type]

  (let [client-in
        (async/chan
          1
          (map edn/read-string))

        ;; FIXME: n=10 is rather arbitrary
        client-out
        (async/chan
          (async/sliding-buffer 10)
          (map pr-str))

        eval-out
        (-> (async/sliding-buffer 10)
            (async/chan))

        result-chan
        (worker/repl-eval-connect worker-proc client-id eval-out)

        ;; no need to forward :build-log messages to the client
        watch-ignore
        #{:build-log
          :repl/result
          :repl/error ;; server-side error
          :repl/action
          :repl/eval-start
          :repl/eval-stop
          :repl/client-start
          :repl/client-stop}

        watch-chan
        (-> (async/sliding-buffer 10)
            (async/chan
              (remove #(contains? watch-ignore (:type %)))))

        client-state
        {:worker-proc worker-proc
         :client-id client-id
         :client-type client-type
         :in client-in
         :out client-out
         :eval-out eval-out
         :result-chan result-chan
         :watch-chan watch-chan}]

    (-> (aleph/websocket-connection ring-request
          {:headers
           (let [proto (get-in ring-request [:headers "sec-websocket-protocol"])]
             (if (seq proto)
               {"sec-websocket-protocol" proto}
               {}))})
        (md/chain
          (fn [socket]
            (ms/connect socket client-in)
            (ms/connect client-out socket)
            socket))

        ;; FIXME: why the second chain?
        (md/chain
          (fn [socket]
            (thread (ws-loop! (assoc client-state :socket socket)))
            ))
        (md/catch common/unacceptable))))

(defn ws-listener-connect
  [{:keys [ring-request] :as ctx}
   {:keys [output] :as worker-proc} client-id]

  (let [client-out
        (async/chan
          (async/sliding-buffer 10)
          (map pr-str))

        ;; FIXME: let the client decide?
        watch-ignore
        #{:build-log
          :repl/result
          :repl/error ;; server-side error
          :repl/action
          :repl/eval-start
          :repl/eval-stop
          :repl/client-start
          :repl/client-stop}

        watch-chan
        (-> (async/sliding-buffer 10)
            (async/chan
              (remove #(contains? watch-ignore (:type %)))))]

    (-> (aleph/websocket-connection ring-request
          {:headers
           (let [proto (get-in ring-request [:headers "sec-websocket-protocol"])]
             (if (seq proto)
               {"sec-websocket-protocol" proto}
               {}))})
        (md/chain
          (fn [socket]
            ;; FIXME: listen only or accept commands?
            ;; (ms/connect socket client-in)
            (ms/connect client-out socket)
            socket))
        (md/chain
          (fn [socket]
            (worker/watch worker-proc watch-chan true)

            (let [last-known-state
                  (-> worker-proc :state-ref deref :build-state ::comp/build-info)]
              (>!! client-out {:type :build-init
                               :info last-known-state}))

            (go (loop []
                  (when-some [msg (<! watch-chan)]
                    (>! client-out msg)
                    (recur))
                  ))))
        (md/catch common/unacceptable))))

(defn files-req
  "a POST request from the REPL client asking for the compile JS for sources by name
   sends a {:sources [...]} structure with a vector of source names
   the response will include [{:js code :name ...} ...] with :js ready to eval"
  [{:keys [ring-request] :as ctx}
   {:keys [state-ref] :as worker-proc}]

  (let [{:keys [request-method body]}
        ring-request

        headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/edn; charset=utf-8"}]

    ;; CORS sends OPTIONS first
    (case request-method

      :options
      {:status 200
       :headers headers
       :body ""}

      :post
      (let [text
            (slurp body)

            {:keys [sources] :as req}
            (edn/read-string text)

            build-state
            (:build-state @state-ref)

            module-format
            (get-in build-state [:build-options :module-format])]

        {:status 200
         :headers headers
         :body
         (->> sources
              (map (fn [src-id]
                     (assert (rc/valid-resource-id? src-id))
                     (let [{:keys [resource-name type output-name ns output] :as src}
                           (data/get-source-by-id build-state src-id)

                           {:keys [js] :as output}
                           (data/get-output! build-state src)]

                       {:resource-name resource-name
                        :resource-id src-id
                        :output-name output-name

                        ;; FIXME: make this pretty ...
                        :js
                        (case module-format
                          :goog
                          (let [sm-text (output/generate-source-map-inline build-state src output "")]
                            (str js sm-text))
                          :js
                          (let [prepend
                                (output/js-module-src-prepend build-state src false)

                                append
                                (output/js-module-src-append build-state src)

                                sm-text
                                (output/generate-source-map-inline build-state src output prepend)]

                            (str prepend js append sm-text)))
                        })))
              (into [])
              (pr-str))})

      ;; bad requests
      {:status 400
       :body "Only POST or OPTIONS requests allowed."}
      )))

(defn process
  [{::http/keys [path-tokens] :keys [supervisor] :as ctx}]

  ;; "/worker/browser/430da920-ffe8-4021-be47-c9ca77c6defd/305de5d9-0272-408f-841e-479937512782/browser"
  ;; _ _ to drop / and worker
  (let [[action build-id proc-id client-id client-type :as x]
        path-tokens

        build-id
        (keyword build-id)

        proc-id
        (UUID/fromString proc-id)

        worker-proc
        (super/get-worker supervisor build-id)]

    (cond
      (nil? worker-proc)
      {:status 404
       :body "No worker for build."}

      (not= proc-id (:proc-id worker-proc))
      {:status 403
       :body "stale client, please reload"}

      :else
      (case action
        "files"
        (files-req ctx worker-proc)

        "ws"
        (ws-connect ctx worker-proc client-id client-type)

        "listener-ws"
        (ws-listener-connect ctx worker-proc client-id)

        :else
        {:status 404
         :headers {"content-type" "text/plain"}
         :body "Not found."}))))

