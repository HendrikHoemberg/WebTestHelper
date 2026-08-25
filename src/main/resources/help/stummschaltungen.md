# Stummschaltungen und Regeln

Mit einer Stummschaltung können Sie bekannte Mängel vorübergehend aus Berichten und Benachrichtigungen ausblenden, ohne sie zu vergessen. Jede Stummschaltung ist befristet und erfordert eine nachvollziehbare Begründung.

## Wann Stummschalten sinnvoll ist

Eine Stummschaltung empfiehlt sich bei bekannten oder erwarteten Problemen, die nicht sofort behoben werden können:

* Ein bestimmter Bereich der Website wird aktuell umgebaut (z. B. */archiv/*).
* Ein externer Dienst drosselt automatisierte Anfragen (z. B. LinkedIn-Profile).
* Ein bekanntes Drittanbieter-Skript erzeugt vorübergehend Fehler, bis der Anbieter ein Update bereitstellt.

## Einzelfall oder Regel

Sie können Mängel auf zwei Wegen stummschalten:

* **Einzelne Feststellung:** Direkt in der Detailansicht oder über die Mehrfachauswahl der Befundliste. Dies gilt genau für die ausgewählten Fundstellen.
* **Stummschaltungsregel:** Gilt für alle bestehenden und künftigen Befunde, die den Kriterien der Regel entsprechen (Prüfungsart, Betreff oder Fundort). Eine Regel kann für eine einzelne Website oder global für alle Websites (durch Administratoren) gelten.

## Muster und Platzhalter

In Stummschaltungsregeln können Sie Muster für den Betreff (z. B. die verlinkte Zieladresse) und den Fundort (die Seite, auf der der Befund auftritt) angeben:

* Der Stern `*` dient als Platzhalter für beliebige Zeichenfolgen (z. B. `*linkedin.com*` oder `https://example.com/archiv/*`).
* Ohne `*` erfolgt ein exakter Abgleich.
* Werden Prüfungsart, Betreff- und Fundortmuster kombiniert, müssen alle angegebenen Kriterien gleichzeitig zutreffen (*Und-Verknüpfung*).

## Warum die Befristung verpflichtend ist

Unbefristete Stummschaltungen führen unweigerlich dazu, dass Mängel in Vergessenheit geraten und das Monitoring erblindet. Daher verlangt jede Stummschaltung ein konkretes Ablaufdatum (maximal 365 Tage).

## Was passiert nach Ablauf der Frist

Ein automatischer stündlicher Abgleich prüft abgelaufene Stummschaltungen:

* Nach Erreichen des Enddatums wird die Stummschaltung automatisch aufgehoben.
* Die Feststellung erscheint in folgenden Prüfberichten wieder als „Unverändert offen“, sofern das Problem weiterhin besteht.
* Die damalige Begründung bleibt erhalten und wird zusammen mit der Historie angezeigt, damit Kolleginnen und Kollegen nachvollziehen können, warum der Befund seinerzeit stummgeschaltet wurde.
