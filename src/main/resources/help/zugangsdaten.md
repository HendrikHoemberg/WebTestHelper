# Zugangsdaten für Abläufe

Zugangsdaten ermöglichen es automatisierten Prüfabläufen, sich auf der Website anzumelden oder geschützte Bereiche zu testen.

## Sicher verschlüsselt

Alle Passwörter werden mit einem sicheren Schlüssel verschlüsselt gespeichert, der in der Schlüsseldatei (`/data/keyfile`) auf dem Server abgelegt ist. Aus Sicherheitsgründen wird ein gespeichertes Passwort im System nie wieder im Klartext angezeigt. Sie können ein bestehendes Passwort jederzeit durch die Eingabe eines neuen Passworts überschreiben oder das Feld beim Speichern leer lassen, um das bisherige Passwort unverändert beizubehalten.

## Zugangsdaten in Abläufen verwenden

In Prüfabläufen verweisen Sie über Platzhalter auf die hinterlegten Daten:
* `{{cred.NAME.username}}` für den Benutzernamen (z. B. `{{cred.login.username}}`)
* `{{cred.NAME.password}}` für das Passwort (z. B. `{{cred.login.password}}`)

Beim Ausführen des Ablaufs löst WebTestHelper diese Verweise automatisch auf. In Berichten und Protokollen werden Passwörter stets geschwärzt.

## Fester Name

Der gewählte Name der Zugangsdaten (z. B. `login`) kann nachträglich nicht geändert werden, da hinterlegte Abläufe genau über diesen Namen auf die Zugangsdaten verweisen. Möchten Sie einen anderen Namen verwenden, legen Sie bitte neue Zugangsdaten an.

## Löschen von Zugangsdaten und Websites

Zugangsdaten können einzeln über die Schaltfläche „Löschen“ entfernt werden. Wird eine Website gelöscht, werden auch alle zugehörigen Zugangsdaten automatisch entfernt.

## Hinweis „Lässt sich nicht mehr entschlüsseln“

Erscheint bei einem Eintrag der Hinweis, dass sich das Passwort nicht mehr entschlüsseln lässt, passt der hinterlegte Datensatz nicht mehr zur aktuellen Schlüsseldatei auf dem Server — beispielsweise wenn die Datei `/data/keyfile` neu erzeugt oder ausgetauscht wurde. Tragen Sie in diesem Fall das Passwort im Bearbeitungsformular einfach neu ein und speichern Sie die Änderung.
