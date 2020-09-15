(ns frontend.components.hiccup
  (:refer-clojure :exclude [range])
  (:require [frontend.config :as config]
            [cljs.core.match :refer-macros [match]]
            [clojure.string :as string]
            [frontend.util :as util]
            [rum.core :as rum]
            [frontend.state :as state]
            [frontend.db :as db]
            [dommy.core :as d]
            [datascript.core :as dc]
            [goog.dom :as gdom]
            [frontend.handler.expand :as expand]
            [frontend.components.editor :as editor]
            [frontend.components.svg :as svg]
            [frontend.components.draw :as draw]
            [frontend.components.block :as block-comp]
            [frontend.ui :as ui]
            [frontend.components.widgets :as widgets]
            [frontend.handler :as handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.handler.image :as image-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.dnd :as dnd]
            [goog.object :as gobj]
            [medley.core :as medley]
            [cljs.reader :as reader]
            [frontend.util :as util :refer-macros [profile]]
            [frontend.mixins :as mixins]
            [frontend.db-mixins :as db-mixins]
            [frontend.extensions.latex :as latex]
            [frontend.extensions.code :as code]
            [frontend.extensions.sci :as sci]
            ["/frontend/utils" :as utils]
            [frontend.format.block :as block]
            [clojure.walk :as walk]
            [cljs-bean.core :as bean]
            [frontend.handler.image :as image-handler]
            [frontend.format.mldoc :as mldoc]
            [frontend.text :as text]
            [frontend.utf8 :as utf8]))

;; local state
(defonce *block-children
  (atom {}))

(defonce *dragging?
  (atom false))
(defonce *dragging-block
  (atom nil))
(defonce *move-to-top?
  (atom false))

(defonce container-ids (atom {}))
(defonce container-idx (atom 0))

;; TODO:
;; add `key`

(defn- remove-nils
  [col]
  (remove nil? col))

(defn anchor-link
  [s]
  (.anchorLink js/window.Mldoc s))

(defn vec-cat
  [& args]
  (->> (apply concat args)
       remove-nils
       vec))

(defn ->elem
  ([elem items]
   (->elem elem nil items))
  ([elem attrs items]
   (let [elem (keyword elem)]
     (if attrs
       (vec
        (cons elem
              (cons attrs
                    (seq items))))
       (vec
        (cons elem
              (seq items)))))))

(defn- join-lines
  [l]
  (string/trim (apply str l)))

(defn- string-of-url
  [url]
  (match url
    (:or ["File" s] ["Search" s])
    s
    ["Complex" m]
    (let [{:keys [link protocol]} m]
      (if (= protocol "file")
        link
        (str protocol ":" link)))))

(defn- get-file-absolute-path
  [config path]
  (let [path (string/replace path "file:" "")
        block-id (:block/uuid config)
        current-file (and block-id
                          (:file/path (:page/file (:block/page (db/entity [:block/uuid block-id])))))]
    (when current-file
      (let [parts (string/split current-file #"/")
            parts-2 (string/split path #"/")
            current-dir (string/join "/" (drop-last 1 parts))]
        (cond
          (util/starts-with? path "/")
          path

          (and (not (util/starts-with? path ".."))
               (not (util/starts-with? path ".")))
          (str current-dir "/" path)

          :else
          (let [parts (loop [acc []
                             parts (reverse parts)
                             col (reverse parts-2)]
                        (if (empty? col)
                          acc
                          (let [[part parts] (case (first col)
                                               ".."
                                               [(first parts) (rest parts)]
                                               "."
                                               ["" parts]
                                               [(first col) (rest parts)])]
                            (recur (conj acc part)
                                   parts
                                   (rest col)))))
                parts (remove #(string/blank? %) parts)]
            (string/join "/" (reverse parts))))))))

;; TODO: safe encoding asciis
;; TODO: image link to another link
(defn image-link [config url href label]
  (let [href (if (util/starts-with? href "http")
               href
               (get-file-absolute-path config href))]
    [:img.rounded-sm.shadow-xl.mb-2.mt-2
     {:class "object-contain object-center"
      :loading "lazy"
      :style {:max-height "24rem"}
      ;; :on-error (fn [])
      :src href
      :title (second (first label))}]))

(defn repetition-to-string
  [[[kind] [duration] n]]
  (let [kind (case kind
               "Dotted" "."
               "Plus" "+"
               "DoublePlus" "++")]
    (str kind n (string/lower-case (str (first duration))))))

(defn timestamp-to-string
  [{:keys [active date time repetition wday active]}]
  (let [{:keys [year month day]} date
        {:keys [hour min]} time
        [open close] (if active ["<" ">"] ["[" "]"])
        repetition (if repetition
                     (str " " (repetition-to-string repetition))
                     "")
        hour (if hour (util/zero-pad hour))
        min  (if min (util/zero-pad min))
        time (cond
               (and hour min)
               (util/format " %s:%s" hour min)
               hour
               (util/format " %s" hour)
               :else
               "")]
    (util/format "%s%s-%s-%s %s%s%s%s"
                 open
                 (str year)
                 (util/zero-pad month)
                 (util/zero-pad day)
                 wday
                 time
                 repetition
                 close)))

(defn timestamp [{:keys [active date time repetition wday] :as t} kind]
  (let [prefix (case kind
                 "Scheduled"
                 [:i {:class "fa fa-calendar"
                      :style {:margin-right 3.5}}]
                 "Deadline"
                 [:i {:class "fa fa-calendar-times-o"
                      :style {:margin-right 3.5}}]
                 "Date"
                 nil
                 "Closed"
                 nil
                 "Started"
                 [:i {:class "fa fa-clock-o"
                      :style {:margin-right 3.5}}]
                 "Start"
                 "From: "
                 "Stop"
                 "To: "
                 nil)]
    (let [class (if (= kind "Closed")
                  "line-through")]
      [:span.timestamp (cond-> {:active (str active)}
                         class
                         (assoc :class class))
       prefix
       (timestamp-to-string t)])))

(defn range [{:keys [start stop]} stopped?]
  [:div {:class "timestamp-range"
         :stopped stopped?}
   (timestamp start "Start")
   (timestamp stop "Stop")])

(declare map-inline)
(declare block-cp)

(declare page-reference)

(defn page-cp
  [{:keys [html-export? label children] :as config} page]
  (when-let [page-name (:page/name page)]
    (let [original-page-name (get page :page/original-name page-name)
          page (string/lower-case page-name)
          href (if html-export?
                 (util/encode-str page)
                 (str "/page/" (util/encode-str page)))]
      [:a.page-ref
       {:href href
        :on-click (fn [e]
                    (util/stop e)
                    (when (gobj/get e "shiftKey")
                      (when-let [page-entity (db/entity [:page/name page])]
                        (state/sidebar-add-block!
                         (state/get-current-repo)
                         (:db/id page-entity)
                         :page
                         {:page page-entity}))))}
       (if (seq children)
         (for [child children]
           (if (= (first child) "Label")
             [:span (last child)]
             (let [{:keys [content children]} (last child)
                   page-name (subs content 2 (- (count content) 2))]
               (page-reference html-export? page-name (assoc config :children children) nil))))
         (if (and label
                  (string? label)
                  (not (string/blank? label))) ; alias
           label
           original-page-name))])))

(defn page-reference
  [html-export? s config label]
  [:span.page-reference
   (when (and (not html-export?)
              (not (= (:id config) "contents"))
              (not (= (:id config) "Contents")))
     [:span.text-gray-500 "[["])
   (if (string/ends-with? s ".excalidraw")
     [:a.page-ref
      {:href (str "/draw?file=" (string/replace s (str config/default-draw-directory "/") ""))
       :on-click (fn [e] (util/stop e))}
      [:span
       (svg/excalidraw-logo)
       (string/capitalize (draw/get-file-title s))]]
     (page-cp (assoc config
                     :label (mldoc/plain->text label)) {:page/name s}))
   (when (and (not html-export?)
              (not (= (:id config) "contents"))
              (not (= (:id config) "Contents")))
     [:span.text-gray-500 "]]"])])

(defn- latex-environment-content
  [name option content]
  (if (= (string/lower-case name) "equation")
    content
    (util/format "\\begin%s\n%s\\end{%s}"
                 (str "{" name "}" option)
                 content
                 name)))

(declare blocks-container)

(rum/defc block-embed < rum/reactive
  [config id]
  (let [blocks (db/get-block-and-children (state/get-current-repo) id)]
    [:div.embed-block.bg-base-2 {:style {:z-index 2}}
     [:code "Embed block:"]
     [:div.px-2
      (blocks-container blocks (assoc config :embed? true))]]))

(rum/defc page-embed < rum/reactive
  [config page-name]
  (let [page-name (string/lower-case page-name)
        page-original-name (:page/original-name (db/entity [:page/name page-name]))
        blocks (db/get-page-blocks (state/get-current-repo) page-name)]
    [:div.embed-page.py-2.my-2.px-3.bg-base-2
     [:p
      [:code "Embed page:"]
      [:a.ml-2 {:href (str "/page/" (util/encode-str page-name))}
       (or page-original-name page-name)]]
     (blocks-container blocks (assoc config :embed? true))]))

(defn- get-label-text
  [label]
  (and (= 1 (count label))
       (let [label (first label)]
         (string? (last label))
         (last label))))

(defn- get-page
  [label]
  (when-let [label-text (get-label-text label)]
    (db/entity [:page/name (string/lower-case label-text)])))

(defn- macro->text
  [name arguments]
  (if (and (seq arguments)
           (not= arguments ["null"]))
    (util/format "{{{%s %s}}}" name (string/join ", " arguments))
    (util/format "{{{%s}}}" name)))

(defn inline
  [{:keys [html-export?] :as config} item]
  (match item
    ["Plain" s]
    s
    ["Spaces" s]
    s
    ["Superscript" l]
    (->elem :sup (map-inline config l))
    ["Subscript" l]
    (->elem :sub (map-inline config l))
    ["Tag" s]
    (if (and s (util/tag-valid? s))
      [:a.tag.mr-1 {:href (str "/page/" s)
                    :on-click (fn [e]
                                (util/stop e)
                                (let [repo (state/get-current-repo)
                                      page (db/pull repo '[*] [:page/name (string/lower-case (util/url-decode s))])]
                                  (when (gobj/get e "shiftKey")
                                    (state/sidebar-add-block!
                                     repo
                                     (:db/id page)
                                     :page
                                     {:page page}))))}
       (str "#" s)]
      [:span.warning.mr-1 {:title "Invalid tag, tags only accept alphanumeric characters, \"-\", \"_\", \"@\" and \"%\"."}
       (str "#" s)])
    ["Emphasis" [[kind] data] ]
    (let [elem (case kind
                 "Bold" :b
                 "Italic" :i
                 "Underline" :ins
                 "Strike_through" :del
                 "Highlight" :mark)]
      (->elem elem (map-inline config data)))
    ["Entity" e]
    [:span {:dangerouslySetInnerHTML
            {:__html (:html e)}}]

    ["Latex_Fragment" ["Displayed" s]]
    (if html-export?
      (latex/html-export s false true)
      (latex/latex (str (dc/squuid)) s false true))

    ["Latex_Fragment" ["Inline" s]]
    (if html-export?
      (latex/html-export s false true)
      (latex/latex (str (dc/squuid)) s false false))

    ["Target" s]
    [:a {:id s} s]

    ["Radio_Target" s]
    [:a {:id s} s]

    ["Email" address]
    (let [{:keys [local_part domain]} address
          address (str local_part "@" domain)]
      [:a {:href (str "mainto:" address)}
       address])

    ["Block_reference" id]
    ;; FIXME: alert when self block reference
    (when-not (string/blank? id)
      (let [block (and (util/uuid-string? id)
                       (db/pull-block (uuid id)))]
        (if block
          [:span
           [:span.text-gray-500 "(("]
           [:a {:href (str "/page/" id)
                :on-click (fn [e]
                            (util/stop e)
                            (when (gobj/get e "shiftKey")
                              (state/sidebar-add-block!
                               (state/get-current-repo)
                               (:db/id block)
                               :block-ref
                               {:block block})))}
            (->elem
             :span.block-ref
             (map-inline config (:block/title block)))]
           [:span.text-gray-500 "))"]]
          [:span.warning.mr-1 {:title "Block ref invalid"}
           (util/format "((%s))" id)])))

    ["Nested_link" link]
    (let [{:keys [content children]} link]
      [:span.page-reference
       (when (and (not html-export?)
                  (not (= (:id config) "contents")))
         [:span.text-gray-500 "[["])
       (let [page-name (subs content 2 (- (count content) 2))]
         (page-cp (assoc config :children children) {:page/name page-name}))
       (when (and (not html-export?)
                  (not (= (:id config) "contents")))
         [:span.text-gray-500 "]]"])])

    ["Link" link]
    (let [{:keys [url label title]} link
          img-formats (set (map name (config/img-formats)))]
      (match url
        ["Search" s]
        (cond
          ;; image
          (some (fn [fmt] (re-find (re-pattern (str "\\." fmt)) s)) img-formats)
          (image-link config url s label)

          (= \# (first s))
          (->elem :a {:href (str "#" (anchor-link (subs s 1)))} (map-inline config label))
          ;; FIXME: same headline, see more https://orgmode.org/manual/Internal-Links.html
          (= \* (first s))
          (->elem :a {:href (str "#" (anchor-link (subs s 1)))} (map-inline config label))
          (re-find #"^https://" s)
          (->elem :a {:href s}
                  (map-inline config label))

          :else
          (page-reference html-export? s config label))

        :else
        (let [href (string-of-url url)
              protocol (or
                        (and (= "Complex" (first url))
                             (:protocol (second url)))
                        (and (= "File" (first url))
                             "file"))]
          (cond
            (= protocol "file")
            (if (some (fn [fmt] (re-find (re-pattern (str "\\." fmt)) href)) img-formats)
              (image-link config url href label)
              (let [label-text (get-label-text label)
                    page (if (string/blank? label-text)
                           {:page/name (db/get-file-page (string/replace href "file:" ""))}
                           (get-page label))]
                (if (and page
                         (when-let [ext (util/get-file-ext href)]
                           (config/mldoc-support? ext)))
                  [:span.page-reference
                   [:span.text-gray-500 "[["]
                   (page-cp config page)
                   [:span.text-gray-500 "]]"]]

                  (->elem
                   :a
                   (cond->
                       {:href href}
                     title
                     (assoc :title title))
                   (map-inline config label)))))

            ;; image
            (some (fn [fmt] (re-find (re-pattern (str "\\." fmt)) href)) img-formats)
            (image-link config url href label)

            :else
            (->elem
             :a
             (cond->
                 {:href href
                  :target "_blank"}
               title
               (assoc :title title))
             (map-inline config label))))))

    ["Verbatim" s]
    [:code s]

    ["Code" s]
    [:code s]

    ["Inline_Source_Block" x]
    [:code (:code x)]

    ["Export_Snippet" "html" s]
    [:span {:dangerouslySetInnerHTML
            {:__html s}}]

    ;; String to hiccup
    ["Inline_Hiccup" s]
    (reader/read-string s)

    ["Break_Line"]
    [:br]
    ["Hard_Break_Line"]
    [:br]

    ["Timestamp" ["Scheduled" t]]
    (timestamp t "Scheduled")
    ["Timestamp" ["Deadline" t]]
    (timestamp t "Deadline")
    ["Timestamp" ["Date" t]]
    (timestamp t "Date")
    ["Timestamp" ["Closed" t]]
    (timestamp t "Closed")
    ["Timestamp" ["Range" t]]
    (range t false)
    ["Timestamp" ["Clock" ["Stopped" t]]]
    (range t true)
    ["Timestamp" ["Clock" ["Started" t]]]
    (timestamp t "Started")

    ["Cookie" ["Percent" n]]
    [:span {:class "cookie-percent"}
     (util/format "[d%%]" n)]

    ["Cookie" ["Absolute" current total]]
    [:span {:class "cookie-absolute"}
     (util/format "[%d/%d]" current total)]

    ["Footnote_Reference" options]
    (let [{:keys [id name]} options
          encode-name (util/url-encode name)]
      [:sup.fn
       [:a {:id (str "fnr." encode-name)
            :class "footref"
            :href (str "#fn." encode-name)}
        name]])

    ["Macro" options]
    (let [{:keys [name arguments]} options]
      (cond
        (= name "embed")
        (let [a (first arguments)]
          (cond
            (and (util/starts-with? a "[[")
                 (string/ends-with? a "]]"))
            (let [page-name (-> (string/replace a "[[" "")
                                (string/replace "]]" "")
                                string/trim)]
              (when-not (string/blank? page-name)
                (page-embed config page-name)))

            (and (util/starts-with? a "((")
                 (string/ends-with? a "))"))
            (when-let [s (-> (string/replace a "((" "")
                             (string/replace "))" "")
                             string/trim)]
              (when-let [id (and s
                                 (let [s (string/trim s)]
                                   (and (util/uuid-string? s)
                                        (uuid s))))]
                (block-embed config id)))

            :else                       ;TODO: maybe collections?
            nil))

        :else
        (if-let [block-uuid (:block/uuid config)]
          (let [macro-content (or
                               (-> (db/entity [:block/uuid block-uuid])
                                   (:block/page)
                                   (:db/id)
                                   (db/entity)
                                   :page/directives
                                   :macros
                                   (get name))
                               (get-in (state/get-config) [:macros name])
                               (get-in (state/get-config) [:macros (keyword name)]))]
            [:span
             (if (and (seq arguments) macro-content)
               (block/macro-subs macro-content arguments)
               (or
                macro-content
                [:span.warning {:title (str "Unsupported macro name: " name)}
                 (macro->text name arguments)]))])

          [:span
           (macro->text name arguments)])))

    :else
    ""))

(declare blocks-cp)

(rum/defc block-child
  [block]
  block)

(defonce *control-show? (atom {}))

(rum/defcs block-control < rum/reactive
  {:will-mount (fn [state]
                 (let [block (nth (:rum/args state) 1)
                       collapsed? (:block/collapsed? block)]
                   (state/set-collapsed-state! (:block/uuid block)
                                               collapsed?))
                 state)}
  [state config block uuid block-id level start-level body children dummy?]
  (let [has-child? (and
                    (not (:pre-block? block))
                    (or (seq children)
                        (seq body)))
        collapsed? (state/sub [:ui/collapsed-blocks uuid])
        collapsed? (and has-child? collapsed?)
        control-show (util/react (rum/cursor *control-show? block-id))
        dark? (= "dark" (state/sub :ui/theme))
        heading? (= (get (:block/properties block) "heading") "true")]
    [:div.hd-control.mr-2.flex.flex-row.items-center
     {:style {:height 24
              :margin-top (if (and heading? (<= level 6))
                            (case level
                              1
                              32
                              2
                              22
                              18)
                            0)
              :float "left"}}

     [:a.block-control
      {:id (str "control-" uuid)
       :style {:width 14
               :height 16
               :margin-right 2}
       :on-click (fn [e]
                   (util/stop e)
                   (if collapsed?
                     (expand/expand! block)
                     (expand/collapse! block))

                   (state/set-collapsed-state! uuid (not collapsed?)))}
      (cond
        (and control-show collapsed?)
        (svg/caret-right)

        (and control-show has-child?)
        (svg/caret-down)

        :else
        [:span ""])]
     [:a (if (not dummy?)
           {:href (str "/page/" uuid)
            :on-click (fn [e]
                        (util/stop e)
                        (when (gobj/get e "shiftKey")
                          (state/sidebar-add-block!
                           (state/get-current-repo)
                           (:db/id block)
                           :block
                           block)))})
      [:span.bullet-container.cursor
       {:id (str "dot-" uuid)
        :draggable true
        :on-drag-start (fn [event]
                         (editor-handler/highlight-block! uuid)
                         (.setData (gobj/get event "dataTransfer")
                                   "block-uuid"
                                   uuid)
                         (.setData (gobj/get event "dataTransfer")
                                   "block-dom-id"
                                   block-id)
                         (reset! *dragging? true)
                         (reset! *dragging-block block))
        :blockid (str uuid)
        :class (str (when collapsed? "bullet-closed")
                    " "
                    (when (and (:document/mode? config)
                               (not collapsed?))
                      "hide-inner-bullet"))}
       [:span.bullet {:blockid (str uuid)
                      :class (if heading? "bullet-heading" "")}]]]]))

(defn- build-id
  [config ref? sidebar? embed?]
  (let [k (pr-str config)
        n (or
           (get @container-ids k)
           (let [n' (swap! container-idx inc)]
             (swap! container-ids assoc k n')
             n'))]
    (str n "-")))

(rum/defc dnd-separator
  [block margin-left bottom top? nested?]
  (let [id (str (:block/uuid block)
                (cond nested?
                      "-nested"
                      top?
                      "-top"
                      :else
                      nil))]
    [:div.dnd-separator
     {:id id
      :style (merge
              {:position "absolute"
               :left margin-left
               :width (- 700 margin-left)}
              (if top?
                {:top 0}
                {:bottom bottom}))}]))

(declare block-container)
(defn block-checkbox
  [block class]
  (let [marker (:block/marker block)
        [class checked?] (cond
                           (nil? marker)
                           nil
                           (contains? #{"NOW" "LATER" "DOING" "IN-PROGRESS" "TODO" "WAIT" "WAITING"} marker)
                           [class false]
                           (= "DONE" marker)
                           [(str class " checked") true])]
    (when class
      (ui/checkbox {:class class
                    :style {:margin-top -1
                            :margin-right 6}
                    :checked checked?
                    :on-change (fn [_e]
                                 ;; FIXME: Log timestamp
                                 (if checked?
                                   (editor-handler/uncheck block)
                                   (editor-handler/check block)))}))))

(defn list-checkbox
  [checked?]
  (ui/checkbox {:style {:margin-right 6
                        :margin-top -1}
                :checked checked?}))

(defn marker-switch
  [{:block/keys [pre-block? marker] :as block}]
  (when (contains? #{"NOW" "LATER" "TODO" "DOING"} marker)
    (let [set-marker-fn (fn [marker]
                          (fn [e]
                            (util/stop e)
                            (editor-handler/set-marker block marker)))]
      (case marker
        "NOW"
        [:a.marker-switch
         {:title "Change from NOW to LATER"
          :on-click (set-marker-fn "LATER")}
         [:span "N"]]
        "LATER"
        [:a.marker-switch
         {:title "Change from LATER to NOW"
          :on-click (set-marker-fn "NOW")}
         "L"]

        "TODO"
        [:a.marker-switch
         {:title "Change from TODO to DOING"
          :on-click (set-marker-fn "DOING")}
         "T"]
        "DOING"
        [:a.marker-switch
         {:title "Change from DOING to TODO"
          :on-click (set-marker-fn "TODO")}
         "D"]
        nil))))

(defn marker-cp
  [{:block/keys [pre-block? marker] :as block}]
  (when-not pre-block?
    (if (contains? #{"IN-PROGRESS" "WAIT" "WAITING"} marker)
      [:span {:class (str "task-status " (string/lower-case marker))
              :style {:margin-right 3.5}}
       (string/upper-case marker)])))

(defn priority-cp
  [{:block/keys [pre-block? priority] :as block}]

  (when (and (not pre-block?) priority)
    (ui/tooltip
     [:ul
      (for [p (remove #(= priority %) ["A" "B" "C"])]
        [:a.mr-2.text-base.tooltip-priority {:priority p
                                             :on-click (fn [] (editor-handler/set-priority block p))}])]
     [:a.opacity-50.hover:opacity-100
      {:class "priority"
       :href (str "/page/" priority)
       :style {:margin-right 3.5}}
      (util/format "[#%s]" (str priority))])))

(defn block-tags-cp
  [{:block/keys [pre-block? tags] :as block}]
  (when (and (not pre-block?)
             (seq tags))
    (->elem
     :span
     {:class "block-tags"}
     (mapv (fn [{:keys [db/id tag/name]}]
             (if (util/tag-valid? name)
               [:a.tag.mx-1 {:key (str "tag-" id)
                             :href (str "/page/" name)}
                (str "#" name)]
               [:span.warning.mx-1 {:title "Invalid tag, tags only accept alphanumeric characters, \"-\", \"_\", \"@\" and \"%\"."}
                (str "#" name)]))
           tags))))

(defn build-block-part
  [{:keys [slide?] :as config} {:block/keys [uuid title tags marker level priority anchor meta format content pre-block? dummy? block-refs-count page properties]
                                :as t}]
  (let [config (assoc config :block t)
        slide? (boolean (:slide? config))
        html-export? (:html-export? config)
        checkbox (when (and (not pre-block?)
                            (not html-export?))
                   (block-checkbox t (str "mr-1 cursor")))
        marker-switch (when (and (not pre-block?)
                                 (not html-export?))
                        (marker-switch t))
        marker-cp (marker-cp t)
        priority (priority-cp t)
        tags (block-tags-cp t)
        contents? (= (:id config) "contents")
        heading? (= (get properties "heading") "true")
        bg-color (get properties "background-color")]
    (when level
      (let [element (if (and (<= level 6) heading?)
                      (keyword (str "h" level))
                      :div)]
        (->elem
         element
         (merge
          {:id anchor}
          (when (and marker
                     (not (string/blank? marker))
                     (not= "nil" marker))
            {:class (string/lower-case marker)})
          (when bg-color
            {:style {:background-color bg-color
                     :padding-left 6
                     :padding-right 6
                     :color "#FFFFFF"}}))
         (remove-nils
          (concat
           [(when-not slide? checkbox)
            (when-not slide? marker-switch)
            marker-cp
            priority]
           (cond
             dummy?
             [[:span.opacity-50 "Click here to start writing"]]

             ;; empty item
             (and contents? (or
                             (empty? title)
                             (= title [["Plain" "[[]]"]])))
             [[:span.opacity-50 "Click here to add a page, e.g. [[favorite-page]]"]]

             :else
             (map-inline config title))
           [tags])))))))

(defn dnd-same-block?
  [uuid]
  (= (:block/uuid @*dragging-block) uuid))

(defn show-dnd-separator
  [element-id]
  (when-let [element (gdom/getElement element-id)]
    (when (d/has-class? element "dnd-separator")
      (d/remove-class! element "dnd-separator")
      (d/add-class! element "dnd-separator-cur"))))

(defn hide-dnd-separator
  [element-id]
  (when-let [element (gdom/getElement element-id)]
    (when (d/has-class? element "dnd-separator-cur")
      (d/remove-class! element "dnd-separator-cur")
      (d/add-class! element "dnd-separator"))))

(defn- get-data-transfer-attr
  [event attr]
  (.getData (gobj/get event "dataTransfer") attr))

(defn- pre-block-cp
  [config content format]
  (let [ast (mldoc/->edn content (mldoc/default-config format))
        ast (map first ast)]
    [:div.pre-block.bg-base-2.p-2
     (blocks-cp config ast)]))

(rum/defc block-content < rum/reactive
  [config {:block/keys [uuid title level body meta content dummy? page format repo children pre-block? collapsed? idx block-refs-count] :as block} edit-input-id block-id slide?]
  (let [dragging? (rum/react *dragging?)
        attrs {:blockid (str uuid)
               ;; FIXME: Click to copy a selection instead of click first and then copy
               ;; It seems that `util/caret-range` can't get the correct range
               :on-click (fn [e]
                           (let [target (gobj/get e "target")]
                             (when-not (or (util/link? target)
                                           (util/input? target)
                                           (util/details-or-summary? target)
                                           (and (util/sup? target)
                                                (d/has-class? target "fn")))
                               (editor-handler/clear-selection! nil)
                               (editor-handler/unhighlight-block!)
                               (let [cursor-range (util/caret-range (gdom/getElement block-id))]
                                 (state/set-editing!
                                  edit-input-id
                                  (->> (text/remove-level-spaces content format)
                                       (text/remove-properties! block))
                                  block
                                  cursor-range))
                               (util/stop e))))
               :on-drag-over (fn [event]
                               (util/stop event)
                               (when-not (dnd-same-block? uuid)
                                 (show-dnd-separator (str uuid "-nested"))))
               :on-drag-leave (fn [event]
                                (hide-dnd-separator (str uuid))
                                (hide-dnd-separator (str uuid "-nested"))
                                (hide-dnd-separator (str uuid "-top")))
               :on-drop (fn [event]
                          (util/stop event)
                          (when-not (dnd-same-block? uuid)
                            (let [from-dom-id (get-data-transfer-attr event "block-dom-id")]
                              (dnd/move-block @*dragging-block
                                              block
                                              from-dom-id
                                              false
                                              true)))
                          (reset! *dragging? false)
                          (reset! *dragging-block nil)
                          (editor-handler/unhighlight-block!))}]
    [:div.flex.flex-row
     [:div.flex-1.flex-col.relative.block-content
      (cond-> {:id (str "block-content-" uuid)
               :style {:cursor "text"
                       :min-height 24}}
        (not slide?)
        (merge attrs))

      (if pre-block?
        (pre-block-cp config (string/trim content) format)
        (build-block-part config block))

      (when (and dragging? (not slide?))
        (dnd-separator block 0 -4 false true))

      (when (and (not pre-block?) (seq body))
        [:div.block-body {:style {:display (if collapsed? "none" "")}}
         ;; TODO: consistent id instead of the idx (since it could be changed later)
         (let [body (block/trim-break-lines! (:block/body block))]
           (for [[idx child] (medley/indexed body)]
             (when-let [block (block-cp config child)]
               (rum/with-key (block-child block)
                 (str uuid "-" idx)))))])]
     (when (and block-refs-count (> block-refs-count 0))
       [:div
        [:a.block.py-0.px-2.rounded.bg-base-2.opacity-50.hover:opacity-100
         {:title "Open block references"
          :style {:margin-top -1}
          :on-click (fn []
                      (state/sidebar-add-block!
                       (state/get-current-repo)
                       (:db/id block)
                       :block-ref
                       {:block block}))}
         block-refs-count]])]))

(rum/defc block-content-or-editor < rum/reactive
  [config {:block/keys [uuid title level body meta content dummy? page format repo children pre-block? collapsed? idx] :as block} edit-input-id block-id slide?]
  (let [edit? (state/sub [:editor/editing? edit-input-id])]
    (if edit?
      [:div.editor-wrapper {:id (str "editor-" edit-input-id)}
       (editor/box {:block block
                    :block-id uuid
                    :block-parent-id block-id
                    :format format
                    :dummy? dummy?
                    :on-hide (fn [value event]
                               (when (= event :esc)
                                 (editor-handler/highlight-block! uuid)))}
                   edit-input-id
                   config)]
      (block-content config block edit-input-id block-id slide?))))

(rum/defc dnd-separator-wrapper < rum/reactive
  [block slide? top?]
  (let [dragging? (rum/react *dragging?)]
    (cond
      (and dragging? (not slide?))
      (dnd-separator block 30 0 top? false)

      :else
      nil)))

(rum/defc block-container < rum/static
  {:did-mount (fn [state]
                (let [block (nth (:rum/args state) 1)
                      collapsed? (:block/collapsed? block)]
                  (when collapsed?
                    (expand/collapse! block))
                  state))}
  [config {:block/keys [uuid title level body meta content dummy? page format repo children collapsed? pre-block? idx properties] :as block}]
  (let [ref? (boolean (:ref? config))
        ref-child? (:ref-child? config)
        sidebar? (boolean (:sidebar? config))
        slide? (boolean (:slide? config))
        doc-mode? (:document/mode? config)
        embed? (:embed? config)
        unique-dom-id (build-id (dissoc config :block/uuid) ref? sidebar? embed?)
        edit-input-id (str "edit-block-" unique-dom-id uuid)
        block-id (str "ls-block-" unique-dom-id uuid)
        has-child? (boolean
                    (and
                     (not pre-block?)
                     (or (seq children)
                         (seq body))))
        start-level (or (:start-level config) 1)
        attrs {:on-drag-over (fn [event]
                               (util/stop event)
                               (when-not (dnd-same-block? uuid)
                                 (if (zero? idx)
                                   (let [element-top (gobj/get (utils/getOffsetRect (gdom/getElement block-id)) "top")
                                         cursor-top (gobj/get event "clientY")]
                                     (if (<= (js/Math.abs (- cursor-top element-top)) 16)
                                       ;; top
                                       (do
                                         (hide-dnd-separator (str uuid))
                                         (show-dnd-separator (str uuid "-top"))
                                         (reset! *move-to-top? true))
                                       (do
                                         (hide-dnd-separator (str uuid "-top"))
                                         (show-dnd-separator (str uuid)))))
                                   (show-dnd-separator (str uuid)))))
               :on-drag-leave (fn [event]
                                (hide-dnd-separator (str uuid))
                                (hide-dnd-separator (str uuid "-nested"))
                                (hide-dnd-separator (str uuid "-top"))
                                (reset! *move-to-top? false))
               :on-drop (fn [event]
                          (when-not (dnd-same-block? uuid)
                            (let [from-dom-id (get-data-transfer-attr event "block-dom-id")]
                              (dnd/move-block @*dragging-block
                                              block
                                              from-dom-id
                                              @*move-to-top?
                                              false)))
                          (reset! *dragging? false)
                          (reset! *dragging-block nil)
                          (editor-handler/unhighlight-block!))
               :on-mouse-move (fn [e]
                                (when (= (gobj/get e "buttons") 1)
                                  (state/into-selection-mode!)))

               :on-mouse-down (fn [e]
                                (when (and
                                       (not (state/get-selection-start-block))
                                       (= (gobj/get e "buttons") 1))
                                  (when block-id (state/set-selection-start-block! block-id))))
               :on-mouse-over (fn [e]
                                (util/stop e)
                                (when has-child?
                                  (swap! *control-show? assoc block-id true))
                                (when-let [parent (gdom/getElement block-id)]
                                  (let [node (.querySelector parent ".bullet-container")
                                        closed? (d/has-class? node "bullet-closed")]
                                    (if closed?
                                      (state/collapse-block! uuid)
                                      (state/expand-block! uuid))
                                    (when doc-mode?
                                      (d/remove-class! node "hide-inner-bullet"))))
                                (when (and
                                       (state/in-selection-mode?)
                                       (= (gobj/get e "buttons") 1)
                                       (false? @*dragging?))
                                  (util/stop e)
                                  (editor-handler/highlight-selection-area! block-id)))
               :on-mouse-out (fn [e]
                               (util/stop e)
                               (when has-child?
                                 (swap! *control-show?
                                        assoc block-id false))
                               (when doc-mode?
                                 (when-let [parent (gdom/getElement block-id)]
                                   (when-let [node (.querySelector parent ".bullet-container")]
                                     (d/add-class! node "hide-inner-bullet")))))}
        pre-block-only-title? (and pre-block?
                                   (db/pre-block-with-only-title? repo uuid))]
    (when-not pre-block-only-title?
      [:div.ls-block.flex.flex-col.pt-1
       (cond->
           {:id block-id
            :style {:position "relative"}
            :class (str uuid
                        (when dummy? " dummy")
                        (when (and collapsed? has-child?) " collapsed")
                        (when pre-block? " pre-block"))
            :blockid (str uuid)
            :repo repo
            :level level
            :haschild (str has-child?)}
         (not slide?)
         (merge attrs))

       (if (and ref? (not ref-child?))
         (when-let [block-parents (block-comp/block-parents repo uuid format false)]
           [:div.my-2.opacity-50.ml-7 block-parents]))

       (dnd-separator-wrapper block slide? (zero? idx))

       [:div.flex-1.flex-row
        (when (not slide?)
          (block-control config block uuid block-id level start-level body children dummy?))

        (block-content-or-editor config block edit-input-id block-id slide?)]

       (when (seq children)
         [:div.block-children {:style {:margin-left (if doc-mode? 12 22)
                                       :display (if collapsed? "none" "")}}
          (for [child children]
            (when (map? child)
              (let [child (dissoc child :block/meta)]
                (rum/with-key (block-container config child)
                  (:block/uuid child)))))])

       (when (and ref? (not ref-child?))
         (let [children (db/get-block-children repo uuid)]
           (when (seq children)
             [:div.ref-children.ml-12
              (blocks-container children (assoc config
                                                :ref-child? true
                                                :ref? true))])))

       (dnd-separator-wrapper block slide? false)])))

(defn divide-lists
  [[f & l]]
  (loop [l l
         ordered? (:ordered f)
         result [[f]]]
    (if (seq l)
      (let [cur (first l)
            cur-ordered? (:ordered cur)]
        (if (= ordered? cur-ordered?)
          (recur
           (rest l)
           cur-ordered?
           (update result (dec (count result)) conj cur))
          (recur
           (rest l)
           cur-ordered?
           (conj result [cur]))))
      result)))

(defn list-element
  [l]
  (match l
    [l1 & tl]
    (let [{:keys [ordered name]} l1]
      (cond
        (seq name)
        :dl
        ordered
        :ol
        :else
        :ul))

    :else
    :ul))

(defn list-item
  [config {:keys [name content checkbox items number] :as l}]
  (let [content (when-not (empty? content)
                  (match content
                    [["Paragraph" i] & rest]
                    (vec-cat
                     (map-inline config i)
                     (blocks-cp config rest))
                    :else
                    (blocks-cp config content)))
        checked? (some? checkbox)
        items (if (seq items)
                (->elem
                 (list-element items)
                 (for [item items]
                   (list-item config item))))]
    (cond
      (seq name)
      [:dl {:checked checked?}
       [:dt (map-inline config name)]
       (->elem :dd
               (vec-cat content [items]))]

      :else
      (if (nil? checkbox)
        (->elem
         :li
         {:checked checked?}
         (vec-cat
          [(->elem
            :p
            content)]
          [items]))
        (->elem
         :li
         {:checked checked?}
         (vec-cat
          [(->elem
            :p
            (list-checkbox checkbox)
            content)]
          [items]))))))

(defn table
  [config {:keys [header groups col_groups]}]
  (let [tr (fn [elm cols]
             (->elem
              :tr
              (mapv (fn [col]
                      (->elem
                       elm
                       {:scope "col"
                        :class "org-left"}
                       (map-inline config col)))
                    cols)))
        tb-col-groups (try
                        (mapv (fn [number]
                                (let [col-elem [:col {:class "org-left"}]]
                                  (->elem
                                   :colgroup
                                   (repeat number col-elem))))
                              col_groups)
                        (catch js/Error e
                          []))
        head (if header
               [:thead (tr :th header)])
        groups (mapv (fn [group]
                       (->elem
                        :tbody
                        (mapv #(tr :td %) group)))
                     groups)]
    [:div.table-wrapper {:style {:max-width (min 700
                                                 (gobj/get js/window "innerWidth"))}}
     (->elem
      :table
      {:class "table-auto"
       :border 2
       :cell-spacing 0
       :cell-padding 6
       :rules "groups"
       :frame "hsides"}
      (vec-cat
       tb-col-groups
       (cons head groups)))]))

(defn map-inline
  [config col]
  (map #(inline config %) col))

(declare ->hiccup)

(defn built-in-custom-query?
  [title]
  (contains? #{"🔨 NOW" "📅 NEXT"}
             title))

(rum/defcs custom-query < rum/reactive
  {:will-mount (fn [state]
                 (let [[config query] (:rum/args state)]
                   (let [query-atom (db/custom-query query)]
                     (assoc state :query-atom query-atom))))
   :did-mount (fn [state]
                (when-let [query (last (:rum/args state))]
                  (state/add-custom-query-component! query (:rum/react-component state)))
                state)
   :will-unmount (fn [state]
                   (when-let [query (last (:rum/args state))]
                     (state/remove-custom-query-component! query)
                     (db/remove-custom-query! (state/get-current-repo) query))
                   state)}
  [state config {:keys [title query inputs view collapsed?] :as q}]
  (let [query-atom (:query-atom state)]
    (let [current-block-uuid (:block/uuid (:block config))
          ;; exclude the current one, otherwise it'll loop forever
          remove-blocks (if current-block-uuid [current-block-uuid] nil)
          query-result (and query-atom (rum/react query-atom))
          result (if query-result
                   (db/custom-query-result-transform query-result remove-blocks q))
          view-f (sci/eval-string (pr-str view))
          only-blocks? (:block/uuid (first result))
          blocks-grouped-by-page? (and (seq result)
                                       (:page/name (ffirst result))
                                       (:block/uuid (first (second (first result))))
                                       true)
          built-in? (built-in-custom-query? title)]
      [:div.custom-query.mt-2 (get config :attr {})
       (when-not (and built-in? (empty? result))
         (ui/foldable
          [:div.opacity-70
           title]
          (cond
            (and (seq result) view-f)
            (let [result (sci/call-fn view-f result)]
              (util/hiccup-keywordize result))

            (and (seq result)
                 (or only-blocks? blocks-grouped-by-page?))
            (->hiccup result (assoc config
                                    :custom-query? true
                                    :group-by-page? blocks-grouped-by-page?)
                      {:style {:margin-top "0.25rem"
                               :margin-left "0.25rem"}})

            (seq result)                     ;TODO: table
            [:pre
             (for [record result]
               (if (map? record)
                 (str (util/pp-str record) "\n")
                 record))]

            :else
            [:div.text-sm.mt-2.ml-2.font-medium.opacity-50 "Empty"])
          collapsed?))])))

(defn admonition
  [config type options result]
  (when-let [icon (case (string/lower-case (name type))
                    "note" svg/note
                    "tip" svg/tip
                    "important" svg/important
                    "caution" svg/caution
                    "warning" svg/warning
                    nil)]
    [:div.flex.flex-row.admonitionblock.align-items {:class type}
     [:div.pr-4.admonition-icon.flex.flex-col.justify-center
      {:title (string/upper-case type)} (icon)]
     [:div.ml-4.text-lg
      (blocks-cp config result)]]))

(defn block-cp
  [{:keys [html-export?] :as config} item]
  (try
    (match item
      ["Directives" m]
      [:div.directives
       (for [[k v] m]
         (when (and (not (and (= k :macros) (empty? v))) ; empty macros
                    (not (= k :title)))
           [:div.directive
            [:span.font-medium.mr-1 (string/upper-case (str (name k) ": "))]
            (if (coll? v)
              (for [item v]
                (if (= k :tags)
                  (let [tag (-> item
                                (string/replace "[" "")
                                (string/replace "]" "")
                                (string/replace "#" ""))]
                    [:a.tag.mr-1 {:href (str "/page/" tag)}
                     tag])
                  [:span item]))
              [:span v])]))]

      ["Paragraph" l]
      ;; TODO: speedup
      (if (re-find #"\"Export_Snippet\" \"embed\"" (str l))
        (->elem :div (map-inline config l))
        (->elem :p (map-inline config l)))

      ["Horizontal_Rule"]
      (when-not (:slide? config)
        [:hr])
      ["Heading" h]
      (block-container config h)
      ["List" l]
      (let [lists (divide-lists l)]
        (if (= 1 (count lists))
          (let [l (first lists)]
            (->elem
             (list-element l)
             (map #(list-item config %) l)))
          [:div.list-group
           (for [l lists]
             (->elem
              (list-element l)
              (map #(list-item config %) l)))]))
      ["Table" t]
      (table config t)
      ["Math" s]
      (if html-export?
        (latex/html-export s true true)
        (latex/latex (str (dc/squuid)) s true true))
      ["Example" l]
      [:pre
       (join-lines l)]
      ["Src" options]
      (let [{:keys [language options lines]} options
            attr (if language
                   {:data-lang language})
            code (join-lines lines)]
        (cond
          html-export?
          (code/html-export attr code)

          (and (= language "clojure") (contains? (set options) ":results"))
          [:div
           (code/highlight (str (dc/squuid)) attr code)
           (sci/eval-result code)]

          :else
          (code/highlight (str (dc/squuid)) attr code)))
      ["Quote" l]
      (->elem
       :blockquote
       (blocks-cp config l))
      ["Raw_Html" content]
      [:div.raw_html {:dangerouslySetInnerHTML
                      {:__html content}}]
      ["Export" "html" options content]
      [:div.export_html {:dangerouslySetInnerHTML
                         {:__html content}}]
      ;; TODO: hiccup element check
      ["Hiccup" content]
      (reader/read-string content)

      ["Export" "latex" options content]
      (if html-export?
        (latex/html-export content true false)
        (latex/latex (str (dc/squuid)) content true false))

      ["Custom" "query" _options result content]
      (let [query (reader/read-string content)]
        (custom-query config query))

      ["Custom" "note" options result content]
      (admonition config "note" options result)

      ["Custom" "tip" options result content]
      (admonition config "tip" options result)

      ["Custom" "important" options result content]
      (admonition config "important" options result)

      ["Custom" "caution" options result content]
      (admonition config "caution" options result)

      ["Custom" "warning" options result content]
      (admonition config "warning" options result)

      ["Custom" name options l content]
      (->elem
       :div
       {:class name}
       (blocks-cp config l))

      ["Latex_Fragment" l]
      [:p.latex-fragment
       (inline config ["Latex_Fragment" l])]

      ["Latex_Environment" name option content]
      (let [content (latex-environment-content name option content)]
        (if html-export?
          (latex/html-export content true true)
          (latex/latex (str (dc/squuid)) content true true)))
      ["Footnote_Definition" name definition]
      (let [id (util/url-encode name)]
        [:div.footdef
         [:div.footpara
          (conj
           (block-cp config ["Paragraph" definition])
           [:a.ml-1 {:id (str "fn." id)
                     :style {:font-size 14}
                     :class "footnum"
                     :href (str "#fnr." id)}
            [:sup.fn (str name "↩︎")]])]])

      :else
      "")
    (catch js/Error e
      (println "Convert to html failed, error: " e)
      "")))

(defn blocks-cp
  [config col]
  (map #(block-cp config %) col))

(defn build-blocks
  [blocks config]
  (let [blocks (db/blocks->vec-tree blocks)]
    (when (seq blocks)
      (let [first-id (:block/uuid (first blocks))]
        (for [item blocks]
          (let [item (-> (if (:block/dummy? item)
                           item
                           (dissoc item :block/meta)))
                item (if (= first-id (:block/uuid item))
                       (assoc item :block/idx 0)
                       item)
                config (assoc config :block/uuid (:block/uuid item))]
            (rum/with-key
              (block-container config item)
              (:block/uuid item))))))))

(defn build-slide-sections
  ([blocks config]
   (build-slide-sections blocks config nil))
  ([blocks config build-block-fn]
   (when (seq blocks)
     (let [blocks (map #(dissoc % :block/children) blocks)
           first-block-level (:block/level (first blocks))
           sections (reduce
                     (fn [acc block]
                       (let [block (dissoc block :block/meta)
                             level (:block/level block)
                             block-cp (if build-block-fn
                                        (build-block-fn config block)
                                        (rum/with-key
                                          (block-container config block)
                                          (str "slide-" (:block/uuid block))))]
                         (if (= first-block-level level)
                           ;; new slide
                           (conj acc [[block block-cp]])
                           (update acc (dec (count acc))
                                   (fn [sections]
                                     (conj sections [block block-cp]))))))
                     []
                     blocks)]
       sections))))

(rum/defc blocks-container < rum/static
  [blocks config]
  (let [blocks (map #(dissoc % :block/children) blocks)
        sidebar? (:sidebar? config)
        ref? (:ref? config)]
    [:div.blocks-container.flex-1
     {:style {:margin-left (cond
                             sidebar?
                             0
                             :else
                             -18)}}
     (build-blocks blocks config)]))

;; headers to hiccup
(rum/defc ->hiccup < rum/reactive
  [blocks config option]
  (let [document-mode? (state/sub [:document/mode?])
        config (assoc config
                      :document/mode? document-mode?)]
    [:div.content
     (cond-> option
       document-mode?
       (assoc :class "doc-mode"))
     (if (:group-by-page? config)
       [:div.flex.flex-col
        (for [[page blocks] blocks]
          (let [page (db/entity (:db/id page))]
            [:div.my-2 (cond-> {:key (str "page-" (:db/id page))}
                         (:ref? config)
                         (assoc :class "bg-base-2 px-7 py-2 rounded"))
             (ui/foldable
              (page-cp config page)
              (blocks-container blocks config))]))]
       (blocks-container blocks config))]))

(comment
  ;; timestamps
  ;; [2020-02-10 Mon 13:22]
  ;; repetition
  (def r1 "<2005-10-01 Sat +1m>")
  ;; TODO: mldoc add supports
  (def r2 "<2005-10-01 Sat +1m -3d>")

  (def l
    "1. First item
hello world
2. Second item
nice
3. Third item")

  (def t
    "| Name  | Phone | Age |
|-------+-------+-----|
| Peter |  1234 |  17 |
| Anna  |  4321 |  25 |")
  )
