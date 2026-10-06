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

import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_ACCESSIBILITY;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_APP;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_INPUT;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_PAGE;
import static com.android.settings.intelligence.search.indexing.DatabaseIndexingUtils.SEARCH_RESULT_TRAMPOLINE_ACTION;
import static com.android.settings.intelligence.search.indexing.IndexDatabaseHelper.IndexColumns.DATA_KEY_REF;
import static com.android.settings.intelligence.search.indexing.IndexDatabaseHelper.IndexColumns.DATA_TITLE;
import static com.android.settings.intelligence.search.indexing.IndexDatabaseHelper.IndexColumns.ENABLED;
import static com.android.settings.intelligence.search.indexing.IndexDatabaseHelper.IndexColumns.PAYLOAD;
import static com.android.settings.intelligence.search.indexing.IndexDatabaseHelper.IndexColumns.PAYLOAD_TYPE;
import static com.android.settings.intelligence.search.indexing.IndexDatabaseHelper.Tables.TABLE_PREFS_INDEX;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.util.Log;

import com.android.settings.intelligence.overlay.FeatureFactory;
import com.android.settings.intelligence.search.indexing.IndexData;
import com.android.settings.intelligence.search.indexing.IndexDatabaseHelper;
import com.android.settings.intelligence.search.query.AccessibilityServiceResultTask;
import com.android.settings.intelligence.search.query.CursorToSearchResultConverter;
import com.android.settings.intelligence.search.query.InputDeviceResultTask;
import com.android.settings.intelligence.search.query.InstalledAppResultTask;
import com.android.settings.intelligence.search.query.SearchQueryTask;
import com.android.settings.intelligence.search.sitemap.SiteMapManager;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Opens a Settings result that Home's search found ({@link TallyHomeSearchProvider}), as a tap on
 * the same result in Settings search does ({@link IntentSearchViewHolder}): the result's own
 * intent, started by Settings search, so Settings' result trampoline accepts it. It finds the
 * result again by the kind, key and title the provider gave, and opens nothing else:
 * <ul>
 * <li>a page among the index's enabled pages;
 * <li>an app's app info, an accessibility service or a keyboard by running Settings search's own
 * task for that kind with the title as the words, so the same rules decide whether it is shown,
 * and taking the result with the same key and title.
 * </ul>
 * Nothing is recorded (Settings search's saved queries stay as they were).
 *
 * <p>Only holders of {@link TallyHomeSearchContract#PERMISSION} (signature) can start it. It shows
 * nothing and finishes at once; the result opens in Settings' own task.
 */
public class TallyHomeSearchActivity extends Activity {

    private static final String TAG = "TallyHomeSearch";

    /** As IntentSearchViewHolder.REQUEST_CODE_NO_OP. */
    private static final int REQUEST_CODE_NO_OP = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            open(getIntent());
        }
        finish();
    }

    private void open(Intent request) {
        final String key = TallyHomeSearchContract.cleanReference(
                request.getStringExtra(TallyHomeSearchContract.EXTRA_KEY));
        final String title = TallyHomeSearchContract.cleanReference(
                request.getStringExtra(TallyHomeSearchContract.EXTRA_TITLE));
        final String kind = TallyHomeSearchContract.cleanKind(
                request.getStringExtra(TallyHomeSearchContract.EXTRA_KIND));
        if (key == null || title == null || kind == null) {
            return;
        }
        final Intent target;
        boolean forResult = true;
        if (KIND_PAGE.equals(kind)) {
            target = findPage(key, title);
        } else {
            final SearchResult result = findResult(kind, key, title);
            target = result == null ? null : new Intent(result.payload.getIntent());
            // As IntentSearchViewHolder starts an app's app info.
            forResult = !(result instanceof AppSearchResult)
                    || SEARCH_RESULT_TRAMPOLINE_ACTION.equals(target.getAction());
        }
        if (target == null) {
            Log.w(TAG, "No such result");
            return;
        }
        // Settings' own task, not this one, which leaves no trace in Recents.
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().queryIntentActivities(target, 0 /* flags */).isEmpty()) {
            Log.w(TAG, "The result cannot be opened");
            return;
        }
        try {
            if (forResult) {
                // For a result, as Settings search: the trampoline checks who started it.
                startActivityForResult(target, REQUEST_CODE_NO_OP);
            } else {
                startActivity(target);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "The result cannot be opened", e);
        }
    }

    /**
     * The result of {@code kind} (not a page) with {@code key} and {@code title} that Settings
     * search shows when the title is typed, or null.
     */
    private SearchResult findResult(String kind, String key, String title) {
        final SiteMapManager siteMap =
                FeatureFactory.get(this).searchFeatureProvider().getSiteMapManager();
        // As Settings search cleans its words (SearchFeatureProviderImpl.cleanQuery).
        final String query = Locale.getDefault().equals(Locale.JAPAN)
                ? IndexData.normalizeJapaneseString(title) : title;
        final SearchQueryTask task;
        switch (kind) {
            case KIND_APP:
                task = InstalledAppResultTask.newTask(this, siteMap, query);
                break;
            case KIND_ACCESSIBILITY:
                task = AccessibilityServiceResultTask.newTask(this, siteMap, query);
                break;
            case KIND_INPUT:
                task = InputDeviceResultTask.newTask(this, siteMap, query);
                break;
            default:
                return null;
        }
        task.run();
        try {
            final List<? extends SearchResult> results = task.get();
            for (SearchResult result : results) {
                if (result != null && key.equals(result.dataKey) && result.title != null
                        && title.contentEquals(result.title) && result.payload != null
                        && result.payload.getIntent() != null) {
                    return result;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            Log.w(TAG, "Cannot look the result up");
        }
        return null;
    }

    /** The intent of the enabled page with {@code key} and {@code title}, or null. */
    private Intent findPage(String key, String title) {
        try {
            final SQLiteDatabase database =
                    IndexDatabaseHelper.getInstance(this).getReadableDatabase();
            try (Cursor cursor = database.query(TABLE_PREFS_INDEX,
                    new String[] {PAYLOAD_TYPE, PAYLOAD},
                    DATA_KEY_REF + " = ? AND " + DATA_TITLE + " = ? AND " + ENABLED + " = 1",
                    new String[] {key, title}, null /* groupBy */, null /* having */,
                    null /* orderBy */, "1" /* limit */)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                final ResultPayload payload = CursorToSearchResultConverter.getUnmarshalledPayload(
                        cursor.getBlob(1), cursor.getInt(0));
                return payload == null ? null : payload.getIntent();
            }
        } catch (SQLiteException e) {
            Log.w(TAG, "Cannot read the index", e);
            return null;
        }
    }
}
