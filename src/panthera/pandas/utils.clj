(ns panthera.pandas.utils
  (:require
    [libpython-clj.python :as py]
    ;[libpython-clj.python.protocols :as py-proto]
    ;[libpython-clj.python.interpreter :as py-interp]
    [camel-snake-kebab.core :as csk]
    [camel-snake-kebab.extras :as cske]
    [clojure.core.memoize :as m]))

(py/initialize!)

(defonce builtins (py/import-module "builtins"))
(defonce pd (py/import-module "pandas"))

(defn slice
  ([]
   (py/call-attr builtins "slice" nil))
  ([start]
   (py/call-attr builtins "slice" start))
  ([start stop]
   (py/call-attr builtins "slice" start stop))
  ([start stop incr]
   (py/call-attr builtins "slice" start stop incr)))

(defn pytype
  [obj]
  (py/python-type obj))

(def memo-key-converter
  (m/fifo csk/->snake_case_string {} :fifo/threshold 512))

(def memo-columns-converter
  (m/fifo
    #(if (number? %)
       %
       (csk/->kebab-case-keyword %)) {} :fifo/threshold 512))

(defn vec->pylist
  [v]
  (py/->py-list v))

(defn nested-vector?
  [v]
  (some vector? v))

(defn nested-slice?
  [v]
  (some #(identical? :slice (pytype %)) v))

(defn vals->pylist
  [obj]
  (cond
    (not (coll? obj)) obj
                      (map? obj) obj
                      (nested-vector? obj) (to-array-2d obj)
                      (vector? obj) (if (nested-slice? obj)
                                      obj
                                      (py/->py-list obj))
                      :else obj))

(defn keys->pyargs
  [m]
  (let [nm (reduce-kv
             (fn [m k v]
               (assoc m k (vals->pylist v)))
             {} m)]
    (cske/transform-keys memo-key-converter nm)))

(defn series?
  [obj]
  (identical? :series (pytype obj)))

(defn data-frame?
  [obj]
  (identical? :data-frame (pytype obj)))

(defn ->clj
  [df-or-srs]
  (if (series? df-or-srs)
    (let [nm (memo-columns-converter
               (or (py/get-attr df-or-srs "name")
                   :unnamed))]
      (into [] (map #(assoc {} nm %))
            (vec df-or-srs)))
    (let [ks (map memo-columns-converter
                  (py/get-attr df-or-srs "columns"))]
      (into [] (map #(zipmap ks %))
            (py/get-attr df-or-srs "values")))))

(defn simple-kw-call
  [df kw & [attrs]]
  (py/call-attr-kw df kw []
                   (keys->pyargs attrs)))

(defn kw-call
  [df kw pos & [attrs]]
  (py/call-attr-kw df kw [(vals->pylist pos)]
                   (keys->pyargs attrs)))

(comment
  "Proposed temporary workaround to the issue with ndarrays of bools"

  (defn- iteritems-iterator
    [pyobj interpreter]
    (py-interp/with-interpreter
      interpreter
      (-> (py/call-attr pyobj "iteritems")
          (py-proto/python-obj-iterator interpreter))))

  (defmethod py-proto/python-obj-iterator :series
    [pyobj interpreter]
    (iteritems-iterator pyobj interpreter))


  (defmethod py-proto/python-obj-iterator :data-frame
    [pyobj interpreter]
    (iteritems-iterator pyobj interpreter)))