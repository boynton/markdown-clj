(defproject boynton/markdown-clj "0.9.8"
  :description "Markdown parser"
  :url "https://github.com/boynton/markdown-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]]
  :plugins [[lein-cljsbuild "0.2.4"]]
  
  :cljsbuild
  {:crossovers [transformers],
   :builds
   [{:source-path "src-cljs",      
     :compiler {:output-to "js/markdown.js"
                :optimizations :advanced
                :pretty-print false}}]})
