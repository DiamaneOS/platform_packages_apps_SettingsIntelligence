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

import static com.android.settings.intelligence.search.TallyHomeSearchContract.ARG_FRESH;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.ARG_QUERY;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.COLUMNS;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.MAX_ROWS;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.PATH_PAGES;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.android.settings.intelligence.overlay.FeatureFactory;
import com.android.settings.intelligence.search.indexing.IndexData;
import com.android.settings.intelligence.search.query.DatabaseResultTask;
import com.android.settings.intelligence.search.query.SearchQueryTask;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Settings pages for Home's search, read-only: the rows Settings search shows for the same words
 * from the same index ({@link DatabaseResultTask}: enabled pages only, so what the device or the
 * user cannot use stays out), with no summaries (as Settings search shows them), no icons and no
 * intents. A page opens through {@link TallyHomeSearchActivity}.
 *
 * <p>Only holders of {@link TallyHomeSearchContract#PERMISSION} (signature) reach it, only from
 * this user. It keeps nothing: no saved query, no log of the words.
 */
public class TallyHomeSearchProvider extends ContentProvider {

    private static final String TAG = "TallyHomeSearch";

    /** How long a query waits for the index to be brought up to date. */
    private static final long MAX_INDEX_WAIT_MS = 10_000;
    /** How often a waiting query checks whether it was cancelled. */
    private static final long WAIT_SLICE_MS = 100;
    /** A fresh query refreshes the index at most this often. */
    private static final long MIN_REFRESH_INTERVAL_MS = 5_000;

    private static final Object sLock = new Object();
    /** Counts down when the running refresh is done; null when none runs. */
    private static CountDownLatch sRefresh;
    /** When the last refresh started (elapsed realtime), 0 before the first. */
    private static long sLastRefreshStart;

    private Handler mMainHandler;

    @Override
    public boolean onCreate() {
        mMainHandler = new Handler(Looper.getMainLooper());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) {
        final MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (!isPagesUri(uri) || !Binder.getCallingUserHandle().equals(Process.myUserHandle())) {
            return cursor;
        }
        String query = TallyHomeSearchContract.cleanQuery(
                queryArgs == null ? null : queryArgs.getString(ARG_QUERY));
        if (query == null) {
            return cursor;
        }
        // As Settings search cleans its query (SearchFeatureProviderImpl.cleanQuery).
        if (Locale.getDefault().equals(Locale.JAPAN)) {
            query = IndexData.normalizeJapaneseString(query);
        }
        final boolean fresh = queryArgs.getBoolean(ARG_FRESH, false);

        final long identity = Binder.clearCallingIdentity();
        try {
            awaitIndex(fresh, cancellationSignal);
            final Context context = getContext();
            final SearchQueryTask task = DatabaseResultTask.newTask(context,
                    FeatureFactory.get(context).searchFeatureProvider().getSiteMapManager(),
                    query);
            task.run();
            final List<? extends SearchResult> results = task.get();
            int id = 0;
            for (SearchResult result : results) {
                if (id >= MAX_ROWS) {
                    break;
                }
                if (cancellationSignal != null) {
                    cancellationSignal.throwIfCanceled();
                }
                if (result == null || TextUtils.isEmpty(result.title)
                        || TextUtils.isEmpty(result.dataKey) || result.payload == null
                        || result.payload.getIntent() == null) {
                    continue;
                }
                cursor.addRow(new Object[] {
                        id++,
                        result.dataKey,
                        result.title.toString(),
                        TallyHomeSearchContract.parentOf(result.breadcrumbs, result.title),
                });
            }
        } catch (InterruptedException | ExecutionException e) {
            // Never the words: only that the query failed.
            Log.w(TAG, "Settings pages query failed");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return cursor;
    }

    private static boolean isPagesUri(Uri uri) {
        final List<String> segments = uri == null ? null : uri.getPathSegments();
        return segments != null && segments.size() == 1 && PATH_PAGES.equals(segments.get(0));
    }

    /**
     * Brings the index up to date before a query, as Settings search does when it opens: on the
     * first query since this process started, and on a fresh one (Home's search just opened) at
     * most every {@link #MIN_REFRESH_INTERVAL_MS}. Waits for a refresh that runs, at most
     * {@link #MAX_INDEX_WAIT_MS}; the refresh runs on Settings search's own serial indexing task,
     * so the two never index at once.
     */
    private void awaitIndex(boolean fresh, CancellationSignal signal)
            throws InterruptedException {
        final CountDownLatch refresh;
        synchronized (sLock) {
            final long now = SystemClock.elapsedRealtime();
            if (sRefresh == null && (sLastRefreshStart == 0
                    || (fresh && now - sLastRefreshStart >= MIN_REFRESH_INTERVAL_MS))) {
                sLastRefreshStart = now;
                final CountDownLatch started = new CountDownLatch(1);
                sRefresh = started;
                final Context context = getContext();
                mMainHandler.post(() -> FeatureFactory.get(context).searchFeatureProvider()
                        .getIndexingManager(context).indexDatabase(() -> {
                            synchronized (sLock) {
                                if (sRefresh == started) {
                                    sRefresh = null;
                                }
                            }
                            started.countDown();
                        }));
            }
            refresh = sRefresh;
        }
        if (refresh == null) {
            return;
        }
        final long deadline = SystemClock.elapsedRealtime() + MAX_INDEX_WAIT_MS;
        while (!refresh.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
            if (signal != null && signal.isCanceled()) {
                throw new OperationCanceledException();
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                // Answer from the index as it is; a later query waits again.
                return;
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        // Only the form with query arguments: the words never go in a selection or a URI.
        return new MatrixCursor(COLUMNS);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
