package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-check configuration form on the site detail page: which checks are active and
 * which severity each overrides. The map keys and the {@code aktiv} values are internal
 * {@link CheckType} names — machine-only values that are never rendered as labels (§13.1),
 * same contract as {@link SetupForm}.
 *
 * <p>Both collections stay mutable on purpose: Spring binds {@code aktiv} and the indexed
 * {@code schweregrad[...]} params by growing what {@code getAktiv()}/{@code getSchweregrad()}
 * returned, and an immutable collection would answer every submit with an
 * {@code UnsupportedOperationException}.
 */
public class CheckSettingsForm {

    private List<CheckType> aktiv = new ArrayList<>();

    private Map<String, String> schweregrad = new HashMap<>();

    public List<CheckType> getAktiv() {
        return aktiv;
    }

    public void setAktiv(List<CheckType> aktiv) {
        this.aktiv = aktiv != null ? new ArrayList<>(aktiv) : new ArrayList<>();
    }

    public Map<String, String> getSchweregrad() {
        return schweregrad;
    }

    public void setSchweregrad(Map<String, String> schweregrad) {
        this.schweregrad = schweregrad != null ? new HashMap<>(schweregrad) : new HashMap<>();
    }
}
