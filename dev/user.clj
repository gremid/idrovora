(ns user
  (:require [idrovora.cli :as cli]
            [mount.core :as mount]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn start
  []
  (cli/start {:http 3000} "text/xpl"))

(defn stop
  []
  (cli/stop))


