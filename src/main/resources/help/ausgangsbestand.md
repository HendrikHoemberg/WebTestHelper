# Ausgangsbestand festlegen

Die Übernahme als Ausgangsbestand stuft alle aktuell ungeprüften Feststellungen eines Prüflaufs auf einmal als „Zur Kenntnis genommen“ ein. Dadurch wandern sie in den Bereich „Bekannt“ und überladen künftige Prüfberichte nicht mehr als Neumeldungen.

## Warum der erste Prüflauf viele Befunde liefert

Wird eine bestehende Website zum ersten Mal umfassend geprüft, meldet das System oft dutzende oder hunderte Befunde auf einmal. Darunter befinden sich historische Eigenheiten der Website, bewusst tolerierte Altlasten und unkritische Abweichungen. Eine manuelle Einzelfallprüfung aller Erstbefunde ist im Arbeitsalltag meist nicht praktikabel.

## Warum der zweite Prüflauf entscheidend ist

Das Differenzmodell des WebTestHelpers entfaltet seinen vollen Nutzen ab dem zweiten Prüflauf:

* Durch das Setzen des **Ausgangsbestands** werden alle Erstbefunde quittiert.
* Künftige Prüfläufe vergleichen den aktuellen Zustand mit diesem Ausgangsbestand.
* Neue Fehler oder wiederkehrende Probleme (*Regressionen*) stechen in künftigen Berichten sofort hervor, ohne im Grundrauschen historischer Befunde unterzugehen.
