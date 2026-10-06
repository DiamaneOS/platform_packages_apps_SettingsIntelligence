/*
 * Copyright (C) 2026 The DiamaneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.settings.intelligence.search;

/**
 * What Home's search (Launcher3, the only holder of {@link #PERMISSION}) and Settings search share:
 * the read-only provider of Settings results ({@link TallyHomeSearchProvider}) and the activity
 * that opens one ({@link TallyHomeSearchActivity}). Launcher3 keeps a copy of these names.
 *
 * <p>The results are those Settings search shows for the same words, of each {@code KIND_}: Settings
 * pages from the index, and installed apps (their app info), accessibility services and keyboards.
 *
 * <p>The typed words travel in the query's arguments, never in a URI or an intent, so that no
 * system log line (a permission denial names the URI) carries them.
 */
public final class TallyHomeSearchContract {

    /** Signature permission that guards the provider and the activity. */
    public static final String PERMISSION = "de.diamaneos.permission.QUERY_SETTINGS_SEARCH";

    /** The provider's authority. */
    public static final String AUTHORITY = "de.diamaneos.settingssearch";

    /** The one path the provider answers: Settings results (pages, and the kinds below). */
    public static final String PATH_PAGES = "pages";

    /** Query argument: the typed words (a String). */
    public static final String ARG_QUERY = "de.diamaneos.settingssearch.QUERY";

    /**
     * Query argument: true on the first query after Home's search opens, which refreshes what
     * pages are available, as opening Settings search does.
     */
    public static final String ARG_FRESH = "de.diamaneos.settingssearch.FRESH";

    /** Columns of a result row. */
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_KEY = "key";
    public static final String COLUMN_TITLE = "title";
    /** The page the result sits on (the last breadcrumb that is not the title), or null. */
    public static final String COLUMN_PARENT = "parent";
    /** What the result is: one of the {@code KIND_} values. */
    public static final String COLUMN_KIND = "kind";

    public static final String[] COLUMNS =
            {COLUMN_ID, COLUMN_KEY, COLUMN_TITLE, COLUMN_PARENT, COLUMN_KIND};

    /** A Settings page from the index; its key is the index row's. */
    public static final String KIND_PAGE = "page";
    /** An installed app's app info; its key is the package name. */
    public static final String KIND_APP = "app";
    /** An accessibility service; its key is the service's component name. */
    public static final String KIND_ACCESSIBILITY = "accessibility";
    /** A physical keyboard (its key is the device's name) or an on-screen one (its component). */
    public static final String KIND_INPUT = "input";

    /**
     * Extras of the activity's intent: a result row's key, title and kind, as the provider gave
     * them. Without a kind, the row is a page.
     */
    public static final String EXTRA_KEY = "de.diamaneos.settingssearch.extra.KEY";
    public static final String EXTRA_TITLE = "de.diamaneos.settingssearch.extra.TITLE";
    public static final String EXTRA_KIND = "de.diamaneos.settingssearch.extra.KIND";

    /** The longest query answered; a longer one gets no rows. */
    public static final int MAX_QUERY_LENGTH = 100;

    /** The most rows a query returns. */
    public static final int MAX_ROWS = 12;

    /** The longest key or title the activity looks up. */
    public static final int MAX_REFERENCE_LENGTH = 512;

    private TallyHomeSearchContract() {}

    /**
     * The typed words as the index is searched: control and format characters become spaces,
     * runs of spaces one, trimmed. Null when nothing is left or it is longer than
     * {@link #MAX_QUERY_LENGTH}.
     */
    public static String cleanQuery(String raw) {
        if (raw == null || raw.length() > MAX_QUERY_LENGTH * 4) {
            return null;
        }
        final StringBuilder out = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); ) {
            final int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isISOControl(cp)
                    || Character.getType(cp) == Character.FORMAT) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(cp);
        }
        if (out.length() == 0 || out.length() > MAX_QUERY_LENGTH) {
            return null;
        }
        return out.toString();
    }

    /**
     * A key or title for the activity's lookup: null when missing, empty, longer than
     * {@link #MAX_REFERENCE_LENGTH} or holding a control character.
     */
    public static String cleanReference(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_REFERENCE_LENGTH) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                return null;
            }
        }
        return raw;
    }

    /**
     * The kind of a row the activity is asked to open: {@link #KIND_PAGE} when none is given, the
     * kind when it is one of the {@code KIND_} values, otherwise null (nothing opens).
     */
    public static String cleanKind(String raw) {
        if (raw == null) {
            return KIND_PAGE;
        }
        switch (raw) {
            case KIND_PAGE:
            case KIND_APP:
            case KIND_ACCESSIBILITY:
            case KIND_INPUT:
                return raw;
            default:
                return null;
        }
    }

    /**
     * The page a result sits on, from its breadcrumbs (root first): the last one that is not the
     * result's own title, or null.
     */
    public static String parentOf(java.util.List<String> breadcrumbs, CharSequence title) {
        if (breadcrumbs == null) {
            return null;
        }
        final String own = title == null ? null : title.toString();
        for (int i = breadcrumbs.size() - 1; i >= 0; i--) {
            final String crumb = breadcrumbs.get(i);
            if (crumb != null && !crumb.isEmpty() && !crumb.equals(own)) {
                return crumb;
            }
        }
        return null;
    }
}
