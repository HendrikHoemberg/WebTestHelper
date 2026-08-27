# Sprachumschalter und Übersetzungen

Die Prüfung des Sprachumschalters kontrolliert, ob die Sprachwahl auf einer Website Besucher tatsächlich zur passenden Übersetzung führt oder ob die Umschaltung ins Leere läuft bzw. unübersetzte Seiten ausliefert.

## Warum eine reine Adressänderung nicht ausreicht

Ein funktionierender Sprachumschalter muss drei Kriterien erfüllen: die aufgerufene Webadresse, die technische Sprachauszeichnung im HTML-Code und der tatsächlich angezeigte Text müssen sich ändern.

In vielen Redaktionssystemen (CMS) wird beim Anlegen einer neuen Sprachfassung zunächst eine 1:1-Kopie der Originalseite unter einer neuen Adresse (wie `/en/...`) gespeichert. Wird dieser Text anschließend nicht übersetzt oder verweist die Sprachwahl auf dieselbe Ausgangsseite, bemerken Besucher keinen Unterschied. Für internationale Kunden und Suchmaschinen ist die fremdsprachige Fassung damit wertlos.

## Die drei Prüfbedingungen im Detail

Die Prüfung untersucht jeden gefundenen Sprachverweis auf der Seite anhand dreier Stufen:

1. **Adresse ändert sich nicht (Sackgasse):** Der Verweis führt zurück auf dieselbe Seite oder nutzt ein leeres Ziel (z. B. `#`). Die Sprachwahl bleibt wirkungslos.
2. **Sprachkennzeichnung bleibt unverändert:** Die Zielseite wird zwar geladen, ihr HTML-Wurzelelement (`<html lang="...">`) ist jedoch weiterhin mit der Ursprungssprache oder gar nicht ausgezeichnet. Dadurch können Screenreader und Browser die Sprache nicht korrekt erkennen.
3. **Inhalt ist identisch (fehlende Übersetzung):** Die Zielseite hat eine neue Adresse und eine geänderte Sprachkennung, zeigt im Haupttext jedoch wortwörtlich denselben Inhalt wie die Ausgangsseite. Die Übersetzung wurde im CMS noch nicht eingepflegt.

## Typische Ursachen und Abhilfe

* **Fehlende Übersetzung im Redaktionssystem:** Wurde eine Seite für eine andere Sprache angelegt, aber der Text nicht übersetzt, muss der Text im CMS nachgepflegt werden.
* **Falsche oder leere Verlinkung:** Verweist der Schalter auf `#` oder auf die aktuelle Adresse, muss in der Vorlage oder Menükonfiguration das richtige Sprachziel hinterlegt werden.
* **Falsche HTML-Sprachauszeichnung im Template:** Wenn das Seitentemplate der Fremdsprache fest mit `lang="de"` codiert ist, muss das Template so angepasst werden, dass es die Kennzeichnung dynamisch an die gewählte Sprache anpasst.
