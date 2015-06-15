(ns plasio-ui.widgets
  (:require [reagent.core :as reagent :refer [atom]]
            [clojure.string :as string]))

(defn panel
  "A simple widget which shows a panel with title and children"
  [title & children]
  [:div.panel
   [:div.title title]
   (into [:div.contents] children)])

(defn panel-section
  "A section inside a panel"
  [& children]
  (into [:div.panel-section] children))

(defn desc
  "Styled description text"
  [& children]
  (into [:div.desc] children))

(defn slider
  "An abstracted jQuery slider control"
  [start min max f]
  (let [this (reagent/current-component)]
    (reagent/create-class
      {:component-did-mount
       (fn []
         (let [slider (js/jQuery (.getDOMNode this))
               single? (number? start)
               start (if single?
                       start
                       (apply array start))
               emit #(f (let [value (.val slider)]
                          (if single?
                            (js/parseFloat value)
                            (mapv js/parseFloat (string/split value #",")))))]
           (doto slider
             (.on "slide" emit)
             (.on "set" emit))
           (.noUiSlider slider
                        (js-obj
                          "start" start
                          "connect" (if single? "lower" true)
                          "range" (js-obj "min" min
                                          "max" max)))))

       :reagent-render
       (fn [start min max f]
         [:div.slider])})))

(defn icon [& parts]
  [:i {:class (str "fa " (string/join " "
                                      (map #(str "fa-" (name %)) parts)))}])


(defn toolbar
  "A toolbar control"
  [f & buttons]
  (let [st (atom "")]
    (fn [f & buttons]
      [:div.toolbar
       (into [:div.icons]
             (map (fn [[id i title state]]
                    (let [disabled? (and (keyword? state) (= state :disabled))]
                      [:a.button {:class (when (keyword? state) (name state))
                                  :href "javascript:"
                                  :on-mouse-over (when-not disabled? #(reset! st title))
                                  :on-mouse-leave (when-not disabled? #(reset! st ""))
                                  :on-click (when-not disabled?
                                              (fn [e]
                                                (.preventDefault e)
                                                (f id)))}
                       (icon i)]))
                  buttons))
       [:p.tip @st]])))
