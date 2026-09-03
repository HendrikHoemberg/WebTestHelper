// Onboarding-Tutorial (Driver.js)
(function () {
    function initTour() {
        const configEl = document.getElementById('wth-tutorial-config');
        if (!configEl) {
            return;
        }

        const autoStart = configEl.getAttribute('data-auto-start') === 'true';
        const isAdmin = configEl.getAttribute('data-is-admin') === 'true';
        const finishUrl = configEl.getAttribute('data-finish-url') || '/tutorial/abschliessen';

        const i18n = {
            next: configEl.getAttribute('data-i18n-next') || 'Weiter',
            prev: configEl.getAttribute('data-i18n-prev') || 'Zurück',
            close: configEl.getAttribute('data-i18n-close') || 'Tour beenden',
            done: configEl.getAttribute('data-i18n-done') || 'Verstanden, loslegen!',
            s1Title: configEl.getAttribute('data-s1-title') || 'Willkommen bei WebTestHelper',
            s1Text: configEl.getAttribute('data-s1-text') || 'WebTestHelper überwacht Ihre Websites automatisch.',
            s2Title: configEl.getAttribute('data-s2-title') || 'Die Hauptnavigation',
            s2Text: configEl.getAttribute('data-s2-text') || 'Über die linke Leiste steuern Sie alles.',
            s3Title: configEl.getAttribute('data-s3-title') || 'Websites aufrufen',
            s3Text: configEl.getAttribute('data-s3-text') || 'Hier sind alle überwachten Webauftritte hinterlegt.',
            s4AdminTitle: configEl.getAttribute('data-s4-admin-title') || 'Neue Website anlegen',
            s4AdminText: configEl.getAttribute('data-s4-admin-text') || 'Hier registrieren Sie neue Websites.',
            s4UserTitle: configEl.getAttribute('data-s4-user-title') || 'Websites einsehen',
            s4UserText: configEl.getAttribute('data-s4-user-text') || 'In dieser Tabelle sehen Sie Ihre Websites.',
            s5Title: configEl.getAttribute('data-s5-title') || 'Regeln & Handbuch',
            s5Text: configEl.getAttribute('data-s5-text') || 'Erwartete Befunde stummschalten und Handbuch aufrufen.',
            s6Title: configEl.getAttribute('data-s6-title') || 'Alles bereit!',
            s6Text: configEl.getAttribute('data-s6-text') || 'Sie kennen nun die wichtigsten Bereiche.'
        };

        const driverFactory = (window.driver && window.driver.js && window.driver.js.driver)
            || (window.driver && window.driver.driver)
            || window.driver;

        if (typeof driverFactory !== 'function') {
            return;
        }

        function markTutorialComplete() {
            sessionStorage.removeItem('wth_tour_step');
            const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
            const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
            const headers = { 'Content-Type': 'application/json' };
            if (token && header) {
                headers[header] = token;
            }
            fetch(finishUrl, {
                method: 'POST',
                headers: headers
            }).catch(function () {
                // Ignore network failure when marking complete
            });
        }

        const path = window.location.pathname;
        const tourStep = sessionStorage.getItem('wth_tour_step');

        const driverInstance = driverFactory({
            showProgress: true,
            animate: true,
            allowClose: true,
            nextBtnText: i18n.next,
            prevBtnText: i18n.prev,
            doneBtnText: i18n.done,
            progressText: '{{current}} / {{total}}',
            onDestroyStarted: function () {
                markTutorialComplete();
                driverInstance.destroy();
            }
        });

        // Phase 1: Dashboard Tour (Steps 1 - 3)
        if (path === '/' || path === '' || path === '/index') {
            if (autoStart || tourStep === 'dashboard') {
                const steps = [
                    {
                        element: document.querySelector('.brand-title-text') || document.querySelector('.sidebar-brand-box') || 'body',
                        popover: {
                            title: i18n.s1Title,
                            description: i18n.s1Text,
                            side: 'bottom',
                            align: 'start'
                        }
                    },
                    {
                        element: document.querySelector('.sidebar-nav-scroll') || '.app-sidebar',
                        popover: {
                            title: i18n.s2Title,
                            description: i18n.s2Text,
                            side: 'right',
                            align: 'start'
                        }
                    },
                    {
                        element: document.querySelector("a[href='/websites']") || '.sidebar-nav-scroll',
                        popover: {
                            title: i18n.s3Title,
                            description: i18n.s3Text,
                            side: 'right',
                            align: 'start',
                            onNextClick: function () {
                                sessionStorage.setItem('wth_tour_step', 'websites');
                                window.location.href = '/websites';
                            }
                        }
                    }
                ];

                driverInstance.setSteps(steps);
                driverInstance.drive(0);
            }
        }
        // Phase 2: Websites Tour (Steps 4 - 6)
        else if (path.indexOf('/websites') === 0 && (tourStep === 'websites' || (autoStart && tourStep === 'websites'))) {
            const adminButton = document.querySelector("a[href='/websites/neu']");
            const step4Target = (isAdmin && adminButton)
                ? adminButton
                : (document.querySelector('.tabelle-container') || document.querySelector('.card-box') || document.querySelector('.page-main-title') || 'body');

            const step4Title = (isAdmin && adminButton) ? i18n.s4AdminTitle : i18n.s4UserTitle;
            const step4Text = (isAdmin && adminButton) ? i18n.s4AdminText : i18n.s4UserText;

            const helpNav = document.querySelector("a[href='/hilfe']") || document.querySelector("a[href='/stummschaltungen']") || '.sidebar-nav-scroll';

            const steps = [
                {
                    element: step4Target,
                    popover: {
                        title: step4Title,
                        description: step4Text,
                        side: 'bottom',
                        align: 'start'
                    }
                },
                {
                    element: helpNav,
                    popover: {
                        title: i18n.s5Title,
                        description: i18n.s5Text,
                        side: 'right',
                        align: 'start'
                    }
                },
                {
                    element: document.querySelector('.sidebar-user-footer') || 'body',
                    popover: {
                        title: i18n.s6Title,
                        description: i18n.s6Text,
                        side: 'top',
                        align: 'start',
                        nextBtnText: i18n.done,
                        onNextClick: function () {
                            markTutorialComplete();
                            driverInstance.destroy();
                        }
                    }
                }
            ];

            driverInstance.setSteps(steps);
            driverInstance.drive(0);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initTour);
    } else {
        initTour();
    }
})();
