# Slack & Teams Webhook-Integration

WebTestHelper kann automatisierte Benachrichtigungen über abgeschlossene Prüfläufe und festgestellte Mängel direkt in Ihre Team-Chat-Kanäle (wie Slack, Microsoft Teams oder Mattermost) senden.

## Funktionsweise

Sobald ein Prüflauf beendet wird und neue oder kritische Feststellungen vorliegen, sendet das System eine formatierte Nachricht an den hinterlegten Webhook-Endpunkt. Dies ermöglicht es Entwicklungsteams, Agenturen oder Website-Betreibern, sofort und ohne ständiges Prüfen des E-Mail-Postfachs auf Probleme zu reagieren.

## Einrichtung für Slack

1. Öffnen Sie in Ihrem Slack-Workspace die Seite **Slack App Directory** und suchen Sie nach **Incoming WebHooks**.
2. Klicken Sie auf **Zu Slack hinzufügen** (Add to Slack) und wählen Sie den gewünschten Kanal (z. B. `#website-monitoring` oder `#web-alerts`).
3. Kopieren Sie die angezeigte **Webhook-URL** (beginnt mit `https://hooks.slack.com/services/...`).
4. Fügen Sie diese URL in den WebTestHelper-Einstellungen unter **Slack & Teams Webhook** ein und aktivieren Sie die Option.
5. Klicken Sie auf **Webhook testen**, um eine Testnachricht an Ihren Slack-Kanal zu senden.

## Einrichtung für Microsoft Teams

1. Navigieren Sie in Microsoft Teams zum gewünschten Kanal, klicken Sie auf die drei Punkte (`...`) und wählen Sie **Workflows** (oder **Connectors**).
2. Wählen Sie die Vorlage **Post to a channel when a webhook request is received** (In Kanal posten, wenn eine Webhook-Anfrage empfangen wird).
3. Vergeben Sie einen Namen (z. B. *WebTestHelper Alerts*) und schließen Sie die Einrichtung ab.
4. Kopieren Sie die generierte Webhook-URL und tragen Sie sie in die WebTestHelper-Einstellungen ein.
5. Überprüfen Sie den Empfang über die Schaltfläche **Webhook testen**.

## Fehlersuche & Hinweise

* **Test-Nachricht schlägt fehl**: Überprüfen Sie, ob die Webhook-URL vollständig und ohne Leerzeichen eingegeben wurde.
* **Firewall / Netzwerk**: Wenn der WebTestHelper lokal oder hinter einer Unternehmens-Firewall betrieben wird, muss ausgehender HTTPS-Verkehr (Port 443) zu den Servern von Slack (`hooks.slack.com`) bzw. Microsoft Teams gestattet sein.
