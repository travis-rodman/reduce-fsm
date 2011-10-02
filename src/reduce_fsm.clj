(ns reduce-fsm
  "Generate and display functional finate state machines"
  (:use [clojure pprint]
	[clojure.core.match
	 [core :only [match match-1]]
	 regex])
  (:require [vijual :only [draw-directed-graph]]
	    [dorothy [core :as d]]
	    )
  )

;; fsm-seq    - creates lazy sequence based on an fsm
;; fsm-filter - stateful filters for use with filter/remove


(defn- create-transition
  "create a single transition make with default params if none specified"
  [[from to]]
  (let [has-params? (map? (first to))
	{:keys [action]} (if has-params? (first to) {})
	to-state (if has-params? (second to) (first to))]
    {:evt (last from)
     :action action
     :to-state to-state}))

(defn- create-state-map
  "Create an entry for a single '[:state evt1 -> :state1 :evt2 -> :state2 ...]"
  [forms]
  (let [from-state (first forms)
	transitions (partition 2 1
			       (remove #(= '-> (first %))
				       (partition-by #(= '-> %) (rest forms))))]
    {:from-state from-state
     :state-params (when (and (-> forms second map?) (->> forms (drop 3) first (= '->))) (second forms))
     :transitions (vec (map create-transition transitions))}))

(defn- create-state-maps [states]
  (let [state-maps  (map create-state-map states)]
    (sanity-check-fsm state-maps)
    state-maps))
  


(defn fn-sym?
  "is sym a symbol that resoves to a function in the current namespace"
  [sym]
  (when (symbol? sym)
    (when-let [a-var (ns-resolve *ns* sym)]
      (fn? @a-var))))

(defn- state-fn-name [sym]
  (let [name (cond
	      (fn? sym) (-> sym meta :name str)
	      (keyword? sym) (name sym)
	      :else (str sym))]
    (gensym (str "state" "-" name "-"))))

(defn- state-for-action [state]
  (cond
   (fn? state) (-> state meta :name keyword)
   (keyword? state) state
   :else (keyword state)))

(defn- report-compile-error [& args]
  (let [msg (apply format args)]
    (println (str "error: " msg))
    (throw (Exception. (str "FSM compilation exception: " msg)))))

(defn- sanity-check-fsm [state-maps]
  (let [state-names (set (map :from-state state-maps))
	transitions (mapcat (fn [s] (map #(vector (:from-state s) (:to-state %)) (:transitions s))) state-maps)]
    
    (doseq [[from-state to-state] transitions]
      (when-not (state-names to-state)	
	(report-compile-error "The state %s was referenced in a transition from %s but does not exist" to-state from-state)))))


(defn- expand-evt-dispatch [state-fn-map from-state evt acc r evt-map]
  (let [target-state-fn (state-fn-map (:to-state evt-map))
	new-acc (gensym "new-acc")]   
    
    `[~(:evt evt-map)
      (let [~new-acc ~(if (:action evt-map)
			`(~(:action evt-map) ~acc ~evt ~(state-for-action from-state) ~(state-for-action (:to-state evt-map)))
			acc)]
	~(if (fn-sym? (:to-state evt-map))      ;; if the target state is a function we need to check for early conditional termination
	   `(if (~(:to-state evt-map) ~new-acc) ;; truthy return from a state function causes the fsm to exit
	      ~new-acc
	      (~target-state-fn ~new-acc ~r))
	   `(~target-state-fn ~new-acc ~r)))])) ;; normal (keyword) states 

(defn- expand-dispatch [dispatch-type evt acc]
  (case dispatch-type
	:event-only [`match-1 evt]
	:event-and-acc   [`match [evt acc]]
	(throw (RuntimeException. "unknown fsm dispatch type, expected one of [:match-1 :match]"))))
  
(defn- state-fn-impl [dispatch-type state-fn-map state]
  (let [this-state-fn  (state-fn-map (:from-state state))
	rst (gensym "rst")
	acc (gensym "acc")
	evt (gensym "evt")]
    `(~this-state-fn
      [~acc [~evt & ~rst]]
      (if ~evt
	#(~@(expand-dispatch dispatch-type evt acc)
		~@(mapcat (partial expand-evt-dispatch state-fn-map (:from-state state)  evt acc rst) (:transitions state))
		:else (~this-state-fn ~acc ~rst)
		)
	~acc))))
  

(defn- state-for-meta [state]
  (assoc state
    :transitions (vec (map #(zipmap (keys %) (map str (vals %))) (:transitions state)))))

(defn- fsm-metadata [state-maps] 
  {::states (vec (map state-for-meta state-maps))
   })
  
(defmacro fsm [states & fsm-opts]
  (let [{:keys [dispatch] :or {dispatch :event-only}} fsm-opts 
	state-maps  (create-state-maps states)
	state-fn-names (map state-fn-name (map :from-state state-maps))
	state-fn-map (zipmap (map :from-state state-maps) state-fn-names)] ;; map of state -> letfn function name    
    `(with-meta
       (fn the-fsm#
	([events#] (the-fsm# nil events#))
	([acc# events#]
	  (letfn [~@(map #(state-fn-impl dispatch state-fn-map %) state-maps)]
	    (trampoline ~(first state-fn-names) acc# events#)
	    )))
       ~(fsm-metadata state-maps)
       )))

(defmacro defsm [fsm-name states & opts]
  `(def ~fsm-name (fsm ~states ~@opts)))

;;===================================================================================================
;; fsm-filter impl

(comment

  (pprint 
   (macroexpand-1 '(fsm-filter [[:initial {:pass true}
				3 -> :suppressing]
			       [:suppressing {:pass false}
				6 -> :initial]])))
  
  ;; sample fsm-filter
  (deffsm-filter f [[:initial {:pass true}
		     3 -> :suppressing]
		    [:suppressing {:pass false}
		     6 -> :initial]])

  (= [1 2 6 1 2] (filter (f) [1 2 3 4 5 1 2 6 1 2]))  
  )

(defn- expand-filter-evt-dispatch [state-fn-map state-params from-state evt acc evt-map]
  (let [target-state-fn (state-fn-map (:to-state evt-map))
	target-pass-val (-> evt-map :to-state state-params :pass)
	new-acc (gensym "new-acc")]   
    
    (when (nil? target-state-fn)
      (report-compile-error "The state %s was referenced in a transition from %s but does not exist" (:to-state evt-map) (str from-state)))

    `[~(:evt evt-map)
      (let [~new-acc ~(if (:action evt-map)
			`(~(:action evt-map) ~acc ~evt ~(state-for-action from-state) ~(state-for-action (:to-state evt-map)))
			acc)]
	[~target-pass-val (~target-state-fn ~new-acc)])]))


(defn- state-filter-fn-impl [dispatch-type state-fn-map state-params state]
  (let [this-state-fn  (state-fn-map (:from-state state))
	acc (gensym "acc")
	evt (gensym "evt")] 
    `(~this-state-fn
      [~acc]
      (fn [~evt]
	(~@(expand-dispatch dispatch-type evt acc)
	 ~@(mapcat (partial expand-filter-evt-dispatch state-fn-map state-params (:from-state state) evt acc) (:transitions state)) ;; todo - modify this for filter fn
	 :else [~(get (:state-params state) :pass true) (~this-state-fn ~acc)]
	))
	)))

(defmacro fsm-filter [states & fsm-opts]
  (let [{:keys [dispatch] :or {dispatch :event-only}} fsm-opts 
	state-maps  (create-state-maps states)
	state-fn-names (map state-fn-name (map :from-state state-maps))
	state-params (zipmap (map :from-state state-maps) (map :state-params state-maps))
	state-fn-map (zipmap (map :from-state state-maps) state-fn-names)] ;; map of state -> letfn function name
    `(letfn [~@(map #(state-filter-fn-impl dispatch state-fn-map state-params %) state-maps)]
       (with-meta
	 (fn filter-builder#
	   ([] (filter-builder# nil))
	   ([acc#]
	      (let [curr-state# (atom (~(first state-fn-names) acc#))]
		(fn [evt#]
		  (let [[pass# next-state#] (@curr-state# evt#)]
		    (reset! curr-state# next-state#)
		    pass#)))))
	 ~(fsm-metadata state-maps)))))
          
(defmacro defsm-filter [name states & fsm-opts]
  `(def ~name (fsm-filter states ~@fsm-opts)))
  


;; sample macro expansion
(def sample-filter 
     (letfn [(state-initial [acc]
			    #(match-1 %
				      3  [false (state-suppressing acc)] ;; acc could be modified by an action here
				      :else [true (state-initial acc)]))
	     (state-suppressing [acc]
				#(match-1 %
					  6  [true (state-initial acc)]
					  :else [false (state-suppressing acc)]))]
       state-initial))


;;===================================================================================================
;; fsm-seq impl
(comment
  ;; create a lazy sequence of all the times we saw a followed by c without an intervening b
  ;; terminate after 1
  (defn emit-event [acc evt & _] evt)
  (defn inc-matches [acc & _] (inc acc))

    
  (defsm-seq my-seq [[:wating-for-a
		      #".*event a" -> :seen-a]
		     [:seen-a
		      #".*event b" -> :waiting-for-c
		      #".*event c" -> {:emit emit-event :action inc-matches} :wating-for-a]
		     [:waiting-for-c
		      #".*event c" -> waiting-for-a]])
  (take 10 (my-seq (ds/read-lines "afile.txt")))
  )


(defn- next-emitted [f]
  (when f
    (loop [[emitted next-step] (f)]
      (if next-step
	(if (not= ::no-event emitted)
	  [emitted next-step]
	  (recur (next-step)))
	[emitted nil]))))

(defn- fsm-seq-impl [f]
  (let [[emitted next-step] (next-emitted f)]
    (lazy-seq
     (if next-step
       (cons emitted (fsm-seq-impl next-step))
       (when (not= ::no-event emitted)
	 (cons emitted nil))))))


(defn sample-seq-expansion [acc events]
  (letfn [(emit-event [acc evt] evt)
	  (inc-matches [acc & _] (inc acc))
	  (state-waiting-for-a [acc events]			       
				(if (seq events)
				  #(match [(first events)]
					  [#".*event a"] [::no-event (state-seen-a acc (rest events))]
					  :else [::no-event (state-waiting-for-a acc (rest events))])
				   nil))
 	  (state-seen-a [acc events]
 			(if (seq events)
 			  #(match [(first events)]
 				  [#".*event b"] [::no-event (state-waiting-for-c acc (rest events))]
 				  [#".*event c"] [(emit-event acc (first events)) (state-waiting-for-a (inc-matches acc (first events) :seen-a :waiting-for-a) (rest events))]
 				  :else [::no-event (state-seen-a acc (rest events))])
 			  nil))
 	  (state-waiting-for-c [acc events]
 			       (if (seq events)
 				 #(match [(first events)]
 					 [#".*event c"] [::no-event (state-waiting-for-a acc (rest events))]
					 :else [::no-event (state-waiting-for-c acc (rest events))])
				 nil))]
    ;;    ((state-waiting-for-a 0 ["1 event a" "2 event b" "3 event c" "4 event a" "5 event c" "6 event a"]))))
    (when (seq events)
      (fsm-seq-impl (state-waiting-for-a acc events)))))


  



;; ===================================================================================================
;; methods to display fsm

(defn- dot-exists
  "return true if the dot executable from graphviz is available on the path"
  [& _ ]
  (try
    (->> "dot -V"
	 (.exec (Runtime/getRuntime))
	 (.waitFor)
	 (= 0))
    (catch Exception e false)))

(defmulti show-fsm "show the fsm using graphviz if available, vijual if it's not" (memoize dot-exists))

(defn- dorothy-edge [from-state trans]
  (let [label (str  " " (:evt trans)
		    (when (:action trans)
		      (str "\\n(" (-> trans :action meta :name str) ")") ))]
    (vector from-state (:to-state trans) {:label label} )
    ))

(defn- show-dorothy-fsm [fsm]
  (-> (d/digraph
       (mapcat #(map (partial dorothy-edge (:from-state %)) (:transitions %))
	       (-> fsm meta ::states)))
      d/dot
      d/show!)  
  )

(defmethod show-fsm true 
  [fsm]
  (show-dorothy-fsm fsm))

(defn- show-vijual-fsm [fsm]
  (vijual/draw-directed-graph
   (mapcat #(map (fn [trans] (vector (:from-state %) (:to-state trans))) (:transitions %))
	   (-> fsm meta ::states))))

(defmethod show-fsm false  [fsm]
  (show-vijual-fsm fsm))

  

(comment
  (pprint
   (create-state-map '[:locked
		       #"[0-9]" -> {:action store-code} :locked
		       "*" -> :locked
		       "#" -> {:guard code-matches :action unlock-door} :unlocked]))

  

  ;; ===================================================================================================
  ;; sample of searching a log for a sequence of events
  (defn save-line [state evt from-state to-state]
    (conj state evt))
  
    (deffsm log-search [[:waiting-for-a
			 #".*event a" -> :seen-a]
			[:seen-a
			 #".*event b" -> :waiting-for-c
			 #".*event c" -> {:action save-line} :waiting-for-a
			 #".*event d" -> exit-now]
			[:waiting-for-c
			 #".*event c" -> :waiting-for-a]])
  
  (deffsm log-search [[:waiting-for-a
		       [#".*event a"] -> :seen-a]
		      [:seen-a
		       [#".*event b"] -> :waiting-for-c
		       [#".*event c"] -> {:action save-line} :waiting-for-a
		       [#".*event d"] -> exit-now]
		      [:waiting-for-c
		       [#".*event c"] -> :waiting-for-a]])

  (log-search [] (ds/read-lines "my-log.txt"))
  
  (log-search2 [] ["1 event a" "2 event b" "3 event c" "4 event a" "5 event c" "6 event a"])
  )


;; macro expansion
(defn save-line [state evt from-state to-state]
  (conj state evt))

(defn exit-fsm [state]
  true)

(defn log-search2 [state events]
  (letfn [(state-waiting-for-a [state [evt & r]]
			       (if evt
				 #(match [evt]
					 [#".*event a"] (state-seen-a state r)
					 :else (state-waiting-for-a state r))
				 state))
 	  (state-seen-a [state [evt & r]]
 			(if evt
 			  #(match [evt]
 				  [#".*event b"] (state-waiting-for-c state r)
 				  [#".*event c"] (state-waiting-for-a (save-line state evt :seen-a :waiting-for-a) r)
				  [#".*event d"] (let [new-state (save-line state evt :seen-a "exit-fsm")]
						   (if (exit-fsm new-state)
						     new-state
						     (state-seen-a new-state r)))
 				  :else (state-seen-a state r))
 			  state))
 	  (state-waiting-for-c [state [evt & r]]
 			       (if [evt]
 				 #(match [evt]
 					 [#".*event c"] (state-waiting-for-a  state  r)
					 :else (state-waiting-for-c state r))
				 state))]
    (trampoline state-waiting-for-a state events)))



