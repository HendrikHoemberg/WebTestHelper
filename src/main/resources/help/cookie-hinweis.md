# Cookie-Hinweise und Zustimmungs-Banner

Ein Cookie-Hinweis (Consent-Banner oder Consent Management Platform / CMP) ist ein Dialogfenster, das Besucher beim ersten Aufruf einer Website um Zustimmung zur Speicherung von Cookies und zum Nachladen von Drittanbieter-Diensten bittet.

## Warum ein blockierender Hinweis einem Totalausfall gleicht

Viele Cookie-Werkzeuge legen sich als vollflächige Überlagerung (Overlay) über die gesamte Seite und sperren das Scrollen und Anklicken von Inhalten, bis eine Entscheidung getroffen wurde.

Lässt sich dieser Dialog nicht mehr schließen — beispielsweise weil ein Skriptfehler das Zustimmen-Ereignis blockiert oder das JavaScript des Anbieters nicht mehr lädt —, kommen Besucher an keinerlei Inhalte der Website mehr heran. Für die Besucher und für Suchmaschinen ist die Website damit faktisch nicht erreichbar. Aus einer rechtlichen Hinweispflicht wird so ein unbeabsichtigter Komplettausfall.

## Typische Ursachen

Wenn ein Cookie-Hinweis stehen bleibt, liegt das meist an einer dieser Ursachen:

* **Lizenz oder Domain beim Anbieter abgelaufen:** Viele Cloud-Lösungen sperren das Ausführen des Schließen-Skripts, wenn das Konto des Anbieters abgelaufen ist oder die Domain nicht mehr freigeschaltet ist.
* **JavaScript-Fehler vor dem Klick:** Ein anderes Skript auf der Seite wirft einen Fehler, der die Ereignisverarbeitung des Schließen-Knopfs unterbricht.
* **Geänderte Einbindung oder veralteter Code:** Der Einbindungscode des Cookie-Werkzeugs passt nicht mehr zur aktuellen Version der Plattform.
* **Blockierte Drittanbieter-Ressourcen:** Das Skript des Cookie-Werkzeugs wird durch fehlerhafte Content-Security-Policies (CSP) oder Netzwerkprobleme blockiert.

## Was Sie tun können (und was Sie der Agentur übergeben)

1. **Selbst im Browser nachvollziehen:** Rufen Sie die Startseite in einem privaten Browserfenster auf und klicken Sie auf den Zustimmen- oder Ablehnen-Knopf. Bleibt das Fenster stehen, öffnen Sie die Entwickler-Werkzeuge (F12) und prüfen Sie die Browser-Konsole auf rote Fehlermeldungen.
2. **Konto beim Anbieter prüfen:** Prüfen Sie im Portal Ihres Cookie-Dienstleisters, ob das Kundenkonto aktiv ist, Rechnungen beglichen sind und die Domain korrekt hinterlegt ist.
3. **Meldung an Web-Agentur oder Entwickler:** Übergeben Sie der betreuenden Agentur:
   - Die betroffene Website-Adresse (URL),
   - den Bezeichner des Dialogs aus dem Prüfbericht (z. B. den HTML-Container),
   - den Hinweis, dass der Klick auf den Zustimmen-Knopf das Schließen nicht auslöst,
   - eventuelle Fehlermeldungen aus der Browser-Konsole.
