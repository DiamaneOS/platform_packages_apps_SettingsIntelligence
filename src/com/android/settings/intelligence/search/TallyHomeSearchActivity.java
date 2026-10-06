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
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.Future;

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
 * <p>The lookup runs on Settings search's executor, never on the main thread (the installed-apps
 * task reads every app's label). Meanwhile this activity is a transparent window; it opens the
 * result only if it is still in front, and gives up after {@link #MAX_LOOKUP_MS}.
 *
 * <p>Only holders of {@link TallyHomeSearchContract#PERMISSION} (signature) can start it. It shows
 * nothing and finishes as soon as the result is open; the result opens in Settings' own task.
 */
public class TallyHomeSearchActivity extends Activity {

    private static final String TAG = "TallyHomeSearch";

    /** As IntentSearchViewHolder.REQUEST_CODE_NO_OP. */
    private static final int REQUEST_CODE_NO_OP = 0;

    /** The longest a lookup may take before this activity gives up and finishes. */
    private static final long MAX_LOOKUP_MS = 5_000;

    /** A found result: the intent to start, and whether to start it for a result. */
    private static final class Target {
        final Intent intent;
        final boolean forResult;

        Target(Intent intent, boolean forResult) {
            this.intent = intent;
            this.forResult = forResult;
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Future<?> mLookup;
    private boolean mResumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent request = getIntent();
        final String key = TallyHomeSearchContract.cleanReference(
                request.getStringExtra(TallyHomeSearchContract.EXTRA_KEY));
        final String title = TallyHomeSearchContract.cleanReference(
                request.getStringExtra(TallyHomeSearchContract.EXTRA_TITLE));
        final String kind = TallyHomeSearchContract.cleanKind(
                request.getStringExtra(TallyHomeSearchContract.EXTRA_KIND));
        if (savedInstanceState != null || key == null || title == null || kind == null) {
            finish();
            return;
        }
        final Context context = getApplicationContext();
        mHandler.postDelayed(this::finish, MAX_LOOKUP_MS);
        mLookup = FeatureFactory.get(context).searchFeatureProvider().getExecutorService()
                .submit(() -> {
                    final Target target = find(context, kind, key, title);
                    mHandler.post(() -> open(target));
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mLookup != null) {
            mLookup.cancel(true /* mayInterruptIfRunning */);
        }
        super.onDestroy();
    }

    /** On the main thread: opens {@code target} if this activity is still in front, then finishes. */
    private void open(Target target) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (target == null) {
            Log.w(TAG, "No such result");
        } else if (!mResumed) {
            // The user moved on (Home, another app) while the result was looked up.
            Log.w(TAG, "Not opened: no longer in front");
        } else {
            try {
                if (target.forResult) {
                    // For a result, as Settings search: the trampoline checks who started it.
                    startActivityForResult(target.intent, REQUEST_CODE_NO_OP);
                } else {
                    startActivity(target.intent);
                }
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "The result cannot be opened", e);
            }
        }
        finish();
    }

    /**
     * On a background thread: the result of {@code kind} with {@code key} and {@code title}, ready
     * to start, or null.
     */
    private static Target find(Context context, String kind, String key, String title) {
        final Intent intent;
        boolean forResult = true;
        if (KIND_PAGE.equals(kind)) {
            intent = findPage(context, key, title);
        } else {
            final SearchResult result = findResult(context, kind, key, title);
            intent = result == null ? null : new Intent(result.payload.getIntent());
            // As IntentSearchViewHolder starts an app's app info.
            forResult = !(result instanceof AppSearchResult)
                    || SEARCH_RESULT_TRAMPOLINE_ACTION.equals(intent.getAction());
        }
        if (intent == null) {
            return null;
        }
        // Settings' own task, not this one, which leaves no trace in Recents.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().queryIntentActivities(intent, 0 /* flags */).isEmpty()) {
            Log.w(TAG, "The result cannot be opened");
            return null;
        }
        return new Target(intent, forResult);
    }

    /**
     * The result of {@code kind} (not a page) with {@code key} and {@code title} that Settings
     * search shows when the title is typed, or null.
     */
    private static SearchResult findResult(Context context, String kind, String key,
            String title) {
        final SiteMapManager siteMap =
                FeatureFactory.get(context).searchFeatureProvider().getSiteMapManager();
        // As Settings search cleans its words (SearchFeatureProviderImpl.cleanQuery).
        final String query = Locale.getDefault().equals(Locale.JAPAN)
                ? IndexData.normalizeJapaneseString(title) : title;
        final SearchQueryTask task;
        switch (kind) {
            case KIND_APP:
                task = InstalledAppResultTask.newTask(context, siteMap, query);
                break;
            case KIND_ACCESSIBILITY:
                task = AccessibilityServiceResultTask.newTask(context, siteMap, query);
                break;
            case KIND_INPUT:
                task = InputDeviceResultTask.newTask(context, siteMap, query);
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
    private static Intent findPage(Context context, String key, String title) {
        try {
            final SQLiteDatabase database =
                    IndexDatabaseHelper.getInstance(context).getReadableDatabase();
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
