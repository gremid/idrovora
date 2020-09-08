(ns idrovora.job-test
  (:require  [clojure.test :refer :all]
             [clj-http.client :as http]
             [idrovora.cli :as cli]
             [idrovora.fs :as fs]
             [clojure.tools.logging :as log]))

(defn cli-fixture
  [f]
  (try
    (cli/start "--http" "3000" "--http-context-path" "/xproc" "test/xpl")
    (f)
    (finally
      (cli/stop))))

(use-fixtures :once cli-fixture)

(deftest create-and-remove-job
  (is
   (as-> {:input "<root/>"} $
     (assoc {:accept :edn :as :clojure} :form-params $)
     (http/post "http://localhost:3000/xproc/copy/" $)
     (get-in $ [:body :_links :job :href])
     (http/delete $ {:accept :edn :as :clojure}))))

