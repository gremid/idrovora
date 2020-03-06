(ns idrovora.xproc
  (:import [com.xmlcalabash.core XProcConfiguration XProcRuntime]
           com.xmlcalabash.model.RuntimeValue
           com.xmlcalabash.runtime.XPipeline
           com.xmlcalabash.util.Input
           net.sf.saxon.s9api.QName))

(def config (delay (XProcConfiguration.)))

(defn run-pipeline!
  [xpl options]
  (let [^XProcRuntime runtime (XProcRuntime. @config)
        ^XPipeline pipeline (.load runtime (Input. xpl))]
    (doseq [[k v] options]
      (.. pipeline (passOption (QName. k) (RuntimeValue. v))))
    (.. pipeline (run))))

