(ns arenaverse.views.battles
  (:require [arenaverse.views.common :as common]
            [arenaverse.views.admin.fighters :as fighters]
            [arenaverse.data-mappers.fighter :as fighter]
            [arenaverse.models.arena :as arena]
            [arenaverse.models.battle :as battle]
            [noir.session :as session]
            [monger.collection :as mc])
  
  (:use noir.core
        hiccup.core
        hiccup.form-helpers
        arenaverse.views.routes)
  
  (:import [org.bson.types ObjectId]))

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

(defpartial card [record, img-version]
  [:div.name (:name record)]
  [:div.pic
   [:a {:href (url-for-r :battles/winner {:_id record})}
    (fighters/fighter-img img-version record)]])

(defn win-ratio [fighter wins]
  (let [bouts (reduce + (vals wins))
        _id (keyword (:_id fighter))
        ratio (* 100 (if (= 0 bouts) 1 (/ (_id wins) bouts)))]
    [:div.ratio-card
     (card fighter "card")
     [:div.win-ratio (str (format "%.1f" (double ratio)) "%")]]))


;; TODO using apply here is really ugly
(defpage-r listing []
  (let [arena (arena/one)]
    (when arena
      (apply common/layout 
             (let [previous-fighters (map #(fighter/one-by-id %) (session/get :_ids))
                   [left-f right-f] (random-fighters (arena/idstr arena))]
               (session/put! :_ids (map :_id [left-f right-f]))
               [[:h1 (:fight-text arena)]
                (when (and left-f right-f)
                  [:div#battle
                   [:div.fighter.a
                    (card left-f "battle")]
                   [:div.fighter.b
                    (card right-f "battle")]
                   (when (not (empty? previous-fighters))
                     (let [wins (battle/record-for-pair (map :_id previous-fighters))]
                       [:div.win-ratios
                        [:h2 "Win Ratio"]
                        (win-ratio (first previous-fighters) wins)
                        (win-ratio (second previous-fighters) wins)]))])])))))

(defpage-r winner {:keys [_id]}
  (battle/record-winner (session/get :_ids) _id)
  (battles-listing nil))