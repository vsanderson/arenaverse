(ns arenaverse.views.battles
  (:require [arenaverse.views.common :as common]
            [arenaverse.views.admin.fighters :as fighters]
            [arenaverse.data-mappers.fighter :as fighter]
            [arenaverse.data-mappers.arena :as arena]
            [arenaverse.data-mappers.battle :as battle]
            [noir.session :as session])
  
  (:use noir.core
        hiccup.core
        hiccup.form-helpers
        arenaverse.views.routes)
  
  (:import [org.bson.types ObjectId]))


;;------
;; Functions for setting up data for displaying a battle page
;;------

;; TODO how to test this?
(defn random-teamless-fighters [fighters]
  (let [randomer #(rand-int (count fighters))
        left (randomer)
        right (first (filter #(not= % left) (repeatedly randomer)))]
    [(nth fighters left) (nth fighters right)]))

(defn random-team-fighters [fighters]
  (let [teams (vals (group-by :team fighters))
        left (rand-int (count (first teams)))
        right (rand-int (count (second teams)))]
    [(nth (first teams) left) (nth (second teams) right)]))

(defn random-fighters [arena-id]
  (let [fighters (fighter/all {:arena-id arena-id})]
    (if (> (count fighters) 1)
      (if (some #(not (empty? (:team %))) fighters)
        (random-team-fighters fighters)
        (random-teamless-fighters fighters))
      [])))


(defn battle->session-battle [battle]
  (let [shortname (:shortname (:arena battle))]
    (conj (map :_id (:fighters battle)) shortname)))

;; Takes a seq of battles, which are a map of arena and two fighters
;; for that arena
(defn register-battles! [b]
  (let [battles-processed (apply hash-map
                                 (reduce (fn [list battle]
                                           (let [bs (battle->session-battle battle)]
                                             (conj list (first bs) bs)))
                                         []
                                         b))]
    (session/put! :battles battles-processed)
    (session/put! :main-battle (battle->session-battle (first b)))))

(defn arena->battle [arena]
  {:arena arena :fighters (random-fighters (arena/idstr arena))})

(defn battle-filter [battles]
  (filter #(not (empty? (:fighters %))) battles))

(defn battles-without-main-arena-specified []
  (shuffle (battle-filter (map arena->battle (arena/all)))))

(defn battles-with-main-arena-specified [main-arena-id]
  (let [arena (arena/one-by-id main-arena-id)
        arenas (remove #(= arena %) (arena/all))]
    (reverse (conj (shuffle (battle-filter (map arena->battle arenas))) (arena->battle arena)))))

(defn battles [main-arena-id]
  (let [b (if main-arena-id
            (battles-with-main-arena-specified main-arena-id)
            (battles-without-main-arena-specified))]
    (register-battles! b)
    b))

;;----------
;; Partials for battle page
;;----------

(defpartial card [arena record img-version]
  [:div.name (:name record)]
  [:div.pic
   [:a {:href (url-for-r :battles/winner {:_id (:_id record) :arena-shortname (:shortname arena)})}
    (fighters/fighter-img img-version record)]])

(defpartial card-without-battle [record img-version]
  [:div.name (:name record)]
  [:div.pic
   (fighters/fighter-img img-version record)])

(defpartial win-ratio [fighter wins]
  (let [bouts (reduce + (vals wins))
        _id (keyword (:_id fighter))
        ratio (* 100 (if (= 0 bouts) 1 (/ (_id wins) bouts)))]
    [:div.ratio-card
     (card-without-battle  fighter "card")
     [:div.win-ratio (str (format "%.1f" (double ratio)) "%")]]))

(defpartial _minor-battle [battle]
  (let [[left-f right-f] (:fighters battle)
        arena (:arena battle)]
    (when (and left-f right-f)
      [:div.battle
       [:h2 (:fight-text (:arena battle))]
       [:div.fighter.a (card arena left-f "card")]
       [:div.fighter.b (card arena right-f "card")]])))

(defpartial _minor-battles [minor-battles]
  (loop [html [:div#minor-battles]
         remaining-battles minor-battles]
    (if (empty? remaining-battles)
      html
      (let [battle (first remaining-battles)]
        (if-let [minor-battle-html (_minor-battle battle)]
          (recur (conj html minor-battle-html)
                 (rest remaining-battles))
          (recur html (rest remaining-battles)))))))

(defpartial previous-battle-results [prev-fighter-id-a prev-fighter-id-b]
  (when (and prev-fighter-id-a prev-fighter-id-b)
    (let [previous-fighters (map #(fighter/one-by-id %) [prev-fighter-id-a prev-fighter-id-b])
          wins (battle/record-for-pair (map :_id previous-fighters))]
      [:div.win-ratios
       [:h2 "Win Ratio"]
       (win-ratio (first previous-fighters) wins)
       (win-ratio (second previous-fighters) wins)])))

(defpartial main-area [arena left-f right-f prev-fighter-id-a prev-fighter-id-b]
  [:div#battle
   [:div.fighter.a
    (card arena left-f "battle")]
   [:div.fighter.b
    (card arena right-f "battle")]
   (previous-battle-results prev-fighter-id-a prev-fighter-id-b)])

(defpartial battle [{:keys [prev-fighter-id-a
                            prev-fighter-id-b
                            prev-main-arena-shortname
                            main-arena-shortname]}]
  (let [[main-battle & minor-battles] (battles false)]
    (when main-battle
      (let [[left-f right-f] (:fighters main-battle)
            arena (:arena main-battle)]
        (common/layout 
         [:h1 (:fight-text (:arena main-battle))]
         (main-area arena left-f right-f prev-fighter-id-a prev-fighter-id-b)
         (_minor-battles minor-battles))))))

(defn session-battle->battle-map [session-battle]
  (let [[prev-main-arena-shortname prev-fighter-id-a prev-fighter-id-b] (session/get :main-battle)]
    {:prev-main-arena-shortname prev-main-arena-shortname
     :prev-fighter-id-a prev-fighter-id-a
     :prev-fighter-id-b prev-fighter-id-b}))

(defpage-r listing []
  (battle (session-battle->battle-map (session/get :main-battle))))

(defpage-r winner {:keys [arena-shortname _id]}
  (println ((session/get :battles) arena-shortname))
  (let [previous-battle ((session/get :battles) arena-shortname)
        selected-battle-fighter-ids (rest previous-battle)]
    (battle/record-winner! selected-battle-fighter-ids _id)
    (let [battle-map (session-battle->battle-map (or previous-battle (session/get :main-battle)))]
      (battle (assoc battle-map :main-arena-shortname (:prev-main-arena-shortname battle-map))))))

(defpage-r arena {:keys [shortname]}
  (let [arena (arena/one {:shortname shortname})]
    (battles-listing [nil nil (:_id arena)])))