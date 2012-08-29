(ns transformers
  (:require [clojure.string :as string]))

(defn- substring [s n]
  (apply str (drop n s)))

(defn- empty-line-transformer [text state]
  [text (if (string/blank? text) (dissoc state :hr :heading) state)])

(defn- escape-code [s]
  (-> s
    (string/replace #"&" "&amp;")
    (string/replace #"\*" "&#42;")
    (string/replace #"\^" "&#94;")
    (string/replace #"\_" "&#95;")
    (string/replace #"\~" "&#126;")    
    (string/replace #"\<" "&lt;")
    (string/replace #"\>" "&gt;")    
    ;(string/replace #"\/" "&frasl;") ;screws up ClojureScript compiling
    (string/replace #"\[" "&#91;")
    (string/replace #"\]" "&#93;")
    (string/replace #"\(" "&#40;")
    (string/replace #"\)" "&#41;")
    (string/replace #"\"" "&quot;")))

(defn- escape-link [& xs]
  (->
    (apply str (apply concat xs))
    (string/replace #"\*" "&#42;")
    (string/replace #"\^" "&#94;")
    (string/replace #"\_" "&#95;")
    (string/replace #"\~" "&#126;")    
    seq))

(defn- separator-transformer [escape? text, open, close, separator, state]
  (if (:code state)
    [text, state]
    (loop [out []
           buf []
           tokens (partition-by (partial = (first separator)) (seq text))
           cur-state (assoc state :found-token false)]
      (cond
        (empty? tokens)
        [(apply str (into (if (:found-token cur-state) (into out separator) out) buf))
         (dissoc cur-state :found-token)]
       
        (:found-token cur-state)
        (if (= (first tokens) separator)      
          (recur (vec 
                   (concat 
                     out
                     (seq open) 
                     (if escape? (seq (escape-code (apply str buf))) buf) 
                     (seq close)))
                 []
                 (rest tokens)
                 (assoc cur-state :found-token false))
          (recur out
                 (into buf (first tokens))
                 (rest tokens)
                 cur-state))
    
        (= (first tokens) separator)
        (recur out, buf, (rest tokens), (assoc cur-state :found-token true))
     
        :default
        (recur (into out (first tokens)), buf, (rest tokens), cur-state)))))
        

(defn bold-transformer [text, state]
  (separator-transformer false text, "<b>", "</b>", [\* \*], state))

(defn alt-bold-transformer [text, state]
  (separator-transformer false text, "<b>", "</b>", [\_ \_], state))

(defn em-transformer [text, state]
  (separator-transformer false text, "<em>", "</em>", [\*], state))

(defn italics-transformer [text, state]
  (separator-transformer false text, "<i>", "</i>", [\_], state))

(defn inline-code-transformer [text, state]
  (separator-transformer true text, "<code>", "</code>", [\`], state))

(defn strikethrough-transformer [text, state]
  (separator-transformer false text, "<del>", "</del>", [\~ \~], state))

(defn superscript-transformer [text, state]
  (if (:code state)
    [text, state]
    (let [tokens (partition-by (partial contains? #{\^ \space}) text)]
      (loop [buf []             
             remaining tokens]
        (cond 
          (empty? remaining)
          [(apply str buf), state]
          
          (= (first remaining) [\^])
          (recur (into buf (concat (seq "<sup>") (second remaining) (seq "</sup>")))                 
                 (drop 2 remaining))
          
          :default
          (recur (into buf (first remaining)) (rest remaining)))))))

(defn heading-transformer [text, state]
  (if (:code state)
    [text, state]
    (let [num-hashes (count (take-while (partial = \#) (seq text)))]    
    (if (pos? num-hashes)   
      [(str "<h" num-hashes ">" (apply str (drop num-hashes text)) "</h" num-hashes ">") (assoc state :heading true)]
      [text state]))))

(defn paragraph-transformer [text, state]    
  (if (or (:heading state) (:hr state) (:code state) (:lists state) (:blockquote state))
    [text, state]
    (cond
      (:paragraph state)     
      (if (or (:eof state) (empty? (string/trim text)))
        [(str text "</p>"), (assoc state :paragraph false)]
        [text, state])
     
      (and (not (:eof state)) (:last-line-empty? state))
      [(str "<p>" text), (assoc state :paragraph true)]
     
      :default
      [text, state])))
      
(defn code-transformer [text, state]  
  (if (or (:lists state) (:codeblock state))
    [text, state]
    (cond         
    (:code state)
    (if (or (:eof state) (not (.startsWith text "    ")))
      ["</code></pre>", (assoc state :code false)]      
      [(str "\n" (escape-code (.substring text 4))), state])
    
    (empty? (string/trim text)) 
    [text, state]
    
    :default
    (let [num-spaces (count (take-while (partial = \space) text))]
      (if (> num-spaces 3)
        [(str "<pre><code>" (escape-code  (.substring text 4))), (assoc state :code true)]
        [text, state])))))      


(defn codeblock-transformer [text, state]    
  (let [trimmed (string/trim text)] 
    (cond
      (and (= [\`\`\`] (take 3 trimmed)) (:codeblock state))
      [(str "</code></pre>" (apply str (drop 3 trimmed))), (assoc state :code false :codeblock false)]
      
      (and (= [\`\`\`] (take-last 3 trimmed)) (:codeblock state))
      [(str "</code></pre>" (apply str (drop-last 3 trimmed))), (assoc state :code false :codeblock false)]
      
      (= [\`\`\`] (take 3 trimmed))
      (let [[lang code] (split-with (partial not= \space) (drop 3 trimmed))]
        [(str "<pre><code" (if (not-empty lang) (str " class=\"brush: " (apply str lang) ";\"")) ">" (escape-code (apply str (rest code)))), (assoc state :code true :codeblock true)])
            
    (:codeblock state)
    [(str (escape-code text) "\n"), state]
    :default
    [text, state])))

(defn hr-transformer [text, state]
  (if (:code state) 
    [text, state]
    (let [trimmed (string/trim text)] 
      (if (or (= "***" trimmed)
              (= "* * *" trimmed)
              (= "*****" trimmed)
              (= "- - -" trimmed))
        [(str "<hr/>") (assoc state :hr true)]
        [text state]))))        


(defn blockquote-transformer [text, state]
  (if (or (:code state) (:codeblock state) (:lists state))
    [text, state]
    (cond
      (:blockquote state)
      (if (or (:eof state) (empty? (string/trim text)))
        ["</p></blockquote>", (assoc state :blockquote false)]
        [(str text " "), state])
      
      :default
      (if (= \> (first text))
        [(str "<blockquote><p>" (apply str (rest text)) " "), (assoc state :blockquote true)]
        [text, state]))))

(defn- href [title link]
  (escape-link (seq "<a href='") link (seq "'>") title (seq "</a>")))

(defn- img [alt url & [title]]
  (escape-link  
    (seq "<img src=\"") url  (seq "\" alt=\"") alt 
    (if (not-empty title)
      (seq (apply str "\" title=" (apply str title) " />"))
      (seq "\" />"))))

(defn link-transformer [text, state]
  (if (or (:codeblock state) (:code state))
    [text, state]
    (loop [out []
           tokens (seq text)]
      (if (empty? tokens)
        [(apply str out) state]
                
        (let [[head, xs] (split-with (partial not= \[) tokens)
              [title, ys] (split-with (partial not= \]) xs)
              [dud, zs] (split-with (partial not= \() ys)
              [link, tail] (split-with (partial not= \)) zs)]
          
          [(count title) (count link)]
          (if (or (< (count title) 2) 
                  (< (count link) 2)
                  (< (count tail) 1))
            (recur (concat out head title dud link), tail)
            (recur 
              (->>
                (if (= (last head) \!)
                  (let [alt (rest title)
                        [url title] (split-with (partial not= \space) (rest link))
                        title (apply str (rest title))]                                   
                    (concat (butlast head) (img alt url title)))
                  (concat head (href (rest title) (rest link))))                                
                (into out))              
              (rest tail))))))))


(defn close-lists [lists]
  (apply str
         (for [[list-type] lists]    
           (str "</li></" (name list-type) ">"))))


(defn add-row [row-type list-type num-indents indents content state]  
  (if list-type
    (cond
      (< num-indents indents)
      (let [lists-to-close (take-while #(> (second %) num-indents) (reverse (:lists state)))
            remaining-lists (vec (drop-last (count lists-to-close) (:lists state)))]
                
        [(apply str (close-lists lists-to-close) "</li><li>" content) 
         (assoc state :lists (if (> num-indents (second (last remaining-lists)))
                               (conj remaining-lists [row-type num-indents])
                               remaining-lists))])
      
      (> num-indents indents)
      (do          
        [(str "<" (name row-type) "><li>" content) 
         (update-in state [:lists] conj [row-type num-indents])])
      
      (= num-indents indents)
      [(str "</li><li>" content), state])
    
    [(str "<" (name row-type) "><li>" content)
     (assoc state :lists [[row-type num-indents]])]))

(defn ul [text state]
  (let [[list-type indents] (last (:lists state))
        num-indents (count (take-while (partial = \space) text))
        content (string/trim (substring text (inc num-indents)))]
    (add-row :ul list-type num-indents indents content state)))

(defn ol [text state]
  (let [[list-type indents] (last (:lists state))
        num-indents (count (take-while (partial = \space) text))
        content (string/trim (apply str (drop-while (partial not= \space) (string/trim text))))]
    (add-row :ol list-type num-indents indents content state)))


(defn list-transformer [text state]    
  (cond
    (or (:code state) (:codeblock state))
    [text, state]
    (and (not (:eof state)) 
         (:lists state)
         (string/blank? text))
    [text (assoc state :last-line-empty? true)]
    
    :else
    (let [trimmed (string/trim text)]      
      (cond
        (re-find #"^\* " trimmed)
        (ul text state)
        
        (re-find #"^[0-9]+\." trimmed)
        (ol text state)
        
        (and (or (:eof state) 
                 (:last-line-empty? state)) 
             (not-empty (:lists state)))
        [(str (close-lists (:lists state)) text)
         (dissoc state :lists)]
        
        :else
        [text state]))))


(defn transformer-list []
  [empty-line-transformer
   codeblock-transformer
   code-transformer
   inline-code-transformer
   link-transformer
   hr-transformer                           									                        
   list-transformer    
   heading-transformer                      
   italics-transformer                      
   em-transformer
   bold-transformer
   alt-bold-transformer                      
   strikethrough-transformer
   superscript-transformer                         
   blockquote-transformer
   paragraph-transformer])