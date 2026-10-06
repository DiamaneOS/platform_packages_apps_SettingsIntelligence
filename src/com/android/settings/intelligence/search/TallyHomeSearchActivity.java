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

import com.android.settings.intelligence.search.indexing.IndexDatabaseHelper;
import com.android.settings.intelligence.search.query.CursorToSearchResultConverter;

/**
 * Opens a Settings page that Home's search found ({@link TallyHomeSearchProvider}), as a tap on
 * the same result in Settings search does ({@link IntentSearchViewHolder}): the page's own intent
 * from the index, started by Settings search, so Settings' result trampoline accepts it. It looks
 * the page up by the key and title the provider gave, among enabled pages only, and opens nothing
 * else. Nothing is recorded (Settings search's saved queries stay as they were).
 *
 * <p>Only holders of {@link TallyHomeSearchContract#PERMISSION} (signature) can start it. It shows
 * nothing and finishes at once; the page opens in Settings' own task.
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
        if (key == null || title == null) {
            return;
        }
        final Intent page = findPage(key, title);
        if (page == null) {
            Log.w(TAG, "No such page");
            return;
        }
        // Settings' own task, not this one, which leaves no trace in Recents.
        page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().queryIntentActivities(page, 0 /* flags */).isEmpty()) {
            Log.w(TAG, "The page cannot be opened");
            return;
        }
        try {
            // For a result, as Settings search: the trampoline checks who started it.
            startActivityForResult(page, REQUEST_CODE_NO_OP);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "The page cannot be opened", e);
        }
    }

    /** The intent of the enabled page with [key] and [title], or null. */
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
