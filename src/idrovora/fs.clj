(ns idrovora.fs
  (:refer-clojure :exclude [ancestors descendants])
  (:require [clojure.java.io :as io]
            [muuntaja.core :as m]
            [muuntaja.format.core :as m-format])
  (:import [java.io ByteArrayOutputStream File]
           java.nio.charset.Charset
           [java.nio.file CopyOption Files LinkOption Path]
           [java.util.zip ZipEntry ZipOutputStream]))

(extend-protocol io/Coercions
  Path
  (as-file [^Path p] (.toFile p))
  (as-url [^Path p] (.. p (toFile) (toURI) (toURL))))

(defn ^File file
  [& args]
  (let [^File f (apply io/file args)]
    (.getCanonicalFile f)))

(defn file?
  [& args]
  (.isFile (apply file args)))

(defn directory?
  [& args]
  (.isDirectory (apply file args)))

(defn ^String path
  [& args]
  (.getPath (apply file args)))

(defn ^String uri
  [& args]
  (.. (apply file args) (toURI) (toASCIIString)))

(def ^:private no-link-options (make-array LinkOption 0))

(defn ^Path file-path
  [& args]
  (let [^File f (apply file args)
        ^Path p (.toPath f)]
    (if (.exists f) (.toRealPath p no-link-options) p)))

(defn ^Path resolve-path
  [base ^Path p]
  (.resolve (file-path base) p))

(defn ^Path relativize-path
  [base f]
  (.relativize (file-path base) (file-path f)))

(defn ancestors
  [& args]
  (let [^File f (apply file args)
        ^File parent (.getParentFile f)]
    (concat [f] (when parent (ancestors parent)))))

(defn descendants
  [& args]
  (let [^File f (apply file args)]
    (if (.isDirectory f)
      (let [children (sort #(compare %2 %1) (.listFiles f))]
        (lazy-cat (mapcat descendants children) children)))))

(defn ancestor?
  [a f]
  (.startsWith (file-path f) (file-path a)))

(def ^:private copy-options
  (make-array CopyOption 0))

(defn copy
  [src dest]
  (let [^File dest (file dest)
        ^File dest-parent (.getParentFile dest)]
    (.mkdirs dest-parent)
    (Files/copy (file-path src) (file-path dest) copy-options)))

(defn delete!
  [^File f & [silently]]
  (when (.isDirectory f)
    (doseq [^File c (.listFiles f)] (delete! c)))
  (io/delete-file f silently))

(defn make-dir!
  [& args]
  (let [^File f (apply file args)]
    (when-not (.isDirectory f) (.mkdirs f))
    f))

(defn clear-dir!
  [& args]
  (let [^File f (apply file args)]
    (when (.isDirectory f) (delete! f))
    (make-dir! f)))

(defn encode-dirs-and-collections
  [request {:keys [body] :as response}]
  (or (and (instance? File body) (directory? body))
      (m/encode-collections request response)))

(defn file->zip-stream
  [^File d os]
  (with-open [zip-os (ZipOutputStream. os (Charset/forName "UTF-8"))]
    (let [file->path (comp str (partial relativize-path d))]
      (doseq [f (filter file? (file-seq d))]
        (.putNextEntry zip-os (ZipEntry. (file->path f)))
        (with-open [is (io/input-stream f)] (io/copy is zip-os))
        (.closeEntry zip-os)))))

(def zip-format
  (m-format/map->Format
   {:name "application/zip"
    :encoder [(fn [_]
                (reify
                  m-format/EncodeToBytes
                  (encode-to-bytes [_ f _]
                    (let [buf (ByteArrayOutputStream.)]
                      (file->zip-stream f buf)
                      (.toByteArray buf)))
                  m-format/EncodeToOutputStream
                  (encode-to-output-stream [_ f _]
                    (partial file->zip-stream f))))]}))

(def muuntaja
  (-> m/default-options
      (assoc-in [:http :encode-response-body?] encode-dirs-and-collections)
      (assoc-in [:formats "application/zip"] zip-format)
      (m/create)))
