# Muster aus Feststellungen übernehmen

Beim Anlegen einer Stummschaltungsregel können Sie mit der Schaltfläche „Aus Feststellungen übernehmen“ aktive Befunde der ausgewählten Website abrufen und direkt als Vorlage nutzen. Ein Klick auf eine Feststellung überträgt deren Prüfungsart und erzeugt automatisch ein passendes Fundort-Muster, sodass Sie die Werte nicht manuell eingeben müssen.

## Wann die Übernahme hilft

Das manuelle Eintippen von Prüfungsarten und URL-Mustern ist fehleranfällig — insbesondere bei langen oder verschachtelten Adressen. Die Übernahme beschleunigt das Erstellen einer Regel und schützt vor Tippfehlern:

* Die **Prüfungsart** wird exakt auf diejenige der Feststellung eingestellt.
* Das **Fundort-Muster** wird mit Stern-Platzhaltern (`*`) so gebildet, dass es den Fundort des Befunds zuverlässig erfasst (z. B. `*/kontakt/*` für `/kontakt/`).
* Handelt es sich um einen website-weiten Befund, wird automatisch das Muster `*` gesetzt.

## Voraussetzungen für die Übernahme

Damit Feststellungen abgerufen werden können, müssen folgende Bedingungen erfüllt sein:

1. **Website auswählen:** Im Formular muss zuerst das Feld *Website* belegt sein.
2. **Offene Befunde vorhanden:** Es werden ausschließlich aktive Feststellungen angezeigt, die den Status *Neu* (ungeprüft) oder *Zur Kenntnis genommen* besitzen. Bereits behobene oder stummgeschaltete Befunde erscheinen nicht in der Liste.
3. **Zulässige Prüfungsarten:** Fehler aus automatisierten Benutzerabläufen (Reisen) können nicht über pauschale Regeln stummgeschaltet werden und stehen daher in der Auswahlliste nicht zur Verfügung.

## Nach der Übernahme anpassen

Nachdem Sie eine Feststellung angeklickt haben, sind die Felder im Formular vorausgefüllt. Sie können diese vor dem Speichern jederzeit weiter verfeinern:

* Ergänzen Sie bei Bedarf ein **Betreff-Muster** (z. B. eine verlinkte Ziel-URL bei toten Links).
* Geben Sie eine nachvollziehbare **Begründung** an.
* Passen Sie das Gültigkeitsdatum (**Stumm bis**) nach Ihren Anforderungen an (maximal 365 Tage).
