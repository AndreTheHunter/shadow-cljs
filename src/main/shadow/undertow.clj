(ns shadow.undertow
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.core.async :as async :refer (go <! >!)]
            [clojure.core.async.impl.protocols :as async-prot]
            [clojure.tools.logging :as log]
            [shadow.undertow.impl :as impl]
            [clojure.edn :as edn])
  (:import (io.undertow Undertow Handlers UndertowOptions)
           (io.undertow.websockets WebSocketConnectionCallback)
           (io.undertow.server.handlers ResponseCodeHandler BlockingHandler)
           (io.undertow.server HttpHandler)
           (shadow.undertow WsTextReceiver)
           (io.undertow.websockets.core WebSockets)
           (javax.net.ssl SSLContext KeyManagerFactory)
           (java.io FileInputStream)
           (java.security KeyStore)))

(defn ring* [handler-fn]
  (reify
    HttpHandler
    (handleRequest [_ exchange]
      (let [req (impl/exchange->ring exchange)
            res (handler-fn req)]
        (when (not= res ::async)
          (impl/ring->exchange exchange req res))
        ))))

(defn ring
  "blocking ring handler"
  [handler-fn]
  (-> (ring* handler-fn)
      (BlockingHandler.)))

(defn websocket? [{::keys [ws] :as req}]
  ws)

;; unfortunately the exchange field is private
;; and I'm too lazy to write another exchange->ring fn
(def ws-exchange-field
  (doto (-> (Class/forName "io.undertow.websockets.spi.AsyncWebSocketHttpServerExchange")
            (.getDeclaredField "exchange"))
    (.setAccessible true)))

(defn ws->ring [ex channel]
  (-> (impl/exchange->ring (.get ws-exchange-field ex))
      (assoc ::channel channel)))

(defn websocket [ring-handler]
  (Handlers/websocket
    (reify
      WebSocketConnectionCallback
      (onConnect [_ exchange channel]
        (let [ws-in (async/chan 10) ;; FIXME: allow config of these, maybe even use proper buffers
              ws-out (async/chan 10)
              ws-req (assoc (ws->ring exchange channel)
                       ::ws true
                       :ws-in ws-in
                       :ws-out ws-out)
              ws-loop (ring-handler ws-req)]

          (if (satisfies? async-prot/ReadPort ws-loop)
            (let [handler-fn
                  (fn [channel msg]
                    (if-not (some? msg)
                      (async/close! ws-in)
                      ;; FIXME: don't hardcode edn, should use transit
                      (async/put! ws-in (edn/read-string msg))))]

              (.. channel (getReceiveSetter) (set (WsTextReceiver. handler-fn)))
              (.. channel (resumeReceives))

              (go (loop []
                    (when-some [msg (<! ws-out)]
                      (WebSockets/sendTextBlocking (pr-str msg) channel)
                      (recur)))
                  ;; when out closes, also close in
                  (async/close! ws-in))

              (go (<! ws-loop)
                  (.close exchange)
                  ;; probably already closed, just in case
                  (async/close! ws-out)
                  (async/close! ws-in)))

            (do (when-not (nil? ws-loop)
                  (log/warn "websocket request not handled properly, did not return a channel" ws-loop))
                (async/close! ws-in)
                (async/close! ws-out)
                (.close exchange))))))))

(defn make-ssl-context [ssl-config]
  (let [key-manager
        (KeyManagerFactory/getInstance
          (KeyManagerFactory/getDefaultAlgorithm))

        key-store
        (KeyStore/getInstance
          (KeyStore/getDefaultType))

        pw
        (.toCharArray (get ssl-config :password "shadow-cljs"))]

    (with-open [fs (FileInputStream. (get ssl-config :keystore "ssl/keystore.jks"))]
      (.load key-store fs pw))

    (.init key-manager key-store pw)

    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers key-manager) nil nil)
      )))

;; need to delay middleware instantiation since the ws don't ever need those
;; and they aren't compatible with the way this does ws anyways
(defn start
  ([config req-handler]
    (start config req-handler identity))
  ([{:keys [port host ssl-port ssl-context] :as config} req-handler ring-middleware]
   (let [ws-handler
         (websocket req-handler)

         handler
         (-> (ring-middleware req-handler)
             (ring)
             ;; FIXME: this composition is horrible
             ((fn [next]
                (doto (Handlers/path next)
                  ;; FIXME: don't hardcode /ws, should be possible to use ws anywhere
                  ;; but the ws handler tries to handshake every get request which seems wasteful
                  (.addPrefixPath "/ws" ws-handler)))))

         instance
         (doto (-> (Undertow/builder)
                   (cond->
                     ;; start http listener when no ssl-context is set
                     ;; or if ssl-port is set in addition to port
                     (or (not ssl-context)
                         (and port ssl-port))
                     (.addHttpListener port host)

                     ;; listens in port unless ssl-port is set
                     ssl-context
                     (-> (.setServerOption UndertowOptions/ENABLE_HTTP2 true)
                         (.addHttpsListener (or ssl-port port) host ssl-context)))
                   (.setHandler handler)
                   (.build))
           (.start))]

     (reduce
       (fn [x listener]
         (assoc x (keyword (str (.getProtcol listener) "-port")) (-> listener (.getAddress) (.getPort))))
       {:instance instance}
       (.getListenerInfo instance)))))

(defn stop [{:keys [instance] :as srv}]
  (.stop instance))

(comment
  (require '[ring.middleware.file :as ring-file])
  (require '[ring.middleware.file-info :as ring-file-info])
  (require '[ring.middleware.content-type :as ring-content-type])
  (require '[shadow.cljs.devtools.server.ring-gzip :as ring-gzip])

  (defn test-ring [req]
    (if-not (websocket? req)
      {:status 200
       :body "hello world"}

      (let [{:keys [ws-in ws-out]} req]
        (go (loop []
              (when-let [msg (<! ws-in)]
                (prn [:ws-echo msg])
                (>! ws-out msg)
                (recur)))
            (prn [:ws-closed])
            ))))

  (def x (start
           {:host "localhost"
            :port 8800
            :ssl-port 8801
            :ssl-context (make-ssl-context {})}
           test-ring
           #(-> %
                (ring-content-type/wrap-content-type)
                (ring-file/wrap-file
                  (io/file "out" "demo-browser" "public")
                  {:allow-symlinks? true
                   :index-files? true})
                (ring-file-info/wrap-file-info
                  ;; source maps
                  {"map" "application/json"})
                (ring-gzip/wrap-gzip))))

  (prn x)

  (-> (:instance x)
      (.getListenerInfo)
      (first)
      (.getAddress)
      (.getPort))

  (stop x))
