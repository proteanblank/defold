(ns editor.editor-extensions
  (:require [dynamo.graph :as g]
            [editor.graph-util :as gu])
  (:import [org.luaj.vm2.lib.jse JsePlatform]))

(g/defnode EditorExtensions
  (input extensions g/Any :array :substitute gu/array-subst-remove-errors)
  (output globals g/Any :cached (g/fnk []
                                  (JsePlatform/debugGlobals)))
  (output extensions g/Any :cached (g/fnk [extensions]
                                     (transduce
                                       cat
                                       (completing
                                         #(update %1 (key %2) (fnil conj []) (val %2)))
                                       {}
                                       extensions))))

#_(let [es-id (first (filter #(g/node-instance? editor.editor-extensions/EditorExtensions %) (g/node-ids (g/graph 1))))]
    (for [[label _] (dev/node-labels es-id)]
      [label (g/node-value es-id label)]))
