(ns shadow.cljs.ui.pages.loading
  (:require
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    ))

(defsc Page [this props]
  {:ident
   (fn []
     [::ui-model/page-loading 1])

   :query
   (fn []
     [])

   :initial-state
   (fn [p]
     {})}

  (s/main-contents
    (html/div "Loading ...")))

(def ui-page (fp/factory Page {}))

