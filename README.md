# DineFlow

**Real-Time Restaurant POS & Kitchen Display System (Java 21 + Vanilla JavaScript)**

DineFlow unifies front-of-house table management and back-of-house kitchen ticket routing on a **Java 21 Virtual Threads** server backed by **SQLite WAL** persistence and a live-updating **JavaScript** POS & KDS console. Waitstaff fire Kitchen Order Tickets (KOTs) from an interactive floor plan, chefs track live prep timers across `QUEUED -> PREPARING -> READY -> SERVED` stages, and tables settle itemized 5% GST bills with instant split-bill calculations.

## Quickstart

~~~bash
git clone https://github.com/itsmerbk/DineFlow.git
cd DineFlow

mkdir -p out
javac -cp "lib/*" -d out src/com/dineflow/*.java

# 1. Run automated POS, KDS lifecycle & 5% GST billing tests
java -ea --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.dineflow.DineFlowTest

# 2. Start the live POS & KDS web application (http://127.0.0.1:8082)
java --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.dineflow.Main
~~~
