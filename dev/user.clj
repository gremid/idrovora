(ns user
  (:require [idrovora.cli :as cli]))

(require '[mount.core :as mount])

(set! *warn-on-reflection* true)

(defn start
  []
  (cli/start "--http" "3000" "--http-context-path" "/xproc" "test/xpl"))

(defn stop
  []
  (cli/stop))


