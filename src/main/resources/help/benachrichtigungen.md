# Benachrichtigungen und E-Mail-Berichte

WebTestHelper informiert per E-Mail über den Zustand der geprüften Websites. Benachrichtigungen werden gesammelt versendet, damit das Postfach übersichtlich bleibt.

## Wann wird eine E-Mail versendet?

Eine E-Mail wird verschickt, wenn ein Prüflauf neue oder wieder aufgetretene Fehler erzeugt. Beim monatlichen Tiefenlauf wird auch dann eine Entwarnung gesendet, wenn alles in Ordnung ist. Fehlgeschlagene Prüfläufe werden immer gemeldet, da ein stillstehender Prüfer schwerer wiegt als ein übersehener toter Link. Eine tägliche Puls-Prüfung ohne Änderungen versendet keine E-Mail.

## Eine E-Mail pro Zeitfenster

WebTestHelper versendet nicht für jede Website eine separate E-Mail, sondern fasst alle Websites eines Zeitfensters in einer einzigen Sammelmail zusammen. So erhält ein Empfänger für den nächtlichen Prüflauf aller betreuten Websites genau eine Benachrichtigung statt dutzender Einzelnachrichten.

## Stummgeschaltete Feststellungen

Eine stummgeschaltete Feststellung wird während der Dauer der Stummschaltung nicht per E-Mail gemeldet, selbst wenn sie bei weiteren Prüfläufen erneut auftritt. Erst wenn die Stummschaltung abgelaufen ist und der Fehler weiterhin besteht, erscheint er wieder in den Berichten.

## Zuverlässigkeit und Postausgang

Schlägt die Zustellung an den E-Mail-Server fehl, bricht der Prüflauf nicht ab. Fehlgeschlagene Nachrichten bleiben im Postausgang sichtbar und werden automatisch wiederholt. Zudem weist ein Warnhinweis in der Anwendung auf Zustellprobleme hin, sodass kein Fehler unbemerkt verloren geht.
