(ns game.cards.icebreakers
  (:require [game.core :refer :all]
            [game.utils :refer :all]
            [game.macros :refer :all]
            [clojure.string :refer [split-lines split join lower-case includes? starts-with?]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [jinteki.utils :refer [str->int other-side is-tagged? has-subtype?]]
            [jinteki.cards :refer [all-cards]]))

(def breaker-auto-pump
  "Updates an icebreaker's abilities with a pseudo-ability to trigger the
  auto-pump routine in core, IF we are encountering a rezzed ice with a subtype
  we can break."
  {:effect
   (req (let [abs (filter #(not= (:dynamic %) :auto-pump) (:abilities card))
              pumpabi (some #(when (:pump %) %) abs)
              pumpcst (when pumpabi (second (drop-while #(and (not= % :credit)
                                                              (not= % "credit"))
                                                        (:cost pumpabi))))
              current-ice (when-not (get-in @state [:run :ending]) (get-card state current-ice))
              strdif (when (and (or (:current-strength current-ice)
                                    (:strength current-ice))
                                (or (:current-strength card)
                                    (:strength card)))
                       (max 0 (- (or (:current-strength current-ice)
                                     (:strength current-ice))
                                 (or (:current-strength card)
                                     (:strength card)))))
              pumpnum (when (and strdif
                                 (:pump pumpabi))
                        (int (Math/ceil (/ strdif (:pump pumpabi)))))]
          (update! state side
                   (assoc card :abilities
                          (if (and pumpcst
                                   pumpnum
                                   (rezzed? current-ice)
                                   (or (some #(has-subtype? current-ice %) (:breaks card))
                                       (= (first (:breaks card)) "All"))
                                   (pos? pumpnum))
                            (vec (cons {:dynamic :auto-pump
                                        :cost [:credit (* pumpcst pumpnum)]
                                        :label (str "Match strength of " (:title current-ice))}
                                       abs))
                            abs)))))})

;; Takes a vector of ice subtypes that can be broken (or ["All"] for
;; AI breakers) and a card definition, and returns a new card definition that
;; hooks up breaker-auto-pump to the necessary events.
;; IMPORTANT: Events on cdef take precedence, and should call
;; (:effect breaker-auto-pump) themselves.
(defn auto-icebreaker [breaks cdef]
  (assoc cdef :data (merge {:breaks breaks} (:data cdef))
              :events (merge {:run breaker-auto-pump
                              :pass-ice breaker-auto-pump
                              :run-ends breaker-auto-pump
                              :ice-strength-changed breaker-auto-pump
                              :ice-subtype-changed breaker-auto-pump
                              :breaker-strength-changed breaker-auto-pump
                              :approach-ice breaker-auto-pump}
                             (:events cdef))))

(defn- wrestling-breaker
  "Laamb and Engolo. Makes currently encountered ice gain chosen type until end of encounter."
  [cost ice-type]
  {:once :per-turn
   :cost [:credit cost]
   :label (str "Make currently encountered ice gain " ice-type)
   :msg (msg "make " (:title current-ice) " gain " ice-type)
   :req (req (and current-ice
                  (rezzed? current-ice)
                  (not (has-subtype? current-ice ice-type))))
   :effect (req (let [ice current-ice
                      stargets (:subtype-target ice)
                      stypes (:subtype ice)
                      remove-subtype {:effect
                                      (effect (update! (assoc ice
                                                              :subtype-target stargets
                                                              :subtype stypes))
                                              (unregister-events card)
                                              (register-events (:events (card-def card)) card))}]
                  (update! state side (assoc ice
                                             :subtype-target (combine-subtypes true stargets ice-type)
                                             :subtype (combine-subtypes true stypes ice-type)))
                  (update-ice-strength state side (get-card state ice))
                  (register-events state side {:pass-ice remove-subtype
                                               :run-ends remove-subtype} card)))})

(defn cloud-icebreaker [cdef]
  (assoc cdef :effect (req (let [link (get-in @state [:runner :link] 0)]
                             (when (>= link 2)
                               (free-mu state (:memoryunits card))))
                           (add-watch state (keyword (str "cloud" (:cid card)))
                                      (fn [k ref old new]
                                        (let [old-link (get-in old [:runner :link] 0)
                                              new-link (get-in new [:runner :link] 0)
                                              cloud-turned-on (and (< old-link 2)
                                                                   (>= new-link 2))
                                              cloud-turned-off (and (>= old-link 2)
                                                                    (< new-link 2))]
                                          (cond
                                            cloud-turned-on
                                            (free-mu state (:memoryunits card))

                                            cloud-turned-off
                                            (use-mu state (:memoryunits card)))))))
              :leave-play (req (remove-watch state (keyword (str "cloud" (:cid card))))
                               (let [link (get-in @state [:runner :link] 0)]
                                 (when (>= link 2)
                                   ;; To counteract the normal freeing of MU on program `:leave-play`
                                   (use-mu state (:memoryunits card)))))))

(defn- strength-pump
  "Creates a strength pump ability.
  Cost can be a credit amount or a list of costs e.g. [:credit 2]."
  ([cost strength] (strength-pump cost strength :encounter))
  ([cost strength duration]
   {:msg (str "add " strength " strength" (cond
                                            (= duration :all-run)
                                            " for the remainder of the run"
                                            (= duration :all-turn)
                                            " for the remainder of the turn"))
    :cost [:credit cost]
    :effect (effect (pump card strength duration))
    :pump strength}))

(defn- break-sub
  "Creates a break subroutine ability.
  If n = 0 then any number of subs are broken."
  ([cost n] (break-sub cost n nil))
  ([cost n subtype] (break-sub cost n subtype nil))
  ([cost n subtype effect]
   {:msg (str "break "
              (when (> n 1) "up to ")
              (if (pos? n) n "any number of")
              (when subtype (str " " subtype))
              (pluralize " subroutine" n))
    :cost [:credit cost]
    :effect effect}))

;;; Breaker sets
(defn- cerberus
  "Breaker from the dog set"
  [ice-type]
  (auto-icebreaker [ice-type]
                   {:data {:counter {:power 4}}
                    :abilities [{:counter-cost [:power 1]
                                 :msg (str "break up to 2 " (lower-case ice-type) " subroutines")}
                                (strength-pump 1 1)]}))

(defn- break-and-enter
  "Breakers from the Break and Entry set"
  [ice-type]
  (cloud-icebreaker {:abilities [{:label (str "[Trash]: Break up to 3 " (lower-case ice-type) "subroutines")
                                  :msg (str "break up to 3 " (lower-case ice-type) " subroutines")
                                  :effect (effect (trash card {:cause :ability-cost}))}]
                      :events (let [cloud {:silent (req true)
                                           :req (req (has-subtype? target "Icebreaker"))
                                           :effect (effect (update-breaker-strength card))}]
                                {:runner-install cloud :trash cloud :card-moved cloud})
                      :strength-bonus (req (count (filter #(has-subtype? % "Icebreaker")
                                                          (all-active-installed state :runner))))}))

(defn- global-sec-breaker
  "GlobalSec breakers for Sunny"
  [ice-type]
  (cloud-icebreaker (auto-icebreaker [ice-type] {:abilities [(break-sub 2 0 (lower-case ice-type))
                                                         (strength-pump 2 3)]})))

(defn- deva
  "Deva breakers"
  [card-name]
  (auto-icebreaker ["All"]
                   {:abilities [(break-sub 1 1 "ICE")
                                (strength-pump 1 1)
                                {:req (req (seq (filter #(has-subtype? % "Deva") (:hand runner))))
                                 :label "Swap with a deva program from your Grip"
                                 :cost [:credit 2]
                                 :prompt (str "Select a deva program in your Grip to swap with " card-name)
                                 :choices {:req #(and in-hand? (has-subtype? % "Deva"))}
                                 :msg (msg "swap in " (:title target) " from their Grip")
                                 :effect (req (if-let [hostcard (:host card)]
                                                (let [hosted (host state side (get-card state hostcard) target)]
                                                  (card-init state side hosted {:resolve-effect false
                                                                                :init-data true}))
                                                (let [devavec (get-in @state [:runner :rig :program])
                                                      devaindex (first (keep-indexed #(when (= (:cid %2) (:cid card)) %1) devavec))
                                                      newdeva (assoc target :zone (:zone card) :installed true)
                                                      newvec (apply conj (subvec devavec 0 devaindex) newdeva (subvec devavec devaindex))]
                                                  (lose state :runner :memory (:memoryunits card))
                                                  (swap! state assoc-in [:runner :rig :program] newvec)
                                                  (swap! state update-in [:runner :hand] (fn [coll] (remove-once #(= (:cid %) (:cid target)) coll)))
                                                  (card-init state side newdeva {:resolve-effect false
                                                                                 :init-data true})))
                                              (move state side card :hand))}]}))

(defn- conspiracy
  "Install-from-heap breakers"
  [title ice-type abilities]
  (let [install-prompt {:req (req (and (= (:zone card) [:discard])
                                       (rezzed? current-ice)
                                       (has-subtype? current-ice ice-type)
                                       (not (install-locked? state :runner))))
                        :async true
                        :effect (effect (continue-ability
                                          {:optional {:req (req (and (not-any? #(= title (:title %)) (all-active-installed state :runner))
                                                                     (not (get-in @state [:run :register :conspiracy (:cid current-ice)]))))
                                                      :player :runner
                                                      :prompt (str "Install " title "?")
                                                      :yes-ability {:effect (effect (unregister-events card)
                                                                                    (runner-install :runner card))}
                                                      ;; Add a register to note that the player was already asked about installing,
                                                      ;; to prevent multiple copies from prompting multiple times.
                                                      :no-ability {:effect (req (swap! state assoc-in [:run :register :conspiracy (:cid current-ice)] true))}}}
                                          card targets))}
        heap-event (req (when (= (:zone card) [:discard])
                          (unregister-events state side card)
                          (register-events state side
                                           {:rez install-prompt
                                            :approach-ice install-prompt
                                            :run install-prompt}
                                           (assoc card :zone [:discard]))))]
    {:move-zone heap-event
     :events {:rez nil
              :approach-ice nil
              :run nil}
     :abilities abilities}))

(defn- central-breaker
  "'Cannot be used on a remote server' breakers"
  [ice-type break pump]
  (let [central-req (req (or (not (:central-breaker card)) (#{:hq :rd :archives} (first (:server run)))))]
    (auto-icebreaker [ice-type]
                     {:abilities [(assoc break :req central-req)
                                  (assoc pump :req central-req)]
                      :effect (effect (update! (assoc card :central-breaker true)))})))

(defn- ancient-greek-breaker
  "Adept, Sage and Savant. Strength depends on available memory units."
  [card-name abilities]
  {:abilities abilities
   :effect (req (add-watch state (keyword (str card-name (:cid card)))
                           (fn [k ref old new]
                             (when (not= (available-mu (atom old))
                                         (available-mu (atom new)))
                               (update-breaker-strength ref side card))))
                (update-breaker-strength state side card))
   :leave-play (req (remove-watch state (keyword (str card-name (:cid card)))))
   :strength-bonus (req (available-mu state))})

(defn- khumalo-breaker
  "Spends virus counters from any card to pump/break, gains virus counters for successful runs."
  [ice-type]
  {:events {:successful-run {:silent (req true)
                             :effect (effect (system-msg (str "adds 1 virus counter to " (:title card)))
                                             (add-counter card :virus 1))}}
   :abilities [{:label (str  "Break " ice-type " subroutine(s)")
                :effect (req (wait-for (resolve-ability
                                         state side (pick-virus-counters-to-spend) card nil)
                                       (when-let* [msg (:msg async-result)
                                                   n (:number async-result)]
                                         (system-msg state :runner
                                                     (str "spends " msg
                                                          " to break "
                                                          (quantify n (str ice-type " subroutine")))))))}
               {:label "Match strength of currently encountered ice"
                :req (req (and current-ice
                               (> (ice-strength state side current-ice)
                                  (or (:current-strength card)
                                      (:strength card)))))
                :effect (req (wait-for (resolve-ability
                                         state side
                                         (pick-virus-counters-to-spend
                                           (- (ice-strength state side current-ice)
                                              (or (:current-strength card)
                                                  (:strength card))))
                                         card nil)
                                       (when-let* [msg (:msg async-result)
                                                   n (:number async-result)]
                                         (dotimes [_ n]
                                           (pump state side (get-card state card) 1))
                                         (system-msg state :runner
                                                     (str "spends " msg
                                                          " to add " n
                                                          " strength")))))}
               {:label "Add strength"
                :effect (req (wait-for (resolve-ability
                                         state side (pick-virus-counters-to-spend) card nil)
                                       (when-let* [msg (:msg async-result)
                                                   n (:number) async-result]
                                         (dotimes [_ n]
                                           (pump state side (get-card state card) 1))
                                         (system-msg state :runner
                                                     (str "spends " msg
                                                          " to add " n
                                                          " strength")))))}]})

;;; Icebreaker definitions
(def card-definitions
  {"Abagnale"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 1 1 "Code Gate")
                                 (strength-pump 2 2)
                                 {:label "Bypass Code Gate being encountered"
                                  :req (req (has-subtype? current-ice "Code Gate"))
                                  :msg (msg "trash it and bypass " (:title current-ice))
                                  :effect (effect (trash card {:cause :ability-cost}))}]})

   "Adept"
   (ancient-greek-breaker "adept" [{:cost [:credit 2]
                                    :req (req (or (has-subtype? current-ice "Barrier")
                                                  (has-subtype? current-ice "Sentry")))
                                    :msg "break 1 Sentry or Barrier subroutine"}])

   "Aghora"
   (deva "Aghora")

   "Alpha"
   (auto-icebreaker ["All"]
                    {:abilities [{:cost [:credit 1]
                                  :req (req (= (:position run) (count run-ices)))
                                  :msg "break 1 subroutine on the outermost ICE protecting this server"}
                                 (strength-pump 1 1)]})

   "Alias"
   (central-breaker "Sentry"
                    (break-sub 1 1 "Sentry")
                    (strength-pump 2 3))

   "Amina"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 2 3 "Code Gate")
                                 (strength-pump 2 3)
                                 {:label "Corp loses 1 [Credits]"
                                  :req (req (and (has-subtype? current-ice "Code Gate")
                                                 (rezzed? current-ice)))
                                  :msg (msg "make the Corp lose 1 [Credits]")
                                  :effect (effect (lose-credits :corp 1))}]})

   "Ankusa"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 2 1 "Barrier")
                                 (strength-pump 1 1)
                                 {:label "Add Barrier to HQ"
                                  :req (req (and (has-subtype? current-ice "Barrier")
                                                 (rezzed? current-ice)))
                                  :msg (msg "add " (:title current-ice) " to HQ after breaking all its subroutines")
                                  :effect (req (let [c current-ice]
                                                 (move state :corp c :hand nil)
                                                 (continue state side nil)))}]})

   "Atman"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :abilities [(break-sub 1 1)]
    :strength-bonus (req (get-counters card :power))
    :events {:counter-added {:req (req (= :cid target) (:cid card))
                             :effect (effect (update-breaker-strength card))}}}

   "Aumakua"
   {:implementation "Add counters manually for access outside of a run or cards that replace access like Ash"
    ; We would need a :once :per-access key to make this work for Gang Sign etc.
    :abilities [(break-sub 1 1)
                {:label "Add a virus counter"
                 :effect (effect (system-msg "manually adds a virus counter to Aumakua")
                                 (add-counter card :virus 1))}]
    :strength-bonus (req (get-virus-counters state card))
    :events {:run-ends {:req (req (and (not (or (get-in @state [:run :did-trash])
                                                (get-in @state [:run :did-steal])))
                                       (get-in @state [:run :did-access])))
                        :effect (effect (add-counter card :virus 1))}
             :expose {:effect (effect (add-counter card :virus 1))}
             :counter-added {:req (req (= :cid target) (:cid card))
                             :effect (effect (update-breaker-strength card))}}}

   "Aurora"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 2 1 "Barrier")
                                 (strength-pump 2 3)]})

   "Baba Yaga"
   (let [host-click {:cost [:click 1]
                     :label "Install a non-AI icebreaker on Baba Yaga"
                     :prompt "Choose a non-AI icebreaker in your Grip to install on Baba Yaga"
                     :choices {:req #(and (has-subtype? % "Icebreaker")
                                          (not (has-subtype? % "AI"))
                                          (in-hand? %))}
                     :effect (effect (runner-install target {:host-card card}))}
         host-free {:label "Host an installed non-AI icebreaker on Baba Yaga"
                    :prompt "Choose an installed non-AI icebreaker to host on Baba Yaga"
                    :choices {:req #(and (has-subtype? % "Icebreaker")
                                         (not (has-subtype? % "AI"))
                                         (installed? %))}
                    :effect (req (when (host state side card target)
                                   (gain :memory (:memoryunits target))))}
         gain-abis (req (let [new-abis (mapcat (fn [c] (map-indexed #(assoc %2 :dynamic :copy, :source (:title c)
                                                                               :index %1, :label (make-label %2))
                                                                    (filter #(not= :manual-state (:ability-type %))
                                                                            (:abilities (card-def c)))))
                                               (:hosted card))]
                          (update! state :runner (assoc card :abilities (concat new-abis [host-click host-free])))))]
   {:abilities [host-click host-free]
    :hosted-gained gain-abis
    :hosted-lost gain-abis})

   "Battering Ram"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 2 2 "Barrier")
                                 (strength-pump 1 1 :all-run)]})

   "Berserker"
   {:abilities [(break-sub 2 2 "Barrier")]
    :implementation "Number of subroutines on encountered ICE has to be entered by runner when Corp chooses 'No More Action'"
    :events {:encounter-ice {:req (req (and (= (:cid target) (:cid current-ice))
                                            (has-subtype? target "Barrier")
                                            (rezzed? target)))
                             :async true
                             :effect (effect (continue-ability :runner
                                               {:prompt "How many subroutines are on the encountered Barrier?"
                                                :choices {:number (req 10)}
                                                :async true
                                                :effect (effect (system-msg (str "pumps Berserker by " target " on encounter with the current ICE"))
                                                                (pump card target))} card nil))}}}

   "BlacKat"
   {:implementation "Stealth credit restriction not enforced"
    :abilities [(break-sub 1 1 "Barrier")
                {:cost [:credit 1]
                 :msg "break up to 3 Barrier subroutines (using a stealth [Credits])"}
                (strength-pump 2 1)
                {:cost [:credit 2]
                 :msg "add 2 strength (using at least 1 stealth [Credits])"
                 :effect (effect (pump card 2)) :pump 2}]}

   "Black Orchestra"
   (conspiracy "Black Orchestra" "Code Gate"
               [{:cost [:credit 3]
                 :effect (effect (pump card 2)) :pump 2
                 :msg "add 2 strength and break up to 2 subroutines"}])

   "Blackstone"
   {:abilities [(break-sub 1 1 "Barrier")
                {:cost [:credit 3]
                 :msg "add 4 strength (using at least 1 stealth [Credits])"
                 :effect (effect (pump card 4 :all-run)) :pump 4}]}

   "Brahman"
   (auto-icebreaker ["All"]
                    {:implementation "Adding non-virus program to top of Stack is manual"
                     :abilities [(break-sub 1 2 "ICE")
                                 (strength-pump 2 1)]})

   "Breach"
   (central-breaker "Barrier"
                    (break-sub 2 3 "Barrier")
                    (strength-pump 2 4))

   "Cerberus \"Cuj.0\" H3"
   (cerberus "Sentry")

   "Cerberus \"Rex\" H2"
   (cerberus "Code Gate")

   "Cerberus \"Lady\" H1"
   (cerberus "Barrier")

   "Chameleon"
   {:prompt "Choose one subtype"
    :choices ["Barrier" "Code Gate" "Sentry"]
    :msg (msg "choose " target)
    :effect (effect (update! (assoc card :subtype-target target)))
    :events {:runner-turn-ends {:msg "add itself to Grip" :effect (effect (move card :hand))}}
    :abilities [{:cost [:credit 1] :msg (msg "break 1 " (:subtype-target card) " subroutine")}]}

   "Corroder"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 1 1 "Barrier")
                                 (strength-pump 1 1)]})

   "Cradle"
   {:abilities [(break-sub 2 0 "Code Gate")]
    :events {:card-moved {:silent (req true)
                          :req (req (and (= "Runner" (:side target))
                                         (= [:hand] (or (:zone target)
                                                        (:previous-zone target)))))
                          :effect (effect (update-breaker-strength card))}
             :runner-draw {:silent (req true)
                           :req (req (when-let [drawn (-> @state :runner :register :most-recent-drawn first)]
                                       (= [:hand] (or (:zone drawn)
                                                      (:previous-zone drawn)))))
                           :effect (effect (update-breaker-strength card))} }
    :strength-bonus (req (-> @state :runner :hand count -))}

   "Creeper"
   (cloud-icebreaker
     (auto-icebreaker ["Sentry"]
                      {:abilities [(break-sub 2 1 "Sentry")
                                   (strength-pump 1 1)]}))

   "Crowbar"
   (break-and-enter "Code Gate")

   "Crypsis"
   (auto-icebreaker ["All"]
                    {:abilities [(break-sub 1 1 "ICE" (effect (update! (assoc card :crypsis-broke true))))
                                 (strength-pump 1 1)
                                 {:cost [:click 1]
                                  :msg "place 1 virus counter"
                                  :effect (effect (add-counter card :virus 1))}]
                     :events (let [encounter-ends-effect
                                   {:req (req (:crypsis-broke card))
                                    :effect (req ((:effect breaker-auto-pump) state side eid card targets)
                                                 (if (pos? (get-counters card :virus))
                                                   (add-counter state side card :virus -1)
                                                   (trash state side card {:cause :self-trash}))
                                                 (update! state side (dissoc (get-card state card) :crypsis-broke)))}]
                               {:pass-ice encounter-ends-effect
                                :run-ends encounter-ends-effect})
                     :move-zone (req (when (= [:discard] (:zone card))
                                       (update! state side (dissoc card :crypsis-broke))))})

   "Cyber-Cypher"
   (auto-icebreaker ["Code Gate"]
                    {:prompt "Choose a server where this copy of Cyber-Cypher can be used:"
                     :msg (msg "target " target)
                     :choices (req servers)
                     :effect (effect (update! (assoc card :server-target target)))
                     :leave-play (effect (update! (dissoc card :server-target)))
                     :abilities [(break-sub 1 1 "Code Gate")
                                 (strength-pump 1 1)]})

   "Dagger"
   (auto-icebreaker ["Sentry"]
                    {:implementation "Stealth credit restriction not enforced"
                     :abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 1 5)]})

   "Dai V"
   (auto-icebreaker ["All"]
                    {:implementation "Stealth credit restriction not enforced"
                     :abilities [{:cost [:credit 2]
                                  :msg "break all ICE subroutines (using stealth [Credits])"}
                                 (strength-pump 1 1)]})

   "Darwin"
   {:flags {:runner-phase-12 (req true)}
    :events {:purge {:effect (effect (update-breaker-strength card))}}
    :abilities [(break-sub 2 1 "ICE")
                {:label "Place 1 virus counter (start of turn)"
                 :once :per-turn
                 :cost [:credit 1]
                 :msg "place 1 virus counter"
                 :req (req (:runner-phase-12 @state))
                 :effect (effect (add-counter card :virus 1)
                                 (update-breaker-strength card))}]
    :strength-bonus (req (or (get-virus-counters state card) 0))}

   "Demara"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 2 2 "Barrier")
                                 (strength-pump 2 3)
                                 {:label "Bypass Barrier being encountered"
                                  :req (req (has-subtype? current-ice "Barrier"))
                                  :msg (msg "trash it and bypass " (:title current-ice))
                                  :effect (effect (trash card {:cause :ability-cost}))}]})

   "Deus X"
   {:interactions {:prevent [{:type #{:net}
                              :req (req true)}]}
    :abilities [{:msg "break any number of AP subroutines"
                 :effect (effect (trash card {:cause :ability-cost}))}
                {:msg "prevent any amount of net damage"
                 :effect (effect (trash card {:cause :ability-cost})
                                 (damage-prevent :net Integer/MAX_VALUE))}]}

   "Eater"
   (auto-icebreaker ["All"]
                    {:abilities [{:cost [:credit 1]
                                  :msg "break ICE subroutine and access 0 cards this run"
                                  :effect (effect (max-access 0))}
                                 (strength-pump 1 1)]})

   "Endless Hunger"
   {:abilities [{:label "Trash 1 installed card to break 1 \"End the run.\" subroutine"
                 :prompt "Select a card to trash for Endless Hunger"
                 :choices {:req #(and (= (:side %) "Runner") (:installed %))}
                 :msg (msg "trash " (:title target)
                           " and break 1 \"[Subroutine] End the run.\" subroutine")
                 :effect (effect (trash target {:unpreventable true}))}]}
   "Engolo"
   (auto-icebreaker
     ["Code Gate"]
     {:abilities [(break-sub 1 1 "Code Gate")
                  (strength-pump 2 4)
                  (wrestling-breaker 2 "Code Gate")]})

   "Faerie"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 0 1 "Sentry" (effect (update! (assoc-in card [:special :faerie-used] true))))
                                 (strength-pump 1 1)]
                     :events {:pass-ice {:req (req (get-in card [:special :faerie-used]))
                                         :effect (effect (trash card))}}})

   "Faust"
   {:abilities [{:label "Trash 1 card from Grip to break 1 subroutine"
                 :prompt "Select a card from your grip to trash for Faust"
                 :choices {:req in-hand?}
                 :msg (msg "trash " (:title target) " and break 1 subroutine")
                 :effect (effect (trash target {:unpreventable true}))}
                {:label "Trash 1 card from Grip to add 2 strength"
                 :prompt "Select a card from your grip to trash for Faust"
                 :choices {:req in-hand?}
                 :msg (msg "trash " (:title target) " and add 2 strength")
                 :effect (effect (trash target {:unpreventable true})
                                 (pump card 2))}]}

   "Fawkes"
   {:implementation "Stealth credit restriction not enforced"
    :abilities [(break-sub 1 1 "Sentry")
                {:label (str "X [Credits]: +X strength for the remainder of the run (using at least 1 stealth [Credits])")
                 :choices :credit
                 :prompt "How many credits?"
                 :effect (effect (pump card target :all-run))
                 :msg (msg "increase strength by " target " for the remainder of the run")}]}

   "Femme Fatale"
   (auto-icebreaker ["Sentry"]
                    {:prompt "Select a piece of ICE to target for bypassing"
                     :choices {:req ice?}
                     :leave-play (req (remove-icon state side card))
                     :effect (req (let [ice target]
                                    (add-icon state side card ice "F" "blue")
                                    (system-msg state side
                                                (str "selects " (card-str state ice)
                                                     " for Femme Fatale's bypass ability"))))
                     :abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 2 1)]})

   "Flashbang"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(strength-pump 1 1)
                                 {:label "Derez a Sentry being encountered"
                                  :cost [:credit 6]
                                  :req (req (and (rezzed? current-ice) (has-subtype? current-ice "Sentry")))
                                  :msg (msg "derez " (:title current-ice))
                                  :effect (effect (derez current-ice))}]})

   "Force of Nature"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 2 2 "Code Gate")
                                 (strength-pump 1 1)]})

   "Garrote"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 1 1)]})

   "God of War"
   (auto-icebreaker ["All"]
                    {:flags {:runner-phase-12 (req true)}
                     :abilities [(strength-pump 2 1)
                                 {:counter-cost [:virus 1]
                                  :msg "break 1 subroutine"}
                                 {:label "Take 1 tag to place 2 virus counters (start of turn)"
                                  :once :per-turn
                                  :effect (req (wait-for (gain-tags state :runner 1)
                                                         (if (not (get-in @state [:tag :tag-prevent]))
                                                           (do (add-counter state side card :virus 2)
                                                               (system-msg state side
                                                                           (str "takes 1 tag to place 2 virus counters on God of War"))
                                                               (effect-completed state side eid))
                                                           (effect-completed state side eid))))}]})

   "Golden"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 2 2 "Sentry")
                                 (strength-pump 2 4)
                                 {:label "Derez a Sentry and return Golden to your Grip"
                                  :cost [:credit 2]
                                  :req (req (and (rezzed? current-ice) (has-subtype? current-ice "Sentry")))
                                  :msg (msg "derez " (:title current-ice) " and return Golden to their Grip")
                                  :effect (effect (derez current-ice)
                                                  (move card :hand))}]})

   "Gordian Blade"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 1 1 "Code Gate")
                                 (strength-pump 1 1 :all-run)]})

   "Gingerbread"
   (auto-icebreaker ["Tracer"]
                    {:abilities [(break-sub 1 1 "Tracer")
                                 (strength-pump 2 3)]})

   "GS Sherman M3"
   (global-sec-breaker "Barrier")

   "GS Shrike M2"
   (global-sec-breaker "Sentry")

   "GS Striker M1"
   (global-sec-breaker "Code Gate")

   "Houdini"
   {:abilities [(break-sub 1 1 "Code Gate")
                {:cost [:credit 2]
                 :msg "add 4 strength (using at least 1 stealth [Credits])"
                 :effect (effect (pump card 4 :all-run)) :pump 4}]}

   "Inti"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 1 1 "Barrier")
                                 (strength-pump 2 1 :all-run)]})

   "Inversificator"
   (auto-icebreaker ["Code Gate"]
                    {:implementation "No restriction on which pieces of ICE are chosen"
                     :abilities [{:label "Swap the Code Gate you just passed with another ICE"
                                  :once :per-turn
                                  :req (req (:run @state))
                                  :prompt "Select the Code Gate you just passed and another piece of ICE to swap positions"
                                  :choices {:req #(and (installed? %) (ice? %)) :max 2}
                                  :msg (msg "swap the positions of " (card-str state (first targets)) " and " (card-str state (second targets)))
                                  :effect (req (when (= (count targets) 2)
                                                 (swap-ice state side (first targets) (second targets))))}
                                 (break-sub 1 1 "Code Gate")
                                 (strength-pump 1 1)]})

   "Ika"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 1 2 "Sentry")
                                 (strength-pump 2 3)
                                 {:label "Host Ika on a piece of ICE"
                                  :prompt (msg "Host Ika on a piece of ICE")
                                  :cost [:credit 2]
                                  :choices {:req #(and (ice? %)
                                                       (installed? %)
                                                       (can-host? %))}
                                  :msg (msg "host it on " (card-str state target))
                                  :effect (effect (host target card))}]})

   "Knight"
   {:abilities [{:label "Host Knight on a piece of ICE"
                 :effect (req (let [k (get-card state card)
                                    hosted (ice? (:host k))
                                    icepos (ice-index state (get-card state (:host k)))]
                                (resolve-ability state side
                                 {:prompt (msg "Host Knight on a piece of ICE" (when hosted " not before or after the current host ICE"))
                                  :cost [:click 1]
                                  :choices {:req #(if hosted
                                                    (and (or (when (= (:zone %) (:zone (:host k)))
                                                               (not= 1 (abs (- (ice-index state %) icepos))))
                                                             (not= (:zone %) (:zone (:host k))))
                                                         (ice? %)
                                                         (can-host? %)
                                                         (installed? %)
                                                         (not (some (fn [c] (has? c :subtype "Caïssa")) (:hosted %))))
                                                    (and (ice? %)
                                                         (installed? %)
                                                         (can-host? %)
                                                         (not (some (fn [c] (has? c :subtype "Caïssa")) (:hosted %)))))}
                                  :msg (msg "host it on " (card-str state target))
                                  :effect (effect (host target card))} card nil)))}
                {:cost [:credit 2]
                 :req (req (ice? (get-nested-host card)))
                 :msg "break 1 subroutine on the host ICE"}]}

   "Laamb"
   (auto-icebreaker
     ["Barrier"]
     {:abilities [(break-sub 2 0 "Barrier")
                  (strength-pump 3 6)
                  (wrestling-breaker 2 "Barrier")]})

   "Leviathan"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 3 3 "Code Gate")
                                 (strength-pump 3 5)]})

   "Lustig"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 3 5)
                                 {:label "Bypass Sentry being encountered"
                                  :req (req (has-subtype? current-ice "Sentry"))
                                  :msg (msg "trash it and bypass " (:title current-ice))
                                  :effect (effect (trash card {:cause :ability-cost}))}]})

   "Mammon"
   (auto-icebreaker ["All"]
                    {:flags {:runner-phase-12 (req (pos? (:credit runner)))}
                     :abilities [{:label "X [Credits]: Place X power counters"
                                  :prompt "How many power counters to place on Mammon?" :once :per-turn
                                  :choices {:number (req (:credit runner))}
                                  :req (req (:runner-phase-12 @state))
                                  :effect (effect (lose-credits target)
                                                  (add-counter card :power target))
                                  :msg (msg "place " target " power counters on it")}
                                 {:counter-cost [:power 1]
                                  :label "Hosted power counter: Break ICE subroutine"
                                  :msg "break 1 ICE subroutine"}
                                 (strength-pump 2 2)]
                     :events {:runner-turn-ends {:effect (effect (update! (assoc-in card [:counter :power] 0)))}}})

   "Mass-Driver"
   (auto-icebreaker ["Code Gate"]
                    {:implementation "Prevention of subroutine resolution on next ICE is manual"
                     :abilities [(break-sub 2 1 "Code Gate")
                                 (strength-pump 1 1)]})

   "Maven"
   {:abilities [(break-sub 2 1 "ICE")]
    :events (let [maven {:silent (req true)
                         :req (req (is-type? target "Program"))
                         :effect (effect (update-breaker-strength card))}]
              {:runner-install maven :trash maven :card-moved maven})
    :strength-bonus (req (count (filter #(is-type? % "Program") (all-active-installed state :runner))))}

   "Morning Star"
   {:abilities [(break-sub 1 0 "Barrier")]}

   "Mimic"
   {:abilities [(break-sub 1 1 "Sentry")]}

   "Mongoose"
   (auto-icebreaker ["Sentry"]
                    {:implementation "Usage restriction is not implemented"
                     :abilities [(break-sub 1 2 "Sentry")
                                 (strength-pump 2 2)]})

   "MKUltra"
   (conspiracy "MKUltra" "Sentry"
               [{:cost [:credit 3]
                 :effect (effect (pump card 2)) :pump 2
                 :msg "add 2 strength and break up to 2 subroutines"}])

   "Musaazi"
   (khumalo-breaker "sentry")

   "NaNotK"
   (auto-icebreaker ["Sentry"]
                    {:effect (req (add-watch state (keyword (str "nanotk" (:cid card)))
                                              (fn [k ref old new]
                                                (let [server (first (get-in @state [:run :server]))]
                                                  (when (or
                                                          ; run initiated or ended
                                                          (not= (get-in old [:run])
                                                                (get-in new [:run]))
                                                          ; server configuration changed (redirected or newly installed ICE)
                                                          (not= (get-in old [:corp :servers server :ices])
                                                                (get-in new [:corp :servers server :ices])))
                                                    (update-breaker-strength ref side card))))))
                     :strength-bonus (req (if-let [numice (count run-ices)] numice 0))
                     :leave-play (req (remove-watch state (keyword (str "nanotk" (:cid card)))))
                     :abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 3 2)]})

   "Nfr"
   {:implementation "Adding power counter is manual"
    :abilities [{:label "Place 1 power counter on Nfr"
                 :msg "place 1 power counter on it"
                 :ability-type :manual-state
                 :effect (effect (add-counter card :power 1)
                                 (update-breaker-strength card))}
                (break-sub 1 1 "Barrier")]
    :strength-bonus (req (get-counters card :power))}

   "Ninja"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 3 5)]})

   "Omega"
   (auto-icebreaker ["All"]
                    {:abilities [{:cost [:credit 1] :req (req (= 1 (:position run)))
                                  :msg "break 1 subroutine on the innermost ICE protecting this server"}
                                 (strength-pump 1 1)]})

   "Overmind"
   (auto-icebreaker ["All"]
                    {:effect (effect (add-counter card :power (available-mu state)))
                     :abilities [{:counter-cost [:power 1]
                                  :msg "break 1 subroutine"}
                                 (strength-pump 1 1)]})
   "Paperclip"
   (conspiracy "Paperclip" "Barrier"
               [{:label (str "X [Credits]: +X strength, break X subroutines")
                 :choices {:number (req (:credit runner))
                           :default (req (if (:current-strength current-ice)
                                           (max (- (:current-strength current-ice)
                                                   (:current-strength card))
                                                1)
                                           1))}
                 :prompt "How many credits?"
                 :effect (effect (lose-credits target)
                                 (pump card target))
                 :msg (msg "spend " target " [Credits], increase strength by " target ", and break "
                           (quantify target "Barrier subroutine"))}])

   "Passport"
   (central-breaker "Code Gate"
                    (break-sub 1 1 "Code Gate")
                    (strength-pump 2 2))

   "Peacock"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 2 1 "Code Gate")
                                 (strength-pump 2 3)]})

   "Peregrine"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 1 1 "Code Gate")
                                 (strength-pump 3 3)
                                 {:label "Derez a Code Gate and return Peregrine to your Grip"
                                  :cost [:credit 2]
                                  :req (req (and (rezzed? current-ice) (has-subtype? current-ice "Code Gate")))
                                  :msg (msg "derez " (:title current-ice) " and return Peregrine to their Grip")
                                  :effect (effect (derez current-ice)
                                                  (move card :hand))}]})

   "Persephone"
   (auto-icebreaker ["Sentry"]
                    {:implementation "Requires runner to input the number of subroutines allowed to resolve"
                     :abilities [(break-sub 2 1 "Sentry")
                                 (strength-pump 1 1)]
                     :events {:pass-ice {:req (req (and (has-subtype? target "Sentry") (rezzed? target)) (pos? (count (:deck runner))))
                                         :optional {:prompt (msg "Use Persephone's ability??")
                                                    :yes-ability {:prompt "How many subroutines resolved on the passed ICE?"
                                                                  :async true
                                                                  :choices {:number (req 10)}
                                                                  :msg (msg (if (pos? target)
                                                                              (str "trash " (:title (first (:deck runner))) " from their Stack and trash " target " cards from R&D")
                                                                              (str "trash " (:title (first (:deck runner))) " from their Stack and nothing from R&D")))
                                                                  :effect (effect (mill :runner)
                                                                                  (mill :runner :corp target))}}}}})

   "Pipeline"
   (auto-icebreaker ["Sentry"]
                    {:abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 2 1 :all-run)]})

   "Puffer"
   (auto-icebreaker ["Sentry"]
                    {:implementation "Memory use must be manually tracked by the Runner"
                     :abilities [(break-sub 1 1 "Sentry")
                                 (strength-pump 2 1)
                                 {:cost [:click 1] :msg "place one power counter"
                                  :label "Place 1 power counter"
                                  :effect (effect (add-counter card :power 1)
                                                  (update-breaker-strength card))}
                                 {:cost [:click 1] :msg "remove one power counter"
                                  :label "Remove 1 power counter"
                                  :effect (effect (add-counter card :power -1)
                                                  (update-breaker-strength card))}]
                     :strength-bonus (req (get-counters card :power))})

   "Refractor"
   (auto-icebreaker ["Code Gate"]
                    {:implementation "Stealth credit restriction not enforced"
                     :abilities [(break-sub 1 1 "Code Gate")
                                 (strength-pump 1 3)]})
   "Sadyojata"
   (deva "Sadyojata")

   "Sage"
   (ancient-greek-breaker "sage" [{:cost [:credit 2] :req (req (or (has-subtype? current-ice "Barrier")
                                                                   (has-subtype? current-ice "Code Gate")))
                                   :msg "break 1 Code Gate or Barrier subroutine"}])

   "Saker"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 1 1 "Barrier")
                                 (strength-pump 2 2)
                                 {:label "Derez a Barrier and return Saker to your Grip"
                                  :cost [:credit 2]
                                  :req (req (and (rezzed? current-ice) (has-subtype? current-ice "Barrier")))
                                  :msg (msg "derez " (:title current-ice) " and return Saker to their Grip")
                                  :effect (effect (derez current-ice)
                                                  (move card :hand))}]})

   "Savant"
   (ancient-greek-breaker "savant" [{:cost [:credit 2] :req (req (has-subtype? current-ice "Sentry"))
                                     :msg "break 1 Sentry subroutine"}
                                    {:cost [:credit 2] :req (req (has-subtype? current-ice "Code Gate"))
                                     :msg "break 2 Code Gate subroutines"}])

   "Snowball"
   (auto-icebreaker ["Barrier"]
                    {:abilities [{:cost [:credit 1] :msg "break 1 Barrier subroutine"
                                  :effect (effect (pump card 1 :all-run))}
                                 (strength-pump 1 1)]})

   "Sharpshooter"
   (auto-icebreaker ["Destroyer"]
                    {:abilities [{:label "[Trash]: Break any number of Destroyer subroutines"
                                  :msg "break any number of Destroyer subroutines"
                                  :effect (effect (trash card {:cause :ability-cost}))}
                                 (strength-pump 1 2)]})

   "Shiv"
   (break-and-enter "Sentry")

   "Spike"
   (break-and-enter "Barrier")

   "Study Guide"
   {:abilities [(break-sub 1 1 "Code Gate")
                {:cost [:credit 2] :msg "place 1 power counter"
                 :effect (effect (add-counter card :power 1)
                                 (update-breaker-strength card))}]
    :strength-bonus (req (get-counters card :power))}

   "Sūnya"
   {:implementation "Adding power counter is manual"
    :abilities [{:label "Place 1 power counter on Sūnya"
                 :ability-type :manual-state
                 :effect (effect (add-counter card :power 1)
                                 (system-msg (str "places 1 power counter on Sūnya"))
                                 (update-breaker-strength card))}
                (break-sub 2 1 "Sentry")]
    :strength-bonus (req (get-counters card :power))}

   "Switchblade"
   (auto-icebreaker ["Sentry"]
                    {:implementation "Stealth credit restriction not enforced"
                     :abilities [(break-sub 1 0 "Sentry")
                                 (strength-pump 1 7)]})

   "Torch"
   (auto-icebreaker ["Code Gate"]
                    {:abilities [(break-sub 1 1 "Code Gate")
                                 (strength-pump 1 1)]})

   "Tycoon"
   (auto-icebreaker ["Barrier"]
                    {:abilities [(break-sub 1 2 "Barrier" (effect (update! (assoc-in card [:special :tycoon-used] true))))
                                 (strength-pump 2 3)]
                     :events {:pass-ice {:req (req (get-in card [:special :tycoon-used]))
                                         :msg "give the Corp 2 [Credits]"
                                         :effect (effect (update! (dissoc-in card [:special :tycoon-used]))
                                                         (gain-credits :corp 2))}}})

   "Vamadeva"
   (deva "Vamadeva")

   "Wyrm"
   (auto-icebreaker ["All"]
                    {:abilities [{:cost [:credit 3]
                                  :msg "break 1 subroutine on ICE with 0 or less strength"}
                                 {:cost [:credit 1]
                                  :label "Give -1 strength to current ICE"
                                  :req (req (rezzed? current-ice))
                                  :msg (msg "give -1 strength to " (:title current-ice))
                                  :effect (req (update! state side (update-in card [:wyrm-count] (fnil #(+ % 1) 0)))
                                               (update-ice-strength state side current-ice))}
                                 (strength-pump 1 1)]
                     :events (let [auto-pump (fn [state side eid card targets]
                                               ((:effect breaker-auto-pump) state side eid card targets))
                                   wy {:effect (effect (update! (dissoc card :wyrm-count))
                                                       (auto-pump eid (get-card state card) targets))}]
                               {:pre-ice-strength {:req (req (and (= (:cid target) (:cid current-ice))
                                                                  (:wyrm-count card)))
                                                   :effect (req (let [c (:wyrm-count (get-card state card))]
                                                                  (ice-strength-bonus state side (- c) target)
                                                                  (auto-pump state side eid card targets)))}
                                :pass-ice wy
                                :run-ends wy})})

   "Yusuf"
   (khumalo-breaker "barrier")

   "Yog.0"
   {:abilities [(break-sub 0 1 "Code Gate")]}

   "ZU.13 Key Master"
   (cloud-icebreaker
     (auto-icebreaker ["Code Gate"]
                      {:abilities [(break-sub 1 1 "Code Gate")
                                   (strength-pump 1 1)]}))})
