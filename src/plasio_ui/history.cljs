(ns plasio-ui.history
  "URL and history stuff, most of the stuff here is HTML5 but will try not to fail"
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]
            [clojure.string :refer [join split]]))

;; define all paths from to compressed name mappings, this will be used to construct URLs and to
;; decode information back from these URLs
(def ^:private path-mappers
  [[[:server] "s" :string]
   [[:resource] "r" :string-or-string-vec]
   [[:camera :azimuth] "ca" :number]
   [[:camera :elevation] "ce" :number]
   [[:camera :target] "ct" :vector3]
   [[:camera :distance] "cd" :number]
   [[:camera :max-distance] "cmd" :number]
   [[:ro :circular?] "cp" :boolean]
   [[:ro :point-size] "ps" :number]
   [[:ro :point-size-attenuation] "pa" :number]
   [[:pm :z-exaggeration] "ze" :number]

   ;; Active filter
   [[:ro :filter] "filter" :string]

   ;; EDL
   [[:ro :edl?] "edl" :boolean]
   [[:ro :edl-strength] "edls" :number]
   [[:ro :edl-radius] "edlr" :number]

   ;; whether we have split limiting
   [[:ro :no-split-limiting] "nsl" :boolean]

   ;; per channel values
   [[:ro :channels :channel0 :source] "c0s" :string]
   [[:ro :channels :channel0 :contribution] "c0c" :number]
   [[:ro :channels :channel0 :range-clamps] "c0r" :vector2]

   [[:ro :channels :channel1 :source] "c1s" :string]
   [[:ro :channels :channel1 :contribution] "c1c" :number]
   [[:ro :channels :channel1 :range-clamps] "c1r" :vector2]

   [[:ro :channels :channel2 :source] "c2s" :string]
   [[:ro :channels :channel2 :contribution] "c2c" :number]
   [[:ro :channels :channel2 :range-clamps] "c2r" :vector2]

   [[:ro :channels :channel3 :source] "c3s" :string]
   [[:ro :channels :channel3 :contribution] "c3c" :number]
   [[:ro :channels :channel3 :range-clamps] "c3r" :vector2]])

(defn all-url-keys []
  (mapv first path-mappers))

(defmulti compress-entity first)
(defmulti decompress-entity first)

(defn- required-precision [val]
  (let [scaled (js/Math.floor (* 1000 val))]
    (cond
      (pos? (rem scaled 10)) 3
      (pos? (rem scaled 100)) 2
      (pos? (rem scaled 1000)) 1
      :else 0)))

(defmethod compress-entity :number [[_ val]]
  (.toFixed val (required-precision val)))

(defmethod compress-entity :string [[_ val]]
  val)

(defmethod compress-entity :string-or-string-vec [[_ val]]
  (if (sequential? val)
    (str/join "," (map str val))
    (str val)))

(defn unparse-vec [val]
  (clojure.string/join "," (map #(compress-entity [:number %]) val)))

(defmethod compress-entity :vector3 [[_ val]]
  (unparse-vec val))

(defmethod compress-entity :vector2 [[_ val]]
  (unparse-vec val))

(defmethod compress-entity :boolean [[_ val]]
  (if (true? val) "1" "0"))

(defmethod compress-entity :keyword [[_ val]]
  (name val))

(defmethod compress-entity :default [[t val]]
  (throw (js/Error. (str "Don't know how to compress URL entity of type: " t " with value: " val))))

(defmethod decompress-entity :number [[_ val]]
  (js/parseFloat val))

(defmethod decompress-entity :string [[_ val]]
  val)

(defmethod decompress-entity :string-or-string-vec [[_ val]]
  (let [parts (clojure.string/split val #",")]
    (if (= 1 (count parts))
      val
      parts)))


(defn- parse-vec [val]
  (let [parts (clojure.string/split val #",")
        res (into [] (map js/parseFloat parts))]
    res))

(defn validate-vec-size [v size]
  (if-not (= size (count v))
    (throw
      (js/Error.
        (str "Validation failed for vector entity, "
             size
             " items were expected but only "
             (count v) " found")))
    v))

(defmethod decompress-entity :vector3 [[_ val]]
  (-> val
      parse-vec
      (validate-vec-size 3)))

(defmethod decompress-entity :vector2 [[_ val]]
  (-> val
      parse-vec
      (validate-vec-size 2)))

(defmethod decompress-entity :boolean [[_ val]]
  (= val "1"))

(defmethod decompress-entity :keyword [[_ val]]
  (name val))

(defmethod decompress-entity :default [[t val]]
  (throw (js/Error. (str "Don't know how to compress URL entity of type: " t " with value: " val))))

(defn- compress [obj]
  (let [pairs (for [[ks token t] path-mappers
                    :let [val (get-in obj ks)]
                    :when val]
                [token val t])]
    (join "&"
          (map (fn [[token val t]]
                 (str token "=" (-> (compress-entity [t val])
                                    js/encodeURIComponent)))
               pairs))))

(defn- decompress [s]
  (let [pairs (split s #"&")
        tokens (map #(split % #"=") pairs)
        reverse-map (into {}
                          (for [[k v t] path-mappers]
                            [v [k t]]))]
    (reduce
     (fn [acc [k v]]
       (if-let [[p t] (get reverse-map k)]
         (let [val (decompress-entity [t (js/decodeURIComponent v)])]
           (assoc-in acc p val))))
     {}
     tokens)))

(defn- decompress-map [m]
  (let [reverse-map (into {}
                          (for [[k v t] path-mappers]
                            [(keyword v) [k t]]))]
    (reduce
     (fn [acc [k v]]
       (if-let [[p t] (get reverse-map k)]
         (let [val (decompress-entity [t (js/decodeURIComponent v)])]
           (assoc-in acc p val))))
     {}
     m)))

(defn- prep-state [obj]
  (let [url (compress obj)
        url-qs (str "/?" url)
        to-store (reduce (fn [m path]
                           (assoc-in m path (get-in obj path)))
                         {}
                         (all-url-keys))
        store-state (pr-str to-store)]
    [(js-obj "state" store-state) url-qs]))

(defn push-state
  ([obj]
    (push-state obj "speck.ly"))

  ([obj title]
    (when-let [history (.. js/window -history)]
      (let [[to-store url-qs] (prep-state obj)]
        (.pushState history to-store title url-qs)))))


(defn replace-state
  ([obj]
    (replace-state obj "speck.ly"))
  ([obj title]
    (when-let [history (.. js/window -history)]
      (let [[to-store url-qs] (prep-state obj)]
        (.replaceState history to-store title url-qs)))))

(defn current-state-from-query-map [m]
  (decompress-map m))

(defn current-state-from-query-string
  ([]
    (current-state-from-query-string (.. js/window -location -search)))
  ([qs]
   (try
     (when-not (clojure.string/blank? qs)
       (decompress (subs qs 1)))
     (catch js/Error e
       (js/console.log "URL was malformed:" e)
       {}))))

(defn listen
  "bind to state pop event if its available, call f with the popped state"
  [f]
  (.addEventListener js/window "popstate"
                     (fn [e]
                       (f (-> (.. e -state -state)
                              read-string)))))

(defn resource-url [server resource]
  (let [origin (aget js/location "origin")]
    (str origin
         "/?s=" server "&r=" resource)))

